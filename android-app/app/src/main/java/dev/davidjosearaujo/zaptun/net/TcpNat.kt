package dev.davidjosearaujo.zaptun.net

import dev.davidjosearaujo.zaptun.net.Ip4.TCP_ACK
import dev.davidjosearaujo.zaptun.net.Ip4.TCP_FIN
import dev.davidjosearaujo.zaptun.net.Ip4.TCP_RST
import dev.davidjosearaujo.zaptun.net.Ip4.TCP_SYN

/**
 * Redirects every TCP connection that enters the tun to a local relay socket by
 * rewriting packet headers, so the kernel's own TCP stack does all the hard work.
 *
 * App packet   tunIp:appPort  -> dstIp:dstPort       becomes  fakeIp:fakePort -> tunIp:listenPort
 * Relay reply  tunIp:listenPort -> fakeIp:fakePort   becomes  dstIp:dstPort   -> tunIp:appPort
 *
 * The relay learns the original destination with [lookup] on the peer's port.
 */
class TcpNat(private val tunIp: Int, private val fakeIp: Int) {

    class Session(val appPort: Int, val dstIp: Int, val dstPort: Int, val fakePort: Int) {
        @Volatile var lastSeen = 0L

        /** Each side has sent its FIN. One FIN is only a half-close: data can still flow the other way. */
        @Volatile var appFin = false
        @Volatile var relayFin = false

        /** When the connection ended (both FINs, or a RST); 0 while it is still open. */
        @Volatile var closedAt = 0L

        /** Some close has started, so a new SYN for the same ports is a new connection, not a retransmit. */
        fun closing() = closedAt != 0L || appFin || relayFin

        /** The last acknowledgement number the app sent: the next sequence number it expects. */
        @Volatile var appAck = 0
        @Volatile var appAckSeen = false
    }

    @Volatile var listenPort = 0

    private val byApp = HashMap<Long, Session>()
    private val byFake = arrayOfNulls<Session>(65536)
    private var nextPort = FIRST_PORT

    /** Rewrites the IPv4/TCP packet in place. Returns false if it should be dropped. */
    @Synchronized
    fun translate(p: ByteArray, now: Long): Boolean {
        val l4 = Ip4.headerLen(p)
        val src = Ip4.src(p)
        val dst = Ip4.dst(p)
        val srcPort = Ip4.u16(p, l4)
        val dstPort = Ip4.u16(p, l4 + 2)
        val flags = Ip4.u8(p, l4 + 13)
        if (src != tunIp || listenPort == 0) return false

        val s: Session
        if (dst == fakeIp && srcPort == listenPort) {
            s = byFake[dstPort] ?: return false
            Ip4.put32(p, 12, s.dstIp)
            Ip4.put16(p, l4, s.dstPort)
            Ip4.put32(p, 16, tunIp)
            Ip4.put16(p, l4 + 2, s.appPort)
        } else {
            val key = key(srcPort, dst, dstPort)
            val isSyn = flags and TCP_SYN != 0 && flags and TCP_ACK == 0
            var found = byApp[key]
            if (isSyn && found != null && found.closing()) {
                remove(found)
                found = null
            }
            if (found == null) {
                if (!isSyn) return false
                found = allocate(srcPort, dst, dstPort) ?: return false
                byApp[key] = found
            }
            s = found
            if (flags and TCP_ACK != 0) {
                s.appAck = Ip4.u32(p, l4 + 8)
                s.appAckSeen = true
            }
            Ip4.put32(p, 12, fakeIp)
            Ip4.put16(p, l4, s.fakePort)
            Ip4.put32(p, 16, tunIp)
            Ip4.put16(p, l4 + 2, listenPort)
        }

        s.lastSeen = now
        trackClose(s, flags, fromRelay = dst == fakeIp && srcPort == listenPort, now)
        Ip4.fixChecksums(p)
        return true
    }

    @Synchronized
    fun lookup(fakePort: Int): Session? = if (fakePort in 0..65535) byFake[fakePort] else null

    /**
     * True if [p], which [translate] refused, is the app's segment for a connection this NAT doesn't
     * have: one from before a reconnect, or one that expired. The app should get a RST for it, or it
     * would retransmit into nothing until it times out.
     */
    @Synchronized
    fun isUnknownAppSegment(p: ByteArray): Boolean {
        val l4 = Ip4.headerLen(p)
        val flags = Ip4.u8(p, l4 + 13)
        if (Ip4.src(p) != tunIp || Ip4.dst(p) == fakeIp) return false
        if (flags and TCP_RST != 0 || flags and TCP_SYN != 0) return false
        return byApp[key(Ip4.u16(p, l4), Ip4.dst(p), Ip4.u16(p, l4 + 2))] == null
    }

    /**
     * RSTs, as if from each destination, for every connection still open. Used when this NAT is
     * replaced: those connections can't continue, and an app that is only waiting to receive (a
     * WebSocket, a long poll) would otherwise never find out.
     */
    @Synchronized
    fun resetsForOpenConnections(): List<ByteArray> = byApp.values
        .filter { it.closedAt == 0L && it.appAckSeen }
        .map { Ip4.tcpReset(it.dstIp, it.dstPort, tunIp, it.appPort, seq = it.appAck) }

    /** Drops sessions that closed a while ago or have been idle for too long. */
    @Synchronized
    fun expire(now: Long) {
        val dead = byApp.values.filter {
            (it.closedAt != 0L && now - it.closedAt > CLOSED_TTL_MS) || now - it.lastSeen > IDLE_TTL_MS
        }
        dead.forEach(::remove)
    }

    @Synchronized
    fun size() = byApp.size

    /** A RST ends the connection at once; it is over for good once both sides have sent a FIN. */
    private fun trackClose(s: Session, flags: Int, fromRelay: Boolean, now: Long) {
        if (flags and TCP_FIN != 0) {
            if (fromRelay) s.relayFin = true else s.appFin = true
        }
        if (s.closedAt == 0L && (flags and TCP_RST != 0 || (s.appFin && s.relayFin))) s.closedAt = now
    }

    private fun allocate(appPort: Int, dstIp: Int, dstPort: Int): Session? {
        repeat(LAST_PORT - FIRST_PORT + 1) {
            val port = nextPort
            nextPort = if (nextPort == LAST_PORT) FIRST_PORT else nextPort + 1
            if (byFake[port] == null) {
                return Session(appPort, dstIp, dstPort, port).also { byFake[port] = it }
            }
        }
        return null
    }

    private fun remove(s: Session) {
        byApp.remove(key(s.appPort, s.dstIp, s.dstPort))
        if (byFake[s.fakePort] === s) byFake[s.fakePort] = null
    }

    private fun key(appPort: Int, dstIp: Int, dstPort: Int) =
        (appPort.toLong() shl 48) or (dstPort.toLong() shl 32) or (dstIp.toLong() and 0xffffffffL)

    companion object {
        private const val FIRST_PORT = 1024
        private const val LAST_PORT = 65535
        private const val CLOSED_TTL_MS = 60_000L
        private const val IDLE_TTL_MS = 2 * 60 * 60_000L
    }
}
