package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class SnifferTest {

    /** Captures the ClientHello the JDK's TLS stack sends for [host]. */
    private fun clientHello(host: String?, alpn: List<String> = emptyList()): ByteArray {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread {
                runCatching {
                    val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                    val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                    val ssl = ctx.socketFactory.createSocket(raw, host ?: "127.0.0.1", server.localPort, true) as SSLSocket
                    if (host != null) ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                    if (alpn.isNotEmpty()) ssl.sslParameters = ssl.sslParameters.apply { applicationProtocols = alpn.toTypedArray() }
                    ssl.soTimeout = 2000
                    runCatching { ssl.startHandshake() }
                    ssl.close()
                }
            }
            server.accept().use { s ->
                val buf = ByteArray(32 * 1024)
                var len = 0
                while (Sniffer.tlsRecordEnd(buf, len).let { it < 0 || it > len }) {
                    val n = s.getInputStream().read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
                return buf.copyOf(len)
            }
        }
    }

    @Test
    fun extractsAlpnFromRealClientHello() {
        val hello = clientHello("api.example.com", listOf("h2", "http/1.1"))
        assertEquals(listOf("h2", "http/1.1"), Sniffer.tlsAlpn(hello, hello.size))
        assertTrue(Sniffer.tlsOffersHttp(hello, hello.size))
        assertEquals("api.example.com", Sniffer.tlsSni(hello, hello.size))
    }

    @Test
    fun tlsThatOffersNoHttpIsNotHttp() {
        val none = clientHello("broker.example.com")
        assertEquals(emptyList<String>(), Sniffer.tlsAlpn(none, none.size))
        assertFalse(Sniffer.tlsOffersHttp(none, none.size))
        val mqtt = clientHello("broker.example.com", listOf("x-amzn-mqtt-ca"))
        assertEquals(listOf("x-amzn-mqtt-ca"), Sniffer.tlsAlpn(mqtt, mqtt.size))
        assertFalse(Sniffer.tlsOffersHttp(mqtt, mqtt.size))
    }

    @Test
    fun truncatedAlpnIsSafe() {
        val hello = clientHello("api.example.com", listOf("h2", "http/1.1"))
        for (cut in 0 until hello.size step 7) Sniffer.tlsAlpn(hello.copyOf(cut), cut)
    }

    @Test
    fun extractsSniFromRealClientHello() {
        val hello = clientHello("api.example.com")
        assertEquals(Sniffer.Kind.TLS, Sniffer.kind(hello, hello.size))
        assertEquals("api.example.com", Sniffer.tlsSni(hello, hello.size))
    }

    @Test
    fun noSniForIpLiteral() {
        val hello = clientHello(null)
        assertNull(Sniffer.tlsSni(hello, hello.size))
    }

    @Test
    fun truncatedClientHelloIsSafe() {
        val hello = clientHello("api.example.com")
        for (n in 0 until 60) assertNull(Sniffer.tlsSni(hello, n))
    }

    @Test
    fun httpHost() {
        val req = "GET /x HTTP/1.1\r\nUser-Agent: t\r\nHOST: example.org:8080\r\n\r\n".toByteArray()
        assertEquals(Sniffer.Kind.HTTP, Sniffer.kind(req, req.size))
        assertEquals("example.org", Sniffer.httpHost(req, req.size))
        assertEquals(true, Sniffer.httpHeadersDone(req, req.size))
    }

    @Test
    fun proxyRequests() {
        fun check(s: String) = Sniffer.isProxyRequest(s.toByteArray(), s.length)
        assertEquals(true, check("CONNECT example.com:443 HTTP/1.1\r\n"))
        assertEquals(true, check("GET http://example.com/ HTTP/1.1\r\n"))
        assertEquals(false, check("GET / HTTP/1.1\r\n"))
    }

    @Test
    fun otherProtocols() {
        val ssh = "SSH-2.0-OpenSSH_9.6\r\n".toByteArray()
        assertEquals(Sniffer.Kind.OTHER, Sniffer.kind(ssh, ssh.size))
        assertEquals(Sniffer.Kind.SILENT, Sniffer.kind(ByteArray(0), 0))
    }
}
