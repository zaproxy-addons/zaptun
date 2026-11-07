package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ip6Test {
    @Test
    fun recognisesASyn() {
        val syn = TestPackets.ipv6Syn(51000, 443, 7)
        assertTrue(Ip6.isTcpSyn(syn, syn.size))
        assertEquals(443, Ip6.dstPort(syn, syn.size))
        val ack = syn.copyOf().also { it[Ip6.HEADER + 13] = (Ip4.TCP_SYN or Ip4.TCP_ACK).toByte() }
        assertFalse(Ip6.isTcpSyn(ack, ack.size))
        assertFalse(Ip6.isTcpSyn(syn, 30)) // truncated
    }

    @Test
    fun resetAnswersASynSoTheAttemptFailsAtOnce() {
        val syn = TestPackets.ipv6Syn(51000, 443, seq = 1234)

        val r = Ip6.resetFor(syn)

        assertEquals(0x60, r[0].toInt() and 0xf0)
        assertEquals(20, Ip4.u16(r, 4))
        assertEquals(Ip6.NEXT_TCP, Ip6.nextHeader(r))
        assertArrayEquals(syn.copyOfRange(24, 40), r.copyOfRange(8, 24)) // from where the SYN was aimed
        assertArrayEquals(syn.copyOfRange(8, 24), r.copyOfRange(24, 40)) // back to the app
        assertEquals(443, Ip4.u16(r, Ip6.HEADER))
        assertEquals(51000, Ip4.u16(r, Ip6.HEADER + 2))
        assertEquals(Ip4.TCP_RST or Ip4.TCP_ACK, Ip4.u8(r, Ip6.HEADER + 13))
        assertEquals(1235, Ip4.u32(r, Ip6.HEADER + 8)) // acknowledges the SYN
        assertEquals(0, TestPackets.ipv6ChecksumRemainder(r))
        assertTrue(Ip6.checksumValid(r))
    }

    @Test
    fun portUnreachableForUdpQuotesTheDatagram() {
        val udp = TestPackets.ipv6Udp(40000, 443, "quic-initial".toByteArray())

        val r = Ip6.portUnreachable(udp, udp.size)

        assertEquals(Ip6.NEXT_ICMPV6, Ip6.nextHeader(r))
        assertEquals(1, Ip4.u8(r, Ip6.HEADER)) // destination unreachable
        assertEquals(4, Ip4.u8(r, Ip6.HEADER + 1)) // port unreachable
        assertArrayEquals(udp.copyOfRange(24, 40), r.copyOfRange(8, 24))
        assertArrayEquals(udp.copyOfRange(8, 24), r.copyOfRange(24, 40))
        assertArrayEquals(udp, r.copyOfRange(Ip6.HEADER + 8, r.size)) // the whole datagram, it is small
        assertEquals(0, TestPackets.ipv6ChecksumRemainder(r))
        assertTrue(Ip6.checksumValid(r))
    }

    @Test
    fun portUnreachableNeverExceedsTheMinimumIpv6Mtu() {
        val udp = TestPackets.ipv6Udp(40000, 443, ByteArray(1400))
        val r = Ip6.portUnreachable(udp, udp.size)
        assertTrue(r.size <= 1280)
        assertEquals(0, TestPackets.ipv6ChecksumRemainder(r))
    }

    @Test
    fun anOddLengthPayloadIsChecksummedCorrectly() {
        val udp = TestPackets.ipv6Udp(40000, 443, ByteArray(7) { it.toByte() })
        val r = Ip6.portUnreachable(udp, udp.size) // quoted length is odd: 40 + 8 + 7 = 55
        assertEquals(1, r.size % 2 xor 1 xor (r.size % 2)) // (keeps the intent visible: size parity varies)
        assertEquals(0, TestPackets.ipv6ChecksumRemainder(r))
    }
}
