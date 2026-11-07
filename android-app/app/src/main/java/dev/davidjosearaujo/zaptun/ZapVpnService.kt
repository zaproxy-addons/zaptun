package dev.davidjosearaujo.zaptun

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.davidjosearaujo.zaptun.net.DnsRelay
import dev.davidjosearaujo.zaptun.net.Ip4
import dev.davidjosearaujo.zaptun.net.Ip6
import dev.davidjosearaujo.zaptun.net.Quic
import dev.davidjosearaujo.zaptun.net.TcpNat
import dev.davidjosearaujo.zaptun.net.TcpRelay
import dev.davidjosearaujo.zaptun.net.UdpRelay
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ZapVpnService : VpnService() {

    private lateinit var globalProxy: GlobalProxy

    /** The VPN interface and what serves it; a reconnect builds a new one before closing the old one. */
    @Volatile private var session: Session? = null

    // The resolvers of the real networks, kept current as networks come and go.
    private val underlyingDns = UnderlyingDns(InetAddress.getByName(FALLBACK_DNS), setOf(DNS_IP))
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Ports are read live, so editing them in the UI needs no reconnect.
    @Volatile private var ports: Set<Int> = Settings.DEFAULT_PORTS
    @Volatile private var allPorts = false
    private val portsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == Settings.KEY_PORTS || key == Settings.KEY_ALL_PORTS) readPorts(Settings.load(prefs))
    }

    private fun readPorts(s: Settings) {
        ports = s.ports
        allPorts = s.allPorts
    }

    override fun onCreate() {
        super.onCreate()
        globalProxy = AndroidGlobalProxy.create(this)
        val prefs = Settings.prefs(this)
        readPorts(Settings.load(prefs))
        prefs.registerOnSharedPreferenceChangeListener(portsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (StartRequest.decide(intent?.action, intent != null, Settings.wanted(this))) {
            StartRequest.Action.START -> {
                Settings.setWanted(this, true)
                val settings = Settings.load(this)
                Thread({ startVpn(settings) }, "zaptun-start").start()
            }
            StartRequest.Action.STOP -> stopVpn(byUser = true)
            StartRequest.Action.IGNORE -> {}
        }
        // If Android kills the process while connected, it restarts the service and the VPN comes back.
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn(byUser = true) // another VPN took over: don't fight it by reconnecting
    }

    override fun onDestroy() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(portsListener)
        stopTrackingNetworks()
        // Not a request to disconnect (that's stopVpn): if Android is stopping us, "wanted" stays so we come back.
        teardown()
        restoreGlobalProxy()
        if (ConnectionLog.running) ConnectionLog.status(false, "Stopped")
        super.onDestroy()
    }

    /**
     * Starts the VPN, or reconnects it with new settings. A reconnect has no gap: the new interface is
     * established while the old one still carries the traffic (Android swaps them), and only then is
     * the old session closed. Tearing down first, as zaptun used to, let apps' traffic go straight to
     * the network, around ZAP, for that moment.
     */
    @Synchronized
    private fun startVpn(settings: Settings) {
        val old = session
        var fd: ParcelFileDescriptor? = null
        var pool: ExecutorService? = null
        var tcp: TcpRelay? = null
        var udpRelay: UdpRelay? = null
        try {
            switchOffGlobalProxy()
            val proxyAddr = InetAddress.getByName(settings.proxyHost)
            val proxy = InetSocketAddress(proxyAddr, settings.proxyPort)
            seedDns()
            trackNetworks()

            val builder = Builder()
                .setSession("ZAP ${settings.proxyHost}:${settings.proxyPort}")
                .setMtu(MTU)
                .setBlocking(true)
                .addAddress(TUN_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_IP)
                // Take IPv6 too, so nothing leaks around ZAP: it is refused at once (see refuseIpv6).
                .addAddress(TUN_IP6, 128)
                .addRoute("::", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            // The relay's own sockets must stay routed through the tun, so zaptun is never excluded.
            if (settings.interceptAllApps) {
                forEachInstalled(settings.excludedApps - packageName) { builder.addDisallowedApplication(it) }
            } else {
                forEachInstalled(settings.includedApps) { builder.addAllowedApplication(it) }
                builder.addAllowedApplication(packageName)
            }

            fd = builder.establish() ?: throw IllegalStateException("VPN permission not granted")
            // From here the new interface carries the traffic.
            val tun = Tun(fd)
            pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
            val nat = TcpNat(Ip4.addr(TUN_IP), Ip4.addr(FAKE_IP))
            tcp = TcpRelay(
                bindAddress = InetAddress.getByName(TUN_IP),
                proxy = proxy,
                originalDestination = { peer ->
                    nat.lookup(peer.port)?.let {
                        // DNS over TCP (a truncated UDP answer is retried that way) goes to the real resolver.
                        if (it.dstIp == DNS_ADDR && it.dstPort == 53) InetSocketAddress(underlyingDns.servers().first(), 53)
                        else InetSocketAddress(Ip4.inet(it.dstIp), it.dstPort)
                    }
                },
                protect = { protect(it) },
                executor = pool,
                intercepts = { it in ports },
                // "Every port": the other ports are looked at briefly too, and HTTP on them goes to ZAP.
                probes = { allPorts },
                // Clients of an intercepted port speak first (TLS, HTTP), and some open sockets well before
                // using them. Waiting means those are still captured, not sent around ZAP after a second.
                firstByteTimeoutMs = { INTERCEPTED_FIRST_BYTE_WAIT_MS },
                onConnection = ConnectionLog::track,
                onOpened = ConnectionLog::opened,
                onFinished = ConnectionLog::finished,
            ).start()
            nat.listenPort = tcp.port
            udpRelay = UdpRelay(protect = { protect(it) }, executor = pool, reply = { tun.write(it) }, stats = ConnectionLog.stats)
            val dns = DnsRelay(
                upstream = underlyingDns::servers,
                protect = { protect(it) },
                executor = pool,
                reply = { tun.write(it) },
                stats = ConnectionLog.stats,
                log = ConnectionLog::add,
            )

            val s = Session(tun, nat, tcp, udpRelay, pool)
            session = s
            if (old != null) {
                // The old session's connections can't move over. Reset them now, so apps open new ones
                // (through the new session) at once, even those only waiting to receive.
                val resets = old.nat.resetsForOpenConnections()
                resets.forEach { tun.write(it) }
                old.close()
                ConnectionLog.add("Reconnected without a gap (${resets.size} open connections reset)")
            }
            startForegroundIfAllowed(settings)
            ConnectionLog.status(true, "Forwarding to ${settings.proxyHost}:${settings.proxyPort} (DNS: ${underlyingDns.servers().joinToString { it.hostAddress ?: "?" }})")
            Thread({ tunLoop(s, dns) }, "zaptun-tun").start()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            tcp?.stop()
            udpRelay?.close()
            runCatching { fd?.close() }
            pool?.shutdownNow()
            if (old != null && fd == null) {
                // The new settings failed before taking over (a bad host, say): the old session still
                // carries the traffic, so keep it rather than leave nothing.
                ConnectionLog.add("Reconnecting failed (${e.message}); still connected with the previous settings")
                return
            }
            old?.close()
            session = null
            restoreGlobalProxy()
            Settings.setWanted(this, false) // don't loop restarting something that can't start
            ConnectionLog.status(false, "Failed to start: ${e.message}")
            stopSelf()
        }
    }

    /**
     * Shows the ongoing notification, which also keeps Android from stopping the service in the
     * background. Best effort: some starts (a restart from the background) aren't allowed to do this,
     * and the VPN works without it.
     */
    private fun startForegroundIfAllowed(settings: Settings) {
        try {
            val notification = Notifications.connected(this, "${settings.proxyHost}:${settings.proxyPort}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not run in the foreground: ${e.message}")
        }
    }

    /** The VPN interface's file: packets are read from it and written back to it. */
    private class Tun(val fd: ParcelFileDescriptor) {
        private val out = FileOutputStream(fd.fileDescriptor)

        /** Writes the first [length] bytes of [packet]. */
        fun write(packet: ByteArray, length: Int = packet.size) {
            synchronized(out) {
                try {
                    out.write(packet, 0, length)
                } catch (e: Exception) {
                    Log.w(TAG, "tun write failed: ${e.message}")
                }
            }
        }
    }

    /** One VPN interface and everything serving it. */
    private class Session(val tun: Tun, val nat: TcpNat, val relay: TcpRelay, val udp: UdpRelay, val pool: ExecutorService) {
        @Volatile var open = true
            private set

        fun close() {
            open = false
            relay.stop()
            udp.close()
            runCatching { tun.fd.close() }
            pool.shutdownNow()
        }
    }

    private fun tunLoop(s: Session, dns: DnsRelay) {
        val input = FileInputStream(s.tun.fd.fileDescriptor)
        val buf = ByteArray(MTU)
        val poll = StructPollfd().apply { this.fd = s.tun.fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        var lastExpire = 0L
        try {
            while (s.open) {
                val now = System.currentTimeMillis()
                if (now - lastExpire > 10_000) {
                    s.nat.expire(now)
                    lastExpire = now
                }
                // Poll with a timeout so the end of the session is noticed without data arriving.
                if (Os.poll(arrayOf(poll), 500) <= 0) continue
                val n = input.read(buf)
                when {
                    n >= 1 && Ip4.version(buf) == 6 -> refuseIpv6(s.tun, buf, n)
                    n >= 20 && Ip4.version(buf) == 4 -> handleIpv4(s, buf, n, now, dns)
                }
            }
        } catch (e: Exception) {
            if (s.open) Log.e(TAG, "tun loop died", e)
        }
    }

    private fun handleIpv4(s: Session, buf: ByteArray, n: Int, now: Long, dns: DnsRelay) {
        val tun = s.tun
        val len = minOf(n, Ip4.totalLen(buf))
        val l4 = Ip4.headerLen(buf)
        if (len < l4 + 8) return
        when (Ip4.protocol(buf)) {
            Ip4.PROTO_TCP -> {
                if (len < l4 + 20) return
                if (Ip4.dst(buf) == DNS_ADDR && Ip4.u16(buf, l4 + 2) != 53) {
                    // Private DNS (DNS over TLS) and anything else aimed at the fake DNS address:
                    // refuse at once, so Android falls back to plain DNS without waiting.
                    val flags = Ip4.u8(buf, l4 + 13)
                    if (flags and Ip4.TCP_SYN != 0 && flags and Ip4.TCP_ACK == 0) tun.write(Ip4.resetForSegment(buf, len))
                    return
                }
                // Rewritten in place and written straight back, so no per-packet allocation.
                if (s.nat.translate(buf, now)) {
                    tun.write(buf, len)
                } else if (s.nat.isUnknownAppSegment(buf)) {
                    // A connection from before a reconnect, or one that expired: tell the app it's gone,
                    // so it opens a new one now instead of retransmitting until it times out.
                    tun.write(Ip4.resetForSegment(buf, len))
                }
            }
            Ip4.PROTO_UDP -> {
                val dstPort = Ip4.u16(buf, l4 + 2)
                val dst = Ip4.dst(buf)
                when {
                    // DNS: the VPN's own address is answered from the device's resolvers; a server the
                    // app chose itself is asked directly.
                    dstPort == 53 -> dns.handle(buf.copyOf(len), if (dst == DNS_ADDR) null else Ip4.inet(dst))
                    // QUIC (HTTP/3) can't go through ZAP. Refused, the client falls back to TCP at once.
                    // UDP 443 always; any other port when the packet is a QUIC Initial.
                    dstPort == QUIC_PORT || Quic.isInitial(buf, l4 + 8, len - l4 - 8) -> {
                        ConnectionLog.stats.udpRefused(dstPort)
                        tun.write(Ip4.portUnreachable(buf, len))
                    }
                    // Everything else (calls, NTP, games) passes straight through, uncaptured.
                    else -> s.udp.handle(buf, len)
                }
            }
        }
    }

    /**
     * IPv6 isn't carried: AAAA records are hidden so apps use IPv4. An app that still tries IPv6 (an
     * address literal, a cached record) gets a RST or ICMPv6 "port unreachable" at once, so it falls
     * back immediately instead of waiting for a timeout.
     */
    private fun refuseIpv6(tun: Tun, buf: ByteArray, n: Int) {
        val reply = when {
            Ip6.isTcpSyn(buf, n) -> Ip6.resetFor(buf)
            Ip6.nextHeader(buf) == Ip6.NEXT_UDP && n >= Ip6.HEADER + 8 -> Ip6.portUnreachable(buf, n)
            else -> return
        }
        ConnectionLog.stats.ipv6Refused.incrementAndGet()
        tun.write(reply)
    }

    /** Calls [action] for each package, skipping (and logging) the ones that aren't installed. */
    private inline fun forEachInstalled(packages: Collection<String>, action: (String) -> Unit) {
        for (pkg in packages) {
            try {
                action(pkg)
            } catch (e: Exception) {
                ConnectionLog.add("Skipping unknown app $pkg")
            }
        }
    }

    /** The resolvers of the network in use right now, before the VPN takes over. */
    private fun seedDns() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return
        cm.getLinkProperties(network)?.let { underlyingDns.update(network, it.dnsServers) }
    }

    /** Follows the real networks, so a switch (Wi-Fi to mobile) updates the resolvers the relay asks. */
    private fun trackNetworks() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                underlyingDns.update(network, linkProperties.dnsServers)
                ConnectionLog.add("DNS servers: ${underlyingDns.servers().joinToString { it.hostAddress ?: "?" }}")
            }

            override fun onLost(network: Network) = underlyingDns.lost(network)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "could not follow network changes: ${e.message}")
        }
    }

    private fun stopTrackingNetworks() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    /**
     * Apps that honour Android's global proxy would talk to that proxy and skip the VPN, so it is
     * switched off while connected (and put back by [restoreGlobalProxy]).
     */
    private fun switchOffGlobalProxy() {
        val note = when (val outcome = globalProxy.clear()) {
            is GlobalProxy.Outcome.NotSet -> null
            is GlobalProxy.Outcome.Cleared -> getString(R.string.proxy_note_cleared, outcome.was)
            is GlobalProxy.Outcome.Failed -> getString(R.string.proxy_note_failed, outcome.current)
        }
        note?.let { ConnectionLog.add(it) } // logcat only
    }

    private fun restoreGlobalProxy() {
        globalProxy.restore()
    }

    /** [byUser]: the user (or another VPN) ended it, so it must not come back by itself. */
    @Synchronized
    private fun stopVpn(byUser: Boolean) {
        if (byUser) Settings.setWanted(this, false)
        stopTrackingNetworks()
        teardown()
        restoreGlobalProxy()
        ConnectionLog.status(false, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        session?.close()
        session = null
    }

    companion object {
        const val ACTION_START = "dev.davidjosearaujo.zaptun.START"
        const val ACTION_STOP = "dev.davidjosearaujo.zaptun.STOP"
        private const val TAG = "zaptun"
        private const val MTU = 1500
        private const val TUN_IP = "10.215.0.1"
        private const val FAKE_IP = "10.215.0.2"
        private const val DNS_IP = "10.215.0.53"
        private val DNS_ADDR = Ip4.addr(DNS_IP)
        private const val TUN_IP6 = "fd00:215::1"
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val QUIC_PORT = 443
        private const val INTERCEPTED_FIRST_BYTE_WAIT_MS = 30_000
    }
}
