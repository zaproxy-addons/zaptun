package dev.davidjosearaujo.zaptun

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The DNS servers of the real networks under the VPN, kept up to date as networks come and go
 * (Wi-Fi to mobile, say). The relay asks for them on every query, so a switch doesn't leave it
 * talking to a resolver that is no longer reachable.
 */
class UnderlyingDns(private val fallback: InetAddress, private val exclude: Set<String> = emptySet()) {
    private val byNetwork = LinkedHashMap<Any, List<InetAddress>>()

    @Synchronized
    fun update(network: Any, servers: List<InetAddress>) {
        byNetwork.remove(network) // moves a network that changed to the end: the newest is tried last
        byNetwork[network] = servers.filter(::usable)
    }

    @Synchronized
    fun lost(network: Any) {
        byNetwork.remove(network)
    }

    /** IPv4 servers of the networks we know about (the most recently changed first), or the fallback. */
    @Synchronized
    fun servers(): List<InetAddress> =
        byNetwork.values.reversed().flatten().distinct().ifEmpty { listOf(fallback) }

    private fun usable(a: InetAddress) = a is Inet4Address && !a.isLoopbackAddress && a.hostAddress !in exclude
}
