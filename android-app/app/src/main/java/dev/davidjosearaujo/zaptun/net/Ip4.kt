package dev.davidjosearaujo.zaptun.net

import java.net.Inet4Address
import java.net.InetAddress

/** Minimal IPv4 / TCP / UDP header helpers. Addresses are kept as big-endian Ints. */
object Ip4 {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_ACK = 0x10

    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)

    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    fun put32(b: ByteArray, i: Int, v: Int) {
        put16(b, i, v ushr 16)
        put16(b, i + 2, v)
    }

    fun version(b: ByteArray) = u8(b, 0) ushr 4
    fun headerLen(b: ByteArray) = (u8(b, 0) and 0x0f) * 4
    fun totalLen(b: ByteArray) = u16(b, 2)
    fun protocol(b: ByteArray) = u8(b, 9)
    fun src(b: ByteArray) = u32(b, 12)
    fun dst(b: ByteArray) = u32(b, 16)

    fun addr(s: String): Int = addr(InetAddress.getByName(s))
    fun addr(a: InetAddress): Int {
        require(a is Inet4Address) { "not IPv4: $a" }
        val b = a.address
        return u32(b, 0)
    }

    fun inet(v: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    fun str(v: Int) = "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"

    /** One's-complement sum of [len] bytes starting at [off], folded into [initial]. */
    private fun sum(b: ByteArray, off: Int, len: Int, initial: Long = 0): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += u16(b, i)
            i += 2
        }
        if (i < end) s += u8(b, i) shl 8
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return s.toInt().inv() and 0xffff
    }

    /** Recomputes the IPv4 header checksum and the TCP/UDP checksum in place. */
    fun fixChecksums(b: ByteArray) {
        val ihl = headerLen(b)
        put16(b, 10, 0)
        put16(b, 10, fold(sum(b, 0, ihl)))

        val l4Len = totalLen(b) - ihl
        val proto = protocol(b)
        val csumOff = when (proto) {
            PROTO_TCP -> ihl + 16
            PROTO_UDP -> ihl + 6
            else -> return
        }
        put16(b, csumOff, 0)
        var pseudo = sum(b, 12, 8)
        pseudo += proto
        pseudo += l4Len
        var c = fold(sum(b, ihl, l4Len, pseudo))
        if (proto == PROTO_UDP && c == 0) c = 0xffff
        put16(b, csumOff, c)
    }

    /** The internet checksum of [len] bytes at [off]: the one's-complement of their one's-complement sum. */
    fun internetChecksum(b: ByteArray, off: Int, len: Int): Int = fold(sum(b, off, len))

    /**
     * An ICMP "port unreachable" for the UDP datagram [original] ([len] bytes), as if the destination
     * had nothing listening. A refused datagram then fails at once with ECONNREFUSED, where a dropped
     * one makes the app wait for a timeout. This is how QUIC (UDP 443) is turned down so clients fall
     * back to TCP straight away.
     */
    fun portUnreachable(original: ByteArray, len: Int): ByteArray {
        val ihl = headerLen(original)
        val quoted = minOf(len, ihl + 8) // the IP header and the first 8 bytes of the datagram
        val total = 20 + 8 + quoted
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[8] = 64
        p[9] = PROTO_ICMP.toByte()
        put32(p, 12, dst(original))
        put32(p, 16, src(original))
        p[20] = 3 // destination unreachable
        p[21] = 3 // port unreachable
        System.arraycopy(original, 0, p, 28, quoted)
        put16(p, 22, internetChecksum(p, 20, 8 + quoted))
        put16(p, 10, 0)
        put16(p, 10, internetChecksum(p, 0, 20))
        return p
    }

    /** True if the IP header and L4 checksums are valid. Used by tests. */
    fun checksumsValid(b: ByteArray): Boolean {
        val ihl = headerLen(b)
        if (fold(sum(b, 0, ihl)) != 0) return false
        val l4Len = totalLen(b) - ihl
        val pseudo = sum(b, 12, 8) + protocol(b) + l4Len
        return fold(sum(b, ihl, l4Len, pseudo)) == 0
    }

    /** A bare TCP RST from [src]:[srcPort] to [dst]:[dstPort] with sequence number [seq], also acknowledging [ack] if given. */
    fun tcpReset(src: Int, srcPort: Int, dst: Int, dstPort: Int, seq: Int, ack: Int? = null): ByteArray {
        val r = ByteArray(40)
        r[0] = 0x45
        put16(r, 2, r.size)
        r[8] = 64
        r[9] = PROTO_TCP.toByte()
        put32(r, 12, src)
        put32(r, 16, dst)
        put16(r, 20, srcPort)
        put16(r, 22, dstPort)
        put32(r, 24, seq)
        if (ack != null) put32(r, 28, ack)
        r[32] = 0x50 // 20-byte header
        r[33] = (if (ack != null) TCP_RST or TCP_ACK else TCP_RST).toByte()
        fixChecksums(r)
        return r
    }

    /**
     * The reply to a segment [p] ([len] bytes) for a connection that doesn't exist (RFC 9293,
     * 3.10.7.1): a RST the sender accepts, so it gives up on the connection at once instead of
     * retransmitting. For a SYN we won't serve it is a RST|ACK that acknowledges the SYN, so the
     * attempt is refused at once instead of hanging until it times out.
     */
    fun resetForSegment(p: ByteArray, len: Int): ByteArray {
        val ihl = headerLen(p)
        val flags = u8(p, ihl + 13)
        val from = dst(p)
        val fromPort = u16(p, ihl + 2)
        return if (flags and TCP_ACK != 0) {
            tcpReset(from, fromPort, src(p), u16(p, ihl), seq = u32(p, ihl + 8))
        } else {
            val data = len - ihl - (u8(p, ihl + 12) ushr 4) * 4
            val segLen = data + (if (flags and TCP_SYN != 0) 1 else 0) + (if (flags and TCP_FIN != 0) 1 else 0)
            tcpReset(from, fromPort, src(p), u16(p, ihl), seq = 0, ack = u32(p, ihl + 4) + segLen)
        }
    }

    /** Builds an IPv4/UDP packet. */
    fun udpPacket(src: Int, srcPort: Int, dst: Int, dstPort: Int, payload: ByteArray, payloadLen: Int = payload.size): ByteArray {
        val total = 20 + 8 + payloadLen
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64
        p[9] = PROTO_UDP.toByte()
        put32(p, 12, src)
        put32(p, 16, dst)
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payloadLen)
        System.arraycopy(payload, 0, p, 28, payloadLen)
        fixChecksums(p)
        return p
    }
}
