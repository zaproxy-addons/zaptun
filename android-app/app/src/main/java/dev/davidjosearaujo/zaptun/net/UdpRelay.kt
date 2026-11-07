package dev.davidjosearaujo.zaptun.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Passes UDP that isn't DNS or QUIC straight through to its destination over protected sockets, so
 * calls, NTP, games and the like keep working. ZAP can't proxy UDP, so this traffic is not captured.
 *
 * Each flow (app port, destination) gets its own socket, which a thread reads replies from until the
 * flow has been quiet for [idleMs]. The socket isn't connected, so replies from other addresses (as
 * in STUN or peer-to-peer) get through, shown to the app as coming from where they really came from.
 */
class UdpRelay(
    private val protect: (DatagramSocket) -> Boolean,
    private val executor: ExecutorService,
    private val reply: (ByteArray) -> Unit,
    private val stats: Stats = Stats(),
    private val idleMs: Long = 60_000,
) {
    private class Flow(val socket: DatagramSocket, val appIp: Int, val appPort: Int) {
        @Volatile var lastUsed = System.currentTimeMillis()
    }

    private val flows = ConcurrentHashMap<Long, Flow>()

    @Volatile private var closed = false

    val size: Int get() = flows.size

    /** Sends the payload of one IPv4/UDP packet ([len] bytes) on to its destination. */
    fun handle(packet: ByteArray, len: Int) {
        if (closed) return
        val l4 = Ip4.headerLen(packet)
        val appPort = Ip4.u16(packet, l4)
        val dstPort = Ip4.u16(packet, l4 + 2)
        val dstIp = Ip4.dst(packet)
        val payloadLen = minOf(len, l4 + Ip4.u16(packet, l4 + 4)) - l4 - 8
        if (payloadLen < 0) return

        val key = (appPort.toLong() shl 48) or (dstPort.toLong() shl 32) or (dstIp.toLong() and 0xffffffffL)
        val flow = flows[key] ?: open(key, Ip4.src(packet), appPort, dstIp, dstPort) ?: return
        flow.lastUsed = System.currentTimeMillis()
        try {
            flow.socket.send(DatagramPacket(packet, l4 + 8, payloadLen, InetSocketAddress(Ip4.inet(dstIp), dstPort)))
        } catch (e: Exception) {
            close(key, flow) // e.g. unreachable network: the next datagram starts a fresh flow
        }
    }

    private fun open(key: Long, appIp: Int, appPort: Int, dstIp: Int, dstPort: Int): Flow? {
        val socket = try {
            DatagramSocket().also { if (!protect(it)) { it.close(); return null } }
        } catch (e: Exception) {
            return null
        }
        socket.soTimeout = POLL_MS
        val flow = Flow(socket, appIp, appPort)
        val existing = flows.putIfAbsent(key, flow)
        if (existing != null) {
            socket.close()
            return existing
        }
        stats.udpFlow(dstPort)
        executor.execute { receiveLoop(key, flow) }
        return flow
    }

    private fun receiveLoop(key: Long, flow: Flow) {
        val buf = ByteArray(MAX_DATAGRAM)
        val received = DatagramPacket(buf, buf.size)
        while (!closed && flows[key] === flow) {
            try {
                flow.socket.receive(received)
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() - flow.lastUsed > idleMs) break
                continue
            } catch (_: Exception) {
                break
            }
            flow.lastUsed = System.currentTimeMillis()
            // A datagram too big for one tun packet can't be delivered (no IP fragmentation here).
            if (received.length > DnsRelay.MAX_UDP_ANSWER) continue
            val from = received.address.address
            if (from.size != 4) continue
            reply(Ip4.udpPacket(Ip4.addr(received.address), received.port, flow.appIp, flow.appPort, buf, received.length))
        }
        close(key, flow)
    }

    private fun close(key: Long, flow: Flow) {
        flows.remove(key, flow)
        flow.socket.close()
    }

    fun close() {
        closed = true
        flows.values.forEach { it.socket.close() }
        flows.clear()
    }

    companion object {
        private const val POLL_MS = 1000
        private const val MAX_DATAGRAM = 65535
    }
}
