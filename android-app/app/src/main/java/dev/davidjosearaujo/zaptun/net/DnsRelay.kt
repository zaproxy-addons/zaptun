package dev.davidjosearaujo.zaptun.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

/**
 * Answers DNS queries sent over UDP by forwarding them to the real resolvers over a protected socket.
 * AAAA queries get an empty answer so apps stick to IPv4, which is all [TcpNat] handles.
 *
 * [upstream] is asked on every query, so it can follow the device from one network to another.
 * Failures, truncations and each query are counted in [stats] and reported to [log].
 */
class DnsRelay(
    private val upstream: () -> List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val executor: ExecutorService,
    private val reply: (ByteArray) -> Unit,
    private val stats: Stats = Stats(),
    private val log: (String) -> Unit = {},
    private val upstreamPort: Int = 53,
    private val timeoutMs: Int = TIMEOUT_MS,
) {
    /**
     * Handles one IPv4/UDP packet addressed to port 53. A query the app sent to a DNS server of its
     * own choosing (not the VPN's) goes to that server, [forwardTo], instead of the device's.
     */
    fun handle(packet: ByteArray, forwardTo: InetAddress? = null) {
        val l4 = Ip4.headerLen(packet)
        val src = Ip4.src(packet)
        val dst = Ip4.dst(packet)
        val srcPort = Ip4.u16(packet, l4)
        val dstPort = Ip4.u16(packet, l4 + 2)
        val query = packet.copyOfRange(l4 + 8, minOf(packet.size, l4 + Ip4.u16(packet, l4 + 4)))
        if (query.size < 12) return

        stats.dnsQueries.incrementAndGet()
        executor.execute {
            val started = System.nanoTime()
            val type = queryType(query)
            val what = "DNS ${typeName(type)} ${queryName(query)}"
            val answer = if (type == TYPE_AAAA) emptyAnswer(query) else resolve(query, forwardTo)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (answer == null) {
                stats.dnsFailed.incrementAndGet()
                log("$what → no answer after $ms ms")
                return@execute
            }
            val fitted = fitForUdp(answer)
            if (fitted !== answer) {
                stats.dnsTruncated.incrementAndGet()
                log("$what → answer of ${answer.size} bytes is too big for one packet, asking the app to retry over TCP")
            } else {
                log("$what → answered in $ms ms${if (type == TYPE_AAAA) " (empty: IPv6 isn't carried)" else ""}")
            }
            reply(Ip4.udpPacket(dst, dstPort, src, srcPort, fitted))
        }
    }

    private fun resolve(query: ByteArray, forwardTo: InetAddress?): ByteArray? {
        for (server in if (forwardTo != null) listOf(forwardTo) else upstream()) {
            try {
                DatagramSocket().use { s ->
                    if (!protect(s)) return null
                    s.soTimeout = timeoutMs
                    s.send(DatagramPacket(query, query.size, InetSocketAddress(server, upstreamPort)))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    s.receive(resp)
                    return buf.copyOf(resp.length)
                }
            } catch (_: Exception) {
                // try the next server
            }
        }
        return null
    }

    companion object {
        const val TYPE_AAAA = 28

        /** The most a DNS answer can hold and still fit one tun packet (MTU 1500, minus IPv4 and UDP headers). */
        const val MAX_UDP_ANSWER = 1500 - 20 - 8

        /**
         * An answer too big for one packet can't be written to the tun, so it would be lost and the app
         * would wait for a timeout. Send it truncated instead: the resolver then asks again over TCP,
         * which the VPN serves.
         */
        fun fitForUdp(answer: ByteArray): ByteArray = if (answer.size <= MAX_UDP_ANSWER) answer else truncated(answer)

        /** The header and question only, with the TC (truncated) flag set and no records. */
        fun truncated(answer: ByteArray): ByteArray {
            val r = answer.copyOf(questionEnd(answer))
            r[2] = (Ip4.u8(r, 2) or 0x02).toByte() // TC
            Ip4.put16(r, 6, 0)
            Ip4.put16(r, 8, 0)
            Ip4.put16(r, 10, 0)
            return r
        }

        /** Offset just past the first question (name, QTYPE, QCLASS), or the end if it's cut short. */
        private fun questionEnd(q: ByteArray): Int {
            var end = 12
            while (end < q.size && Ip4.u8(q, end) != 0) end += 1 + Ip4.u8(q, end)
            return minOf(q.size, end + 5) // root label + QTYPE + QCLASS
        }
        private const val TIMEOUT_MS = 4000

        /** The queried name, e.g. `example.com`, or `?` if it can't be read. */
        fun queryName(q: ByteArray): String {
            val labels = mutableListOf<String>()
            var i = 12
            while (i < q.size) {
                val l = Ip4.u8(q, i)
                if (l == 0) return if (labels.isEmpty()) "." else labels.joinToString(".")
                if (l and 0xc0 != 0 || i + 1 + l > q.size) return "?"
                labels += String(q, i + 1, l, Charsets.US_ASCII)
                i += 1 + l
            }
            return "?"
        }

        fun typeName(type: Int) = when (type) {
            1 -> "A"
            28 -> "AAAA"
            5 -> "CNAME"
            15 -> "MX"
            16 -> "TXT"
            33 -> "SRV"
            64 -> "SVCB"
            65 -> "HTTPS"
            -1 -> "?"
            else -> "type $type"
        }

        /** QTYPE of the first question, or -1. */
        fun queryType(q: ByteArray): Int {
            if (Ip4.u16(q, 4) == 0) return -1
            var i = 12
            while (i < q.size) {
                val l = Ip4.u8(q, i)
                if (l == 0) break
                if (l and 0xc0 != 0) return -1
                i += 1 + l
            }
            return if (i + 3 <= q.size) Ip4.u16(q, i + 1) else -1
        }

        /** NOERROR response with the question echoed and no records. */
        fun emptyAnswer(q: ByteArray): ByteArray {
            val r = q.copyOf(questionEnd(q))
            val rd = Ip4.u8(q, 2) and 0x01
            r[2] = (0x80 or rd).toByte() // QR, opcode 0, RD copied
            r[3] = 0x80.toByte() // RA, RCODE 0
            Ip4.put16(r, 4, 1)
            Ip4.put16(r, 6, 0)
            Ip4.put16(r, 8, 0)
            Ip4.put16(r, 10, 0)
            return r
        }
    }
}
