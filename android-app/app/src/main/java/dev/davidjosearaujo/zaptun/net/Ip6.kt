package dev.davidjosearaujo.zaptun.net

/**
 * Just enough IPv6 to turn connections down quickly. The VPN doesn't carry IPv6 (and hides AAAA
 * records), but an app that does try it, with an address literal or a cached record, would hang until
 * a timeout if its packets were silently dropped. Answering with a RST (TCP) or ICMPv6 port
 * unreachable (UDP) makes it fall back to IPv4 at once. Packets with extension headers are ignored.
 */
object Ip6 {
    const val HEADER = 40
    const val NEXT_TCP = 6
    const val NEXT_UDP = 17
    const val NEXT_ICMPV6 = 58
    private const val MIN_MTU = 1280

    fun nextHeader(b: ByteArray) = Ip4.u8(b, 6)

    /** The destination port of a TCP or UDP packet with no extension headers, or -1. */
    fun dstPort(b: ByteArray, len: Int): Int =
        if (len >= HEADER + 8 && (nextHeader(b) == NEXT_TCP || nextHeader(b) == NEXT_UDP)) Ip4.u16(b, HEADER + 2) else -1

    fun isTcpSyn(b: ByteArray, len: Int): Boolean {
        if (len < HEADER + 20 || nextHeader(b) != NEXT_TCP) return false
        val flags = Ip4.u8(b, HEADER + 13)
        return flags and Ip4.TCP_SYN != 0 && flags and Ip4.TCP_ACK == 0
    }

    /** A RST|ACK answering the TCP SYN [syn]. */
    fun resetFor(syn: ByteArray): ByteArray {
        val r = ByteArray(HEADER + 20)
        header(r, syn, NEXT_TCP, 20)
        Ip4.put16(r, HEADER, Ip4.u16(syn, HEADER + 2)) // ports swapped
        Ip4.put16(r, HEADER + 2, Ip4.u16(syn, HEADER))
        Ip4.put32(r, HEADER + 8, Ip4.u32(syn, HEADER + 4) + 1) // acknowledges their SYN
        r[HEADER + 12] = 0x50
        r[HEADER + 13] = (Ip4.TCP_RST or Ip4.TCP_ACK).toByte()
        Ip4.put16(r, HEADER + 16, checksum(r, NEXT_TCP, HEADER + 16))
        return r
    }

    /** An ICMPv6 "port unreachable" for the UDP datagram [original] ([len] bytes). */
    fun portUnreachable(original: ByteArray, len: Int): ByteArray {
        val quoted = minOf(len, MIN_MTU - HEADER - 8)
        val r = ByteArray(HEADER + 8 + quoted)
        header(r, original, NEXT_ICMPV6, 8 + quoted)
        r[HEADER] = 1 // destination unreachable
        r[HEADER + 1] = 4 // port unreachable
        System.arraycopy(original, 0, r, HEADER + 8, quoted)
        Ip4.put16(r, HEADER + 2, checksum(r, NEXT_ICMPV6, HEADER + 2))
        return r
    }

    /** True if the upper-layer checksum (TCP, UDP or ICMPv6) is valid. Used by tests. */
    fun checksumValid(b: ByteArray): Boolean = upperSum(b, nextHeader(b)) == 0xffff

    /** The IPv6 header of a reply to [to]: from where it was aimed, back to where it came from. */
    private fun header(r: ByteArray, to: ByteArray, next: Int, payload: Int) {
        r[0] = 0x60
        Ip4.put16(r, 4, payload)
        r[6] = next.toByte()
        r[7] = 64
        System.arraycopy(to, 24, r, 8, 16) // source = their destination
        System.arraycopy(to, 8, r, 24, 16) // destination = their source
    }

    /** The checksum to store, given that the checksum field at [field] is still zero. */
    private fun checksum(b: ByteArray, next: Int, field: Int): Int {
        check(Ip4.u16(b, field) == 0)
        return upperSum(b, next).inv() and 0xffff
    }

    /** One's-complement sum (folded to 16 bits) of the pseudo-header and the upper-layer data. */
    private fun upperSum(b: ByteArray, next: Int): Int {
        val len = Ip4.u16(b, 4)
        var s = 0L
        var i = 8
        while (i < 40) {
            s += Ip4.u16(b, i)
            i += 2
        }
        s += len + next
        i = HEADER
        while (i + 1 < HEADER + len) {
            s += Ip4.u16(b, i)
            i += 2
        }
        if (len % 2 == 1) s += Ip4.u8(b, HEADER + len - 1) shl 8
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return s.toInt()
    }
}
