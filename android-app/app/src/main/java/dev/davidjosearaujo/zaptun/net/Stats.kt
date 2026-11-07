package dev.davidjosearaujo.zaptun.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counters for what the VPN refuses or can't place, shown on screen so a silent failure is visible. */
class Stats {
    val udpRefused = AtomicLong()
    val udpFlows = AtomicLong()
    val ipv6Refused = AtomicLong()
    val dnsQueries = AtomicLong()
    val dnsFailed = AtomicLong()
    val dnsTruncated = AtomicLong()
    private val udpPorts = ConcurrentHashMap<Int, AtomicLong>()
    private val udpFlowPorts = ConcurrentHashMap<Int, AtomicLong>()

    /** A UDP datagram that isn't DNS was refused (QUIC to port 443 is the usual one). */
    fun udpRefused(port: Int) {
        udpRefused.incrementAndGet()
        udpPorts.computeIfAbsent(port) { AtomicLong() }.incrementAndGet()
    }

    /** A UDP flow (a new source port and destination) was passed through to its destination. */
    fun udpFlow(port: Int) {
        udpFlows.incrementAndGet()
        udpFlowPorts.computeIfAbsent(port) { AtomicLong() }.incrementAndGet()
    }

    /** Refused UDP datagrams per destination port, most frequent first. */
    fun udpByPort(): List<Pair<Int, Long>> = ranked(udpPorts)

    /** Passed-through UDP flows per destination port, most frequent first. */
    fun udpFlowsByPort(): List<Pair<Int, Long>> = ranked(udpFlowPorts)

    private fun ranked(m: Map<Int, AtomicLong>) =
        m.map { it.key to it.value.get() }.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })

    fun reset() {
        udpRefused.set(0)
        udpFlows.set(0)
        ipv6Refused.set(0)
        dnsQueries.set(0)
        dnsFailed.set(0)
        dnsTruncated.set(0)
        udpPorts.clear()
        udpFlowPorts.clear()
    }
}
