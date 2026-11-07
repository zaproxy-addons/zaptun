package dev.davidjosearaujo.zaptun.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** DnsRelay.handle against a fake resolver on loopback. */
class DnsRelayHandleTest {
    private val lo = InetAddress.getLoopbackAddress()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val replies = LinkedBlockingQueue<ByteArray>()
    private val logs = CopyOnWriteArrayList<String>()
    private val stats = Stats()
    private val servers = mutableListOf<DatagramSocket>()
    private val contacted = AtomicInteger()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        pool.shutdownNow()
    }

    /** A resolver that answers each query with [answer] (or says nothing if it returns null). */
    private fun resolver(answer: (ByteArray) -> ByteArray?): DatagramSocket {
        val s = DatagramSocket(0, lo).also(servers::add)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val p = DatagramPacket(ByteArray(4096), 4096)
                try {
                    s.receive(p)
                } catch (_: Exception) {
                    break
                }
                contacted.incrementAndGet()
                answer(p.data.copyOf(p.length))?.let { s.send(DatagramPacket(it, it.size, p.address, p.port)) }
            }
        }
        return s
    }

    private fun relay(server: DatagramSocket, timeoutMs: Int = 2000, upstream: () -> List<InetAddress> = { listOf(lo) }) =
        DnsRelay(upstream, { true }, pool, { replies.add(it) }, stats, { logs += it }, server.localPort, timeoutMs)

    private fun appQuery(type: Int) =
        Ip4.udpPacket(Ip4.addr("10.215.0.1"), 40000, Ip4.addr("10.215.0.53"), 53, TestPackets.dnsQuery(type))

    private fun reply(): ByteArray = replies.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("no reply within 3 s")

    private fun response(q: ByteArray, extra: Int = 0) = q.copyOf(q.size + extra).also { it[2] = (it[2].toInt() or 0x80).toByte() }

    @Test
    fun relaysTheAnswerFromTheResolver() {
        val relay = relay(resolver { response(it, extra = 16) })

        relay.handle(appQuery(1))
        val p = reply()

        assertEquals(Ip4.addr("10.215.0.53"), Ip4.src(p)) // from the VPN's DNS address
        assertEquals(53, Ip4.u16(p, 20))
        assertEquals(Ip4.addr("10.215.0.1"), Ip4.dst(p))
        assertEquals(40000, Ip4.u16(p, 22)) // back to the app's port
        assertEquals(TestPackets.dnsQuery(1).size + 16, TestPackets.udpPayload(p).size)
        assertTrue(Ip4.checksumsValid(p))
        assertEquals(1, stats.dnsQueries.get())
        assertEquals(0, stats.dnsFailed.get())
        assertTrue(logs.toString(), logs.any { it.startsWith("DNS A example.com → answered") })
    }

    @Test
    fun answersIpv6QueriesWithoutAskingTheResolver() {
        val relay = relay(resolver { response(it) })

        relay.handle(appQuery(DnsRelay.TYPE_AAAA))
        val payload = TestPackets.udpPayload(reply())

        assertEquals(0, Ip4.u16(payload, 6)) // no records: the app sticks to IPv4
        assertEquals(0x80, Ip4.u8(payload, 3)) // NOERROR, recursion available
        assertEquals(0, contacted.get())
        assertTrue(logs.toString(), logs.any { it.contains("AAAA example.com") && it.contains("IPv6 isn't carried") })
    }

    @Test
    fun anAnswerTooBigForOnePacketIsTruncated() {
        val relay = relay(resolver { response(it, extra = 3000) })

        relay.handle(appQuery(1))
        val payload = TestPackets.udpPayload(reply())

        assertEquals(TestPackets.dnsQuery(1).size, payload.size)
        assertEquals(0x02, Ip4.u8(payload, 2) and 0x02) // TC
        assertEquals(1, stats.dnsTruncated.get())
        assertTrue(logs.toString(), logs.any { it.contains("retry over TCP") })
    }

    @Test
    fun noAnswerIsCountedAndLoggedAndNothingIsSentBack() {
        val relay = relay(resolver { null }, timeoutMs = 300)

        relay.handle(appQuery(1))

        val deadline = System.currentTimeMillis() + 3000
        while (stats.dnsFailed.get() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, stats.dnsFailed.get())
        assertNull(replies.poll(100, TimeUnit.MILLISECONDS))
        assertTrue(logs.toString(), logs.any { it.startsWith("DNS A example.com → no answer after") })
    }

    @Test
    fun aQueryForAServerTheAppChoseGoesToThatServerOnly() {
        val chosen = resolver { response(it, extra = 4) }
        val device = resolver { response(it, extra = 99) } // the device's resolver must not be used
        val relay = DnsRelay({ listOf(lo) }, { true }, pool, { replies.add(it) }, stats, { logs += it }, chosen.localPort, 2000)

        relay.handle(appQuery(1), forwardTo = lo)

        assertEquals(TestPackets.dnsQuery(1).size + 4, TestPackets.udpPayload(reply()).size)
        assertEquals(1, contacted.get())
        assertTrue(device.localPort != chosen.localPort)
    }

    @Test
    fun followsTheDeviceFromOneNetworkToAnother() {
        val server = resolver { response(it) }
        val current = java.util.concurrent.atomic.AtomicReference(listOf(InetAddress.getByName("192.0.2.1"))) // unreachable
        val relay = relay(server, timeoutMs = 300) { current.get() }

        relay.handle(appQuery(1)) // the first network's resolver is gone: no answer
        val deadline = System.currentTimeMillis() + 3000
        while (stats.dnsFailed.get() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, stats.dnsFailed.get())

        current.set(listOf(lo)) // the device moved to a network whose resolver works
        relay.handle(appQuery(1))
        assertNotNull(reply())
    }

    @Test
    fun readsTheQueriedNameAndType() {
        assertEquals("example.com", DnsRelay.queryName(TestPackets.dnsQuery(1)))
        assertEquals("A", DnsRelay.typeName(1))
        assertEquals("HTTPS", DnsRelay.typeName(65))
        assertEquals("type 99", DnsRelay.typeName(99))
        assertEquals("?", DnsRelay.queryName(ByteArray(12)))
    }
}
