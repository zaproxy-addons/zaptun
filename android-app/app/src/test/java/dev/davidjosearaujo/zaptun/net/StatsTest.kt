package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {
    @Test
    fun ranksRefusedUdpPortsMostFrequentFirst() {
        val s = Stats()
        repeat(5) { s.udpRefused(443) }
        repeat(2) { s.udpRefused(3478) }
        s.udpRefused(123)
        s.udpRefused(80)

        assertEquals(9, s.udpRefused.get())
        assertEquals(listOf(443 to 5L, 3478 to 2L, 80 to 1L, 123 to 1L), s.udpByPort()) // ties by port
    }

    @Test
    fun countsPassedThroughFlowsPerPort() {
        val s = Stats()
        s.udpFlow(123)
        s.udpFlow(123)
        s.udpFlow(3478)
        assertEquals(3, s.udpFlows.get())
        assertEquals(listOf(123 to 2L, 3478 to 1L), s.udpFlowsByPort())
    }

    @Test
    fun resetClearsEverything() {
        val s = Stats()
        s.udpRefused(443)
        s.udpFlow(123)
        s.ipv6Refused.incrementAndGet()
        s.dnsQueries.incrementAndGet()
        s.dnsFailed.incrementAndGet()
        s.dnsTruncated.incrementAndGet()

        s.reset()

        assertEquals(0, s.udpRefused.get() + s.udpFlows.get() + s.ipv6Refused.get() + s.dnsQueries.get() + s.dnsFailed.get() + s.dnsTruncated.get())
        assertEquals(emptyList<Pair<Int, Long>>(), s.udpByPort())
        assertEquals(emptyList<Pair<Int, Long>>(), s.udpFlowsByPort())
    }
}
