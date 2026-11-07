package dev.davidjosearaujo.zaptun

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class UnderlyingDnsTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private val fallback = ip("8.8.8.8")

    private fun dns() = UnderlyingDns(fallback, setOf("10.215.0.53"))

    @Test
    fun fallsBackWhenNothingIsKnown() {
        assertEquals(listOf(fallback), dns().servers())
    }

    @Test
    fun usesTheServersOfTheNetwork() {
        val d = dns()
        d.update("wifi", listOf(ip("192.168.1.1"), ip("192.168.1.2")))
        assertEquals(listOf(ip("192.168.1.1"), ip("192.168.1.2")), d.servers())
    }

    @Test
    fun ignoresIpv6LoopbackAndTheVpnsOwnAddress() {
        val d = dns()
        d.update("wifi", listOf(ip("2001:4860:4860::8888"), ip("127.0.0.1"), ip("10.215.0.53"), ip("1.1.1.1")))
        assertEquals(listOf(ip("1.1.1.1")), d.servers())
    }

    @Test
    fun aNetworkWithOnlyUnusableServersFallsBack() {
        val d = dns()
        d.update("wifi", listOf(ip("2001:4860:4860::8888")))
        assertEquals(listOf(fallback), d.servers())
    }

    @Test
    fun theNetworkThatChangedMostRecentlyComesFirst() {
        val d = dns()
        d.update("wifi", listOf(ip("192.168.1.1")))
        d.update("mobile", listOf(ip("100.64.0.1")))
        assertEquals(listOf(ip("100.64.0.1"), ip("192.168.1.1")), d.servers())

        d.update("wifi", listOf(ip("192.168.1.9"))) // wifi changed again
        assertEquals(listOf(ip("192.168.1.9"), ip("100.64.0.1")), d.servers())
    }

    @Test
    fun aLostNetworkIsForgotten() {
        val d = dns()
        d.update("wifi", listOf(ip("192.168.1.1")))
        d.update("mobile", listOf(ip("100.64.0.1")))
        d.lost("mobile")
        assertEquals(listOf(ip("192.168.1.1")), d.servers())
        d.lost("wifi")
        assertEquals(listOf(fallback), d.servers())
    }

    @Test
    fun sharedServersAreListedOnce() {
        val d = dns()
        d.update("wifi", listOf(ip("1.1.1.1")))
        d.update("mobile", listOf(ip("1.1.1.1"), ip("100.64.0.1")))
        assertEquals(listOf(ip("1.1.1.1"), ip("100.64.0.1")), d.servers())
    }
}
