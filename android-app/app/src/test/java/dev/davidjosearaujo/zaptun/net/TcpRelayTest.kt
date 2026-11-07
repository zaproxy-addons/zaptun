package dev.davidjosearaujo.zaptun.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class TcpRelayTest {
    private val lo = InetAddress.getLoopbackAddress()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    /** A server on a free loopback port: [handle] runs on its own thread for each connection. */
    private fun serve(handle: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 50, lo).also(closeables::add)
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { handle(s) }
            }
        }
        return server
    }

    /** Reads up to the blank line that ends an HTTP header block, and returns it. */
    private fun InputStream.readHead(): String {
        val head = ByteArrayOutputStream()
        while (!head.toString().endsWith("\r\n\r\n")) {
            val b = read()
            if (b < 0) throw IOException("closed before the end of the headers")
            head.write(b)
        }
        return head.toString()
    }

    /** Reads up to [n] bytes, fewer if the stream ends first, as text. */
    private fun Socket.readText(n: Int): String {
        val buf = ByteArray(n)
        var len = 0
        while (len < n) {
            val got = getInputStream().read(buf, len, n - len)
            if (got < 0) break
            len += got
        }
        return String(buf, 0, len, Charsets.ISO_8859_1)
    }

    /** A fake ZAP: records the CONNECT line, answers 200, then echoes uppercased bytes. */
    private fun fakeProxy(connects: MutableList<String>) = serve { echo(it, connects, upper = true) }

    /** A plain server that echoes lowercase, standing in for the real destination. */
    private fun fakeOrigin() = serve { echo(it, null, upper = false) }

    private fun echo(s: Socket, connects: MutableList<String>?, upper: Boolean) = s.use {
        val input = s.getInputStream()
        val out = s.getOutputStream()
        if (connects != null) {
            connects += input.readHead().substringBefore("\r\n")
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        }
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            out.write((if (upper) text.uppercase() else text).toByteArray(Charsets.ISO_8859_1))
        }
    }

    private fun relay(
        proxy: ServerSocket,
        origin: InetSocketAddress,
        records: MutableList<ConnectionRecord>,
        intercepts: (Int) -> Boolean = { true },
        firstByteTimeoutMs: (Int) -> Int = { TcpRelay.DEFAULT_FIRST_BYTE_TIMEOUT_MS },
        finished: MutableList<ConnectionRecord> = CopyOnWriteArrayList(),
        probes: (Int) -> Boolean = { false },
        probeTimeoutMs: Int = TcpRelay.DEFAULT_PROBE_TIMEOUT_MS,
        protect: (Socket) -> Boolean = { true },
    ) =
        TcpRelay(
            bindAddress = lo,
            proxy = InetSocketAddress(lo, proxy.localPort),
            originalDestination = { origin },
            protect = protect,
            executor = pool,
            intercepts = intercepts,
            probes = probes,
            probeTimeoutMs = probeTimeoutMs,
            firstByteTimeoutMs = firstByteTimeoutMs,
            onConnection = { records += it },
            onFinished = { finished += it },
        ).start().also { r -> closeables += AutoCloseable { r.stop() } }

    private fun exchange(port: Int, send: ByteArray, expect: Int): String = Socket(lo, port).use { c ->
        c.soTimeout = 5000
        c.getOutputStream().write(send)
        c.readText(expect)
    }

    @Test
    fun httpGoesThroughProxyWithHostName() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 80)
        val r = relay(fakeProxy(connects), origin, records)

        val req = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        assertEquals(req.uppercase(), exchange(r.port, req.toByteArray(), req.length))
        assertEquals(listOf("CONNECT example.com:80 HTTP/1.1"), connects)
        assertEquals(TcpRelay.Route.PROXY, records.single().route)
    }

    @Test
    fun silentClientGoesDirect() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = fakeOrigin()
        val r = relay(fakeProxy(connects), InetSocketAddress(lo, origin.localPort), records)

        Socket(lo, r.port).use { c ->
            c.soTimeout = 5000
            Thread.sleep(1500) // longer than the sniffing window
            c.getOutputStream().write("Hello".toByteArray())
            assertEquals("Hello", c.readText(5))
        }
        assertEquals(emptyList<String>(), connects)
        assertEquals(TcpRelay.Route.DIRECT, records.single().route)
    }

    @Test
    fun trafficToTheProxyItselfIsNotWrapped() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val proxy = fakeProxy(connects)
        // An app that already uses the system proxy talks to ZAP directly.
        val r = relay(proxy, InetSocketAddress(lo, proxy.localPort), records)

        val req = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
        exchange(r.port, req.toByteArray(), "HTTP/1.1 200".length)
        assertEquals(listOf("CONNECT example.com:443 HTTP/1.1"), connects)
        assertEquals(TcpRelay.Route.DIRECT, records.single().route)
    }

    @Test
    fun proxyRequestsAreNotWrappedTwice() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        // An app still using the old global proxy (10.0.2.2:9090) while zaptun points at 127.0.0.1.
        val origin = InetSocketAddress(InetAddress.getByName("10.0.2.2"), 9090)
        val r = relay(fakeProxy(connects), origin, records)

        val req = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
        exchange(r.port, req.toByteArray(), "HTTP/1.1 200".length)
        assertEquals(listOf("CONNECT example.com:443 HTTP/1.1"), connects)
        assertEquals(TcpRelay.Route.PROXY, records.single().route)
    }

    @Test
    fun portsNotInterceptedGoDirectWithoutSniffing() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = fakeOrigin()
        val r = relay(fakeProxy(connects), InetSocketAddress(lo, origin.localPort), records, intercepts = { false })

        // Plain HTTP would normally go to the proxy, but this port isn't intercepted.
        val req = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        assertEquals(req, exchange(r.port, req.toByteArray(), req.length))
        assertEquals(emptyList<String>(), connects)
        assertEquals(Sniffer.Kind.PORT_SKIPPED, records.single().kind)
        assertEquals(TcpRelay.Route.DIRECT, records.single().route)
    }

    private fun await(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun theLifecycleCountsBytesAndSaysWhoClosed() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443)
        val r = relay(fakeProxy(connects), origin, records, finished = finished)

        val req = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        exchange(r.port, req.toByteArray(), req.length) // closes its end afterwards

        await("the connection to finish") { finished.isNotEmpty() }
        val c = finished.single()
        assertEquals(ConnectionRecord.State.CLOSED, c.state)
        assertEquals(ConnectionRecord.Closer.APP, c.closedBy)
        assertEquals(req.length.toLong(), c.up.get())
        assertEquals(req.length.toLong(), c.down.get())
        assertEquals(Sniffer.Kind.HTTP, c.kind)
        assertEquals("example.com", c.host)
        assertEquals(TcpRelay.Route.PROXY, c.route)
        assertEquals(null, c.error)
    }

    @Test
    fun aConnectionClosedByTheServerSaysSo() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        // A destination that answers and hangs up straight away.
        val origin = serve { it.use { s -> s.getOutputStream().write("bye".toByteArray()) } }
        val r = relay(fakeProxy(CopyOnWriteArrayList()), InetSocketAddress(lo, origin.localPort), records, intercepts = { false }, finished = finished)

        Socket(lo, r.port).use { c ->
            c.soTimeout = 5000
            assertEquals("bye", c.readText(3))
            // The relay passes the server's hang-up on as end of stream, then waits for the app to close its side.
            assertEquals(-1, c.getInputStream().read())
        }
        await("the connection to finish") { finished.isNotEmpty() }
        assertEquals(ConnectionRecord.Closer.SERVER, finished.single().closedBy)
        assertEquals(3L, finished.single().down.get())
    }

    @Test
    fun anAppThatClosesWithoutSendingAnythingOpensNoUpstreamConnection() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        val reached = CopyOnWriteArrayList<Socket>()
        val origin = serve { reached += it }
        val r = relay(fakeProxy(CopyOnWriteArrayList()), InetSocketAddress(lo, origin.localPort), records, finished = finished)

        Socket(lo, r.port).close() // a socket opened ahead of time and never used

        await("the connection to finish") { finished.isNotEmpty() }
        val c = finished.single()
        assertEquals(ConnectionRecord.State.CLOSED, c.state)
        assertEquals(ConnectionRecord.Closer.APP, c.closedBy)
        assertEquals(null, c.route)
        Thread.sleep(200)
        assertEquals(0, reached.size)
    }

    @Test
    fun aClientThatSpeaksLateOnAnInterceptedPortIsStillCaptured() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443)
        // Waits well past the 1.5 s the client takes to send its request.
        val r = relay(fakeProxy(connects), origin, records, firstByteTimeoutMs = { 10_000 })

        Socket(lo, r.port).use { c ->
            c.soTimeout = 5000
            Thread.sleep(1500)
            val req = "GET / HTTP/1.1\r\nHost: late.example.com\r\n\r\n"
            c.getOutputStream().write(req.toByteArray())
            assertEquals(req.uppercase(), c.readText(req.length))
        }
        assertEquals(listOf("CONNECT late.example.com:443 HTTP/1.1"), connects)
        assertEquals(TcpRelay.Route.PROXY, records.single().route)
    }

    /**
     * Behaves like ZAP: after the CONNECT and a request, it takes [replyDelayMs] to answer, and if the
     * client half-closes before then, it drops the tunnel without answering.
     */
    private fun zapLikeProxy(replyDelayMs: Int = 300) = serve { s ->
        s.use {
            val input = s.getInputStream()
            input.readHead()
            s.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            input.readHead()
            s.soTimeout = replyDelayMs
            val halfClosed = try {
                input.read() < 0
            } catch (_: java.net.SocketTimeoutException) {
                false
            }
            if (!halfClosed) s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
        }
    }

    /** Sends [req], half-closes, and reads until the relay ends the stream. */
    private fun sendHalfCloseAndReadAll(port: Int, req: String): String = Socket(lo, port).use { c ->
        c.soTimeout = 5000
        c.getOutputStream().write(req.toByteArray())
        c.shutdownOutput()
        String(c.getInputStream().readBytes(), Charsets.ISO_8859_1)
    }

    @Test
    fun anAppThatHalfClosesAfterItsRequestStillGetsZapsReply() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 80)
        val r = relay(zapLikeProxy(), origin, records, finished = finished)

        val reply = sendHalfCloseAndReadAll(r.port, "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")

        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", reply)
        await("the connection to finish") { finished.isNotEmpty() }
        assertEquals(ConnectionRecord.Closer.APP, finished.single().closedBy)
        assertEquals(null, finished.single().error)
    }

    @Test
    fun aDirectConnectionStillPassesTheHalfCloseOn() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        // A destination that answers only once the app has finished sending.
        val origin = serve { s ->
            s.use {
                val got = it.getInputStream().readBytes()
                it.getOutputStream().write("got ${got.size}".toByteArray())
            }
        }
        val r = relay(fakeProxy(CopyOnWriteArrayList()), InetSocketAddress(lo, origin.localPort), records, intercepts = { false })

        assertEquals("got 5", sendHalfCloseAndReadAll(r.port, "hello"))
        assertEquals(TcpRelay.Route.DIRECT, records.single().route)
    }

    /** Starts a TLS handshake to the relay, offering [alpn], and gives up after a moment (nobody answers TLS). */
    private fun tlsClient(port: Int, alpn: List<String>) = thread(isDaemon = true) {
        runCatching {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
            val ssl = ctx.socketFactory.createSocket(Socket(lo, port), "odd.example.com", port, true) as SSLSocket
            ssl.use {
                if (alpn.isNotEmpty()) it.sslParameters = it.sslParameters.apply { applicationProtocols = alpn.toTypedArray() }
                it.soTimeout = 2000
                it.startHandshake()
            }
        }
    }

    @Test
    fun aProbedPortSendsPlainHttpToZap() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = fakeOrigin()
        val r = relay(fakeProxy(connects), InetSocketAddress(lo, origin.localPort), records, intercepts = { false }, probes = { true })

        val req = "GET / HTTP/1.1\r\nHost: odd.example.com\r\n\r\n"
        assertEquals(req.uppercase(), exchange(r.port, req.toByteArray(), req.length))
        assertEquals(listOf("CONNECT odd.example.com:${origin.localPort} HTTP/1.1"), connects)
        assertEquals(TcpRelay.Route.PROXY, records.single().route)
    }

    @Test
    fun aProbedPortSendsTlsToZapOnlyWhenItOffersHttp() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = fakeOrigin()
        val r = relay(fakeProxy(connects), InetSocketAddress(lo, origin.localPort), records, intercepts = { false }, probes = { true })

        tlsClient(r.port, listOf("h2", "http/1.1"))
        await("the HTTP-offering TLS to reach ZAP") { connects.isNotEmpty() }
        assertEquals(listOf("CONNECT odd.example.com:${origin.localPort} HTTP/1.1"), connects)

        // MQTT over TLS, say: ZAP would break it, so it goes direct.
        tlsClient(r.port, listOf("mqtt"))
        await("the other TLS to be routed") { records.size == 2 && records[1].route != null }
        assertEquals(Sniffer.Kind.TLS, records[1].kind)
        assertEquals(TcpRelay.Route.DIRECT, records[1].route)
        assertEquals(1, connects.size)
    }

    @Test
    fun aListedPortStillSendsAnyTlsToZap() {
        val connects = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 8883)
        val r = relay(fakeProxy(connects), origin, records, intercepts = { true }, probes = { true })

        tlsClient(r.port, emptyList())
        await("the TLS to reach ZAP") { connects.isNotEmpty() }
        assertEquals(listOf("CONNECT odd.example.com:8883 HTTP/1.1"), connects)
    }

    @Test
    fun aProbedPortWaitsOnlyBrieflyForAServerThatSpeaksFirst() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        // SMTP-like: the server greets first.
        val origin = serve { it.use { s -> s.getOutputStream().write("220 ready\r\n".toByteArray()); s.getInputStream().read() } }
        val r = relay(fakeProxy(CopyOnWriteArrayList()), InetSocketAddress(lo, origin.localPort), records, intercepts = { false }, probes = { true }, probeTimeoutMs = 200)

        val started = System.currentTimeMillis()
        Socket(lo, r.port).use { c ->
            c.soTimeout = 5000
            val greeting = c.getInputStream().bufferedReader().readLine()
            assertEquals("220 ready", greeting)
        }
        val waited = System.currentTimeMillis() - started
        assertTrue("waited $waited ms", waited < 1500)
        assertEquals(TcpRelay.Route.DIRECT, records.single().route)
    }

    @Test
    fun stopEndsTheConnectionsInProgress() {
        val origin = fakeOrigin()
        val r = relay(fakeProxy(CopyOnWriteArrayList()), InetSocketAddress(lo, origin.localPort), CopyOnWriteArrayList(), intercepts = { false })

        Socket(lo, r.port).use { c ->
            c.soTimeout = 3000
            c.getOutputStream().write("ping".toByteArray())
            assertEquals("ping", c.readText(4))

            r.stop()

            val end = runCatching { c.getInputStream().read() }.getOrElse { -1 }
            assertEquals(-1, end)
        }
    }

    @Test
    fun anUpstreamSocketThatCantBeProtectedIsClosedAndForgotten() {
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        val tried = CopyOnWriteArrayList<Socket>()
        val origin = InetSocketAddress(InetAddress.getByName("93.184.216.34"), 80)
        val r = relay(
            fakeProxy(CopyOnWriteArrayList()), origin, CopyOnWriteArrayList(), finished = finished,
            protect = { tried += it; false },
        )

        Socket(lo, r.port).use { c ->
            c.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
            await("the connection to finish") { finished.isNotEmpty() }
        }
        assertEquals("could not protect socket", finished.single().error)
        // Nothing may keep it open: it was created for a connection that never happened.
        assertTrue("the upstream socket was left open", tried.single().isClosed)
    }

    @Test
    fun aRefusedConnectionToTheProxyIsRecordedAsFailed() {
        val records = CopyOnWriteArrayList<ConnectionRecord>()
        val finished = CopyOnWriteArrayList<ConnectionRecord>()
        val deadProxy = ServerSocket(0, 1, lo).also { it.close() } // nothing listens on this port any more
        val r = TcpRelay(
            bindAddress = lo,
            proxy = InetSocketAddress(lo, deadProxy.localPort),
            originalDestination = { InetSocketAddress(InetAddress.getByName("93.184.216.34"), 443) },
            protect = { true },
            executor = pool,
            onConnection = { records += it },
            onFinished = { finished += it },
        ).start().also { rel -> closeables += AutoCloseable { rel.stop() } }

        Socket(lo, r.port).use { c ->
            c.getOutputStream().write("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
            await("the connection to finish") { finished.isNotEmpty() }
        }
        val c = finished.single()
        assertEquals(ConnectionRecord.State.FAILED, c.state)
        assertEquals(true, c.error!!.isNotEmpty())
        assertEquals(null, c.route)
    }
}
