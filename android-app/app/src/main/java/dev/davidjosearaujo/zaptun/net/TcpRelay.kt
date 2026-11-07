package dev.davidjosearaujo.zaptun.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Accepts the connections redirected by [TcpNat]. Connections to ports that [intercepts] rejects go
 * straight to their destination, unless [probes] says to look at them too (see below). For the
 * intercepted ports it peeks at the first bytes and:
 *  - TLS or HTTP: opens `CONNECT <sni|host|ip>:<port>` on the proxy (ZAP) and pipes the stream through it;
 *  - a request already addressed to a proxy (CONNECT, absolute URI): hands it to the proxy unchanged;
 *  - anything else (or a server-speaks-first protocol): connects straight to the original destination.
 *
 * On a probed port the relay waits only [probeTimeoutMs] for the first bytes, and sends to ZAP only
 * what is surely HTTP: a plain HTTP request, or TLS whose ClientHello offers HTTP with ALPN. Other
 * TLS (MQTT, IMAP, XMPP over TLS...) goes direct, since ZAP would break it. Add the port to the
 * intercepted ones to capture any TLS on it.
 *
 * Every connection gets a [ConnectionRecord]: [onConnection] when it arrives, [onOpened] once its route
 * is known, [onFinished] when it ends (cleanly or not).
 */
class TcpRelay(
    private val bindAddress: InetAddress,
    private val proxy: InetSocketAddress,
    private val originalDestination: (InetSocketAddress) -> InetSocketAddress?,
    private val protect: (Socket) -> Boolean,
    private val executor: ExecutorService,
    private val intercepts: (port: Int) -> Boolean = { true },
    /** For ports [intercepts] rejects: whether to look for HTTP on them anyway. */
    private val probes: (port: Int) -> Boolean = { false },
    private val probeTimeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
    /** How long to wait for the app's first bytes on a port. TLS and HTTP clients speak first, so web ports can wait long. */
    private val firstByteTimeoutMs: (port: Int) -> Int = { DEFAULT_FIRST_BYTE_TIMEOUT_MS },
    private val onConnection: (ConnectionRecord) -> Unit = {},
    private val onOpened: (ConnectionRecord) -> Unit = {},
    private val onFinished: (ConnectionRecord) -> Unit = {},
) {
    enum class Route { PROXY, DIRECT }

    private lateinit var server: ServerSocket
    private val nextId = AtomicLong()

    /** Every socket of a connection in progress, so [stop] can end them all. */
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = server.localPort

    fun start(): TcpRelay {
        server = ServerSocket(0, 256, bindAddress)
        executor.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                executor.execute { handle(client) }
            }
        }
        return this
    }

    /** Stops accepting and ends every connection in progress. */
    fun stop() {
        runCatching { server.close() }
        sockets.forEach { it.closeQuietly() }
    }

    private fun handle(client: Socket) {
        val record = ConnectionRecord(nextId.incrementAndGet(), System.currentTimeMillis())
        onConnection(record)
        var upstream: Socket? = null
        sockets += client
        try {
            client.tcpNoDelay = true
            val dst = originalDestination(client.remoteSocketAddress as InetSocketAddress)
                ?: throw IOException("unknown session for ${client.remoteSocketAddress}")
            record.host = dst.address.hostAddress ?: "?"
            record.port = dst.port

            val listed = intercepts(dst.port)
            val probed = !listed && probes(dst.port)
            val sniff = listed || probed
            val buf = if (sniff) ByteArray(MAX_PEEK) else NO_BYTES // skipped ports need no buffer
            val len = if (sniff) peek(client, buf, if (listed) firstByteTimeoutMs(dst.port) else probeTimeoutMs) else 0
            if (len < 0) {
                // The app closed the connection without sending anything: a socket it opened ahead
                // of time and never used. There is nothing to relay.
                record.closedFirstBy(ConnectionRecord.Closer.APP)
                return
            }
            record.kind = if (sniff) Sniffer.kind(buf, len) else Sniffer.Kind.PORT_SKIPPED
            val name = when (record.kind) {
                Sniffer.Kind.TLS -> Sniffer.tlsSni(buf, len)
                Sniffer.Kind.HTTP -> Sniffer.httpHost(buf, len)
                else -> null
            }
            val host = name ?: record.host
            if (name != null) record.host = name

            val isHttp = when (record.kind) {
                Sniffer.Kind.HTTP -> true
                Sniffer.Kind.TLS -> listed || Sniffer.tlsOffersHttp(buf, len)
                else -> false
            }
            val toProxy = isHttp && dst != proxy
            val proxyRequest = record.kind == Sniffer.Kind.HTTP && Sniffer.isProxyRequest(buf, len)
            // Registered before anything can fail, so the finally block always closes and forgets it.
            upstream = Socket().also { sockets += it }
            upstream.bind(null) // creates the fd, which protect() needs
            upstream.tcpNoDelay = true
            if (!protect(upstream)) throw IOException("could not protect socket")
            if (toProxy) {
                upstream.connect(proxy, CONNECT_TIMEOUT_MS)
                if (!proxyRequest) connectThroughProxy(upstream, host, dst.port)
            } else {
                upstream.connect(dst, CONNECT_TIMEOUT_MS)
            }
            record.route = if (toProxy) Route.PROXY else Route.DIRECT
            record.opened()
            onOpened(record)

            upstream.getOutputStream().apply { write(buf, 0, len); flush() }
            record.up.addAndGet(len.toLong())
            pipe(client, upstream, record, toZap = toProxy || dst == proxy, unanswered = len > 0)
        } catch (e: Exception) {
            record.failedWith(e.message ?: e.javaClass.simpleName)
            runCatching { client.setSoLinger(true, 0) } // reset, so the app fails fast
        } finally {
            client.closeQuietly()
            upstream.closeQuietly()
            sockets -= client
            upstream?.let { sockets -= it }
            if (record.finish(System.currentTimeMillis())) onFinished(record)
        }
    }

    /**
     * Reads the start of the stream: a full TLS record or HTTP header block. Returns 0 if the app
     * stays quiet for [firstByteTimeout] ms, and -1 if it closes the connection without sending anything.
     */
    private fun peek(client: Socket, buf: ByteArray, firstByteTimeout: Int): Int {
        val input = client.getInputStream()
        var len = 0
        try {
            client.soTimeout = firstByteTimeout
            val n = input.read(buf, 0, buf.size)
            if (n < 0) return -1
            if (n == 0) return 0
            len = n
            client.soTimeout = REST_TIMEOUT_MS
            while (len < buf.size) {
                val done = when {
                    Sniffer.isTls(buf, len) -> Sniffer.tlsRecordEnd(buf, len).let { it in 1..len }
                    Sniffer.isHttp(buf, len) -> Sniffer.httpHeadersDone(buf, len)
                    else -> true
                }
                if (done) break
                val m = input.read(buf, len, buf.size - len)
                if (m <= 0) break
                len += m
            }
        } catch (_: SocketTimeoutException) {
        } finally {
            client.soTimeout = 0
        }
        return len
    }

    private fun connectThroughProxy(upstream: Socket, host: String, port: Int) {
        val target = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        val out = upstream.getOutputStream()
        out.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: Keep-Alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        upstream.soTimeout = CONNECT_TIMEOUT_MS
        val input = upstream.getInputStream()
        val resp = ByteArray(8192)
        var len = 0
        while (!Sniffer.httpHeadersDone(resp, len)) {
            if (len == resp.size) throw IOException("proxy response too large")
            // Read one byte at a time so nothing after the header block is consumed.
            val c = input.read()
            if (c < 0) throw IOException("proxy closed the connection")
            resp[len++] = c.toByte()
        }
        upstream.soTimeout = 0
        val status = String(resp, 0, len, Charsets.ISO_8859_1).substringBefore("\r\n")
        if (status.split(' ').getOrNull(1) != "200") throw IOException("proxy said: $status")
    }

    /**
     * Pipes both ways until both directions have ended.
     *
     * ZAP drops a tunnel as soon as its client half-closes it, even with a reply on the way, so an
     * app that sends a request and then shuts down its sending side would get nothing back. When
     * [toZap], the app's half-close is therefore held back while what it sent is still unanswered
     * (it sent bytes after ZAP's last ones), and the connection ends when ZAP closes it, which ZAP
     * does by itself once the exchange is over. An app that closes after its reply has come is
     * passed on at once, so ZAP isn't left holding a connection nobody uses.
     */
    private fun pipe(client: Socket, upstream: Socket, record: ConnectionRecord, toZap: Boolean, unanswered: Boolean) {
        val awaitingReply = AtomicBoolean(unanswered)
        val toApp = executor.submit {
            copy(upstream, client, record.down, record, ConnectionRecord.Closer.SERVER, onData = { awaitingReply.set(false) })
        }
        copy(
            client, upstream, record.up, record, ConnectionRecord.Closer.APP,
            onData = { awaitingReply.set(true) },
            passHalfClose = { !(toZap && awaitingReply.get()) },
        )
        runCatching { toApp.get() }
        client.closeQuietly()
        upstream.closeQuietly()
    }

    /**
     * Copies [from] to [to] until EOF, counting the bytes, then half-closes [to] unless
     * [passHalfClose] says otherwise. [onData] runs for each chunk, before it's written. On an error
     * it tears down both sockets. The first end of a stream is remembered as who closed the connection.
     */
    private fun copy(
        from: Socket,
        to: Socket,
        counter: AtomicLong,
        record: ConnectionRecord,
        who: ConnectionRecord.Closer,
        onData: () -> Unit = {},
        passHalfClose: () -> Boolean = { true },
    ) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                onData()
                output.write(buf, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
            record.closedFirstBy(who)
            if (passHalfClose()) to.shutdownOutput()
        } catch (e: IOException) {
            record.failedWith(e.message ?: "connection error")
            to.closeQuietly()
            from.closeQuietly()
        }
    }

    private fun Socket?.closeQuietly() {
        runCatching { this?.close() }
    }

    companion object {
        private val NO_BYTES = ByteArray(0)
        private const val MAX_PEEK = 32 * 1024
        const val DEFAULT_FIRST_BYTE_TIMEOUT_MS = 1000
        const val DEFAULT_PROBE_TIMEOUT_MS = 500
        private const val REST_TIMEOUT_MS = 3000
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
