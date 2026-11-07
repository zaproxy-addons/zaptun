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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UdpRelayTest {
    private val lo = InetAddress.getLoopbackAddress()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val replies = LinkedBlockingQueue<ByteArray>()
    private val stats = Stats()
    private val servers = mutableListOf<DatagramSocket>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        pool.shutdownNow()
    }

    /** A UDP server that answers every datagram with "re:" followed by what it received. */
    private fun echoServer(): DatagramSocket {
        val s = DatagramSocket(0, lo).also(servers::add)
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val p = DatagramPacket(ByteArray(2048), 2048)
                try {
                    s.receive(p)
                } catch (_: Exception) {
                    break
                }
                val out = "re:".toByteArray() + p.data.copyOf(p.length)
                s.send(DatagramPacket(out, out.size, p.address, p.port))
            }
        }
        return s
    }

    private fun relay(idleMs: Long = 60_000) = UdpRelay({ true }, pool, { replies.add(it) }, stats, idleMs)

    private fun send(relay: UdpRelay, appPort: Int, server: DatagramSocket, text: String) {
        val packet = TestPackets.udpFromApp(appPort, "127.0.0.1", server.localPort, text.toByteArray())
        relay.handle(packet, packet.size)
    }

    private fun nextReply(): ByteArray = replies.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("no reply within 3 s")

    @Test
    fun passesADatagramThroughAndBringsTheReplyBack() {
        val server = echoServer()
        val relay = relay()

        send(relay, 40001, server, "hello")
        val p = nextReply()

        assertEquals("re:hello", String(TestPackets.udpPayload(p)))
        assertEquals(Ip4.addr("127.0.0.1"), Ip4.src(p)) // shown as coming from the destination
        assertEquals(server.localPort, Ip4.u16(p, 20))
        assertEquals(Ip4.addr("10.215.0.1"), Ip4.dst(p))
        assertEquals(40001, Ip4.u16(p, 22))
        assertTrue(Ip4.checksumsValid(p))
        assertEquals(1, stats.udpFlows.get())
        assertEquals(listOf(server.localPort to 1L), stats.udpFlowsByPort())
    }

    @Test
    fun oneFlowPerAppPortAndDestination() {
        val server = echoServer()
        val relay = relay()

        send(relay, 40001, server, "a")
        send(relay, 40001, server, "b") // same flow
        send(relay, 40002, server, "c") // another app port
        repeat(3) { nextReply() }

        assertEquals(2, relay.size)
        assertEquals(2, stats.udpFlows.get())
    }

    @Test
    fun repliesGoBackToTheRightAppPort() {
        val server = echoServer()
        val relay = relay()

        send(relay, 40001, server, "one")
        send(relay, 40002, server, "two")
        val byPort = (1..2).map { nextReply() }.associate { Ip4.u16(it, 22) to String(TestPackets.udpPayload(it)) }

        assertEquals(mapOf(40001 to "re:one", 40002 to "re:two"), byPort)
    }

    @Test
    fun aQuietFlowIsClosedAfterTheIdleTime() {
        val server = echoServer()
        val relay = relay(idleMs = 100)

        send(relay, 40001, server, "hello")
        nextReply()
        assertEquals(1, relay.size)

        val deadline = System.currentTimeMillis() + 5000
        while (relay.size > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, relay.size)
    }

    @Test
    fun nothingGoesThroughOnceClosed() {
        val server = echoServer()
        val relay = relay()
        relay.close()

        send(relay, 40001, server, "hello")

        assertNull(replies.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(0, relay.size)
    }
}
