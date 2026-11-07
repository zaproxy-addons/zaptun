package dev.davidjosearaujo.zaptun.net

/** Packets for tests. */
object TestPackets {
    /** A DNS query for example.com of the given QTYPE, with id 0xbeef and the RD flag. */
    fun dnsQuery(type: Int): ByteArray {
        val name = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        val q = ByteArray(12 + name.size + 4)
        Ip4.put16(q, 0, 0xbeef)
        q[2] = 0x01 // RD
        Ip4.put16(q, 4, 1)
        System.arraycopy(name, 0, q, 12, name.size)
        Ip4.put16(q, 12 + name.size, type)
        Ip4.put16(q, 14 + name.size, 1)
        return q
    }

    /** An IPv4/UDP packet from the app (10.215.0.1) to [dst]:[dstPort]. */
    fun udpFromApp(appPort: Int, dst: String, dstPort: Int, payload: ByteArray): ByteArray =
        Ip4.udpPacket(Ip4.addr("10.215.0.1"), appPort, Ip4.addr(dst), dstPort, payload)

    /** The payload of an IPv4/UDP packet. */
    fun udpPayload(p: ByteArray): ByteArray {
        val l4 = Ip4.headerLen(p)
        return p.copyOfRange(l4 + 8, l4 + Ip4.u16(p, l4 + 4))
    }

    /** A TCP SYN over IPv6 from ::1-ish addresses, no extension headers. */
    fun ipv6Syn(srcPort: Int, dstPort: Int, seq: Int): ByteArray {
        val p = ByteArray(Ip6.HEADER + 20)
        p[0] = 0x60
        Ip4.put16(p, 4, 20)
        p[6] = Ip6.NEXT_TCP.toByte()
        p[7] = 64
        p[8] = 0xfd.toByte(); p[9] = 0x00; p[10] = 0x02; p[11] = 0x15; p[23] = 1 // fd00:215::1
        p[24] = 0x26; p[25] = 0x06; p[26] = 0x47; p[27] = 0x00; p[39] = 0x11 // 2606:4700::11
        Ip4.put16(p, Ip6.HEADER, srcPort)
        Ip4.put16(p, Ip6.HEADER + 2, dstPort)
        Ip4.put32(p, Ip6.HEADER + 4, seq)
        p[Ip6.HEADER + 12] = 0x50
        p[Ip6.HEADER + 13] = Ip4.TCP_SYN.toByte()
        return p
    }

    /** An IPv6/UDP packet with [payload], addresses as in [ipv6Syn]. */
    fun ipv6Udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val p = ByteArray(Ip6.HEADER + 8 + payload.size)
        p[0] = 0x60
        Ip4.put16(p, 4, 8 + payload.size)
        p[6] = Ip6.NEXT_UDP.toByte()
        p[7] = 64
        p[8] = 0xfd.toByte(); p[9] = 0x00; p[10] = 0x02; p[11] = 0x15; p[23] = 1
        p[24] = 0x26; p[25] = 0x06; p[26] = 0x47; p[27] = 0x00; p[39] = 0x11
        Ip4.put16(p, Ip6.HEADER, srcPort)
        Ip4.put16(p, Ip6.HEADER + 2, dstPort)
        Ip4.put16(p, Ip6.HEADER + 4, 8 + payload.size)
        System.arraycopy(payload, 0, p, Ip6.HEADER + 8, payload.size)
        return p
    }

    /**
     * The upper-layer checksum of an IPv6 packet, worked out the long way from RFC 8200 section 8.1
     * (pseudo-header, then the data), so tests don't rely on the code that produced the checksum.
     * Returns 0 when the packet's own checksum is right.
     */
    fun ipv6ChecksumRemainder(p: ByteArray): Int {
        val payloadLen = Ip4.u16(p, 4)
        val pseudo = java.io.ByteArrayOutputStream()
        pseudo.write(p, 8, 32)
        pseudo.write(byteArrayOf(0, 0, (payloadLen shr 8).toByte(), payloadLen.toByte()))
        pseudo.write(byteArrayOf(0, 0, 0, p[6]))
        pseudo.write(p, Ip6.HEADER, payloadLen)
        val data = pseudo.toByteArray().let { if (it.size % 2 == 1) it + 0 else it }
        var sum = 0L
        for (i in data.indices step 2) sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.toInt().inv()) and 0xffff
    }
}
