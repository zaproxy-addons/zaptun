package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRelayTest {
    private fun query(type: Int) = TestPackets.dnsQuery(type)

    @Test
    fun queryType() {
        assertEquals(1, DnsRelay.queryType(query(1)))
        assertEquals(DnsRelay.TYPE_AAAA, DnsRelay.queryType(query(28)))
    }

    @Test
    fun emptyAnswerEchoesQuestion() {
        val q = query(28)
        val r = DnsRelay.emptyAnswer(q)
        assertEquals(q.size, r.size)
        assertEquals(0xbeef, Ip4.u16(r, 0))
        assertEquals(0x81, Ip4.u8(r, 2))
        assertEquals(0x80, Ip4.u8(r, 3))
        assertEquals(0, Ip4.u16(r, 6))
        assertArrayEquals(q.copyOfRange(12, q.size), r.copyOfRange(12, r.size))
    }

    @Test
    fun smallAnswersAreLeftAlone() {
        val answer = query(1) + ByteArray(100)
        assertTrue(DnsRelay.fitForUdp(answer) === answer)
    }

    @Test
    fun anAnswerTooBigForOnePacketIsTruncatedSoTheResolverRetriesOverTcp() {
        val q = query(1)
        val big = q.copyOf(q.size + 2000).also {
            it[2] = 0x81.toByte() // QR + RD
            Ip4.put16(it, 6, 40) // 40 answers that would not fit
        }

        val t = DnsRelay.fitForUdp(big)

        assertEquals(q.size, t.size) // header and question only
        assertEquals(0xbeef, Ip4.u16(t, 0)) // same id, so the resolver matches it
        assertEquals(0x83, Ip4.u8(t, 2)) // QR + RD kept, TC set
        assertEquals(0, Ip4.u16(t, 6))
        assertEquals(1, Ip4.u16(t, 4)) // the question is echoed
        assertTrue(t.size + 28 <= 1500)
    }

    @Test
    fun theLimitIsExactlyWhatFitsInOnePacket() {
        val q = query(1)
        val fits = q.copyOf(DnsRelay.MAX_UDP_ANSWER)
        val tooBig = q.copyOf(DnsRelay.MAX_UDP_ANSWER + 1)
        assertTrue(DnsRelay.fitForUdp(fits) === fits)
        assertTrue(DnsRelay.fitForUdp(tooBig) !== tooBig)
        // And an unfragmented IPv4/UDP packet of that size is no bigger than the MTU.
        assertEquals(1500, Ip4.udpPacket(1, 53, 2, 1000, fits).size)
    }

    @Test
    fun udpPacketChecksums() {
        val p = Ip4.udpPacket(Ip4.addr("10.215.0.53"), 53, Ip4.addr("10.215.0.1"), 40123, query(1))
        assertEquals(true, Ip4.checksumsValid(p))
    }
}
