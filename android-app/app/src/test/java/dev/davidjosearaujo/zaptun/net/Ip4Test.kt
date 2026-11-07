package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ip4Test {
    private fun syn(src: String, srcPort: Int, dst: String, dstPort: Int, seq: Int): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45
        Ip4.put16(p, 2, 40)
        p[8] = 64
        p[9] = Ip4.PROTO_TCP.toByte()
        Ip4.put32(p, 12, Ip4.addr(src))
        Ip4.put32(p, 16, Ip4.addr(dst))
        Ip4.put16(p, 20, srcPort)
        Ip4.put16(p, 22, dstPort)
        Ip4.put32(p, 24, seq)
        p[32] = 0x50
        p[33] = Ip4.TCP_SYN.toByte()
        Ip4.fixChecksums(p)
        return p
    }

    @Test
    fun resetAnswersASynSoTheAttemptIsRefusedAtOnce() {
        val s = syn("10.215.0.1", 51000, "10.215.0.53", 853, seq = 1234)

        val r = Ip4.resetForSegment(s, s.size)

        assertEquals(Ip4.addr("10.215.0.53"), Ip4.src(r)) // from where the SYN was aimed
        assertEquals(Ip4.addr("10.215.0.1"), Ip4.dst(r))
        assertEquals(853, Ip4.u16(r, 20))
        assertEquals(51000, Ip4.u16(r, 22))
        assertEquals(Ip4.TCP_RST or Ip4.TCP_ACK, Ip4.u8(r, 33))
        assertEquals(1235, Ip4.u32(r, 28)) // acknowledges the SYN
        assertTrue(Ip4.checksumsValid(r))
    }

    @Test
    fun resetAcknowledgesASequenceNumberThatWrapsAround() {
        val s = syn("10.215.0.1", 51000, "10.215.0.53", 853, seq = -1) // 0xffffffff
        assertEquals(0, Ip4.u32(Ip4.resetForSegment(s, s.size), 28))
    }

    @Test
    fun portUnreachableAnswersAUdpDatagramAsIfNothingListened() {
        val udp = Ip4.udpPacket(Ip4.addr("10.215.0.1"), 40000, Ip4.addr("142.250.1.1"), 443, ByteArray(30) { it.toByte() })

        val icmp = Ip4.portUnreachable(udp, udp.size)

        assertEquals(Ip4.PROTO_ICMP, Ip4.protocol(icmp))
        assertEquals(Ip4.addr("142.250.1.1"), Ip4.src(icmp)) // from where the datagram was aimed
        assertEquals(Ip4.addr("10.215.0.1"), Ip4.dst(icmp))
        assertEquals(3, Ip4.u8(icmp, 20)) // destination unreachable
        assertEquals(3, Ip4.u8(icmp, 21)) // port unreachable
        // It quotes the original IP header and the first 8 bytes of the datagram, so the app can match it.
        assertEquals(20 + 8 + 20 + 8, icmp.size)
        assertEquals(udp.copyOfRange(0, 28).toList(), icmp.copyOfRange(28, 56).toList())
        assertEquals(0, Ip4.internetChecksum(icmp, 0, 20)) // IP header
        assertEquals(0, Ip4.internetChecksum(icmp, 20, icmp.size - 20)) // ICMP message
    }

    @Test
    fun portUnreachableForATinyDatagramQuotesWhatThereIs() {
        val udp = Ip4.udpPacket(Ip4.addr("10.215.0.1"), 40000, Ip4.addr("1.1.1.1"), 123, ByteArray(0))
        val icmp = Ip4.portUnreachable(udp, udp.size)
        assertEquals(28 + udp.size, icmp.size)
        assertEquals(0, Ip4.internetChecksum(icmp, 20, icmp.size - 20))
    }
}
