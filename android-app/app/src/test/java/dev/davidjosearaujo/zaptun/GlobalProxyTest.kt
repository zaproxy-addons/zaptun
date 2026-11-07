package dev.davidjosearaujo.zaptun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalProxyTest {

    private class FakeSystem(var value: String?, var allowed: Boolean = true) : GlobalProxy.Backend {
        val writes = mutableListOf<String>()

        override fun read() = value

        override fun write(value: String): Boolean {
            if (!allowed) return false
            writes += value
            this.value = value
            return true
        }
    }

    private class FakeMemory(override var pending: String? = null) : GlobalProxy.Memory

    @Test
    fun clearsTheProxyAndRemembersIt() {
        val system = FakeSystem("10.0.2.2:9090")
        val memory = FakeMemory()
        val proxy = GlobalProxy(system, memory)

        assertEquals(GlobalProxy.Outcome.Cleared("10.0.2.2:9090"), proxy.clear())
        assertEquals(":0", system.value)
        assertEquals("10.0.2.2:9090", memory.pending)
        assertNull(proxy.current())
    }

    @Test
    fun restoresWhatItCleared() {
        val system = FakeSystem("10.0.2.2:9090")
        val proxy = GlobalProxy(system, FakeMemory())
        proxy.clear()

        proxy.restore()

        assertEquals("10.0.2.2:9090", system.value)
        assertFalse(proxy.hasPending())
    }

    @Test
    fun doesNothingWhenNoProxyIsSet() {
        for (unset in listOf(null, "", ":0", "null", "  ")) {
            val system = FakeSystem(unset)
            val memory = FakeMemory()
            val proxy = GlobalProxy(system, memory)

            assertEquals(GlobalProxy.Outcome.NotSet, proxy.clear())
            proxy.restore()

            assertTrue("no writes for $unset", system.writes.isEmpty())
            assertNull(memory.pending)
        }
    }

    @Test
    fun clearingAgainOnAReconnectKeepsTheOriginal() {
        val system = FakeSystem("10.0.2.2:9090")
        val memory = FakeMemory()
        val proxy = GlobalProxy(system, memory)
        proxy.clear()

        // The VPN restarts (an app was toggled): the proxy is already off, but what to restore must survive.
        assertEquals(GlobalProxy.Outcome.Cleared("10.0.2.2:9090"), proxy.clear())
        assertEquals("10.0.2.2:9090", memory.pending)
        proxy.restore()
        assertEquals("10.0.2.2:9090", system.value)
    }

    @Test
    fun reportsWhenItIsNotAllowedToChangeIt() {
        val system = FakeSystem("10.0.2.2:9090", allowed = false)
        val memory = FakeMemory()
        val proxy = GlobalProxy(system, memory)

        assertEquals(GlobalProxy.Outcome.Failed("10.0.2.2:9090"), proxy.clear())
        assertEquals("10.0.2.2:9090", system.value)
        assertNull(memory.pending) // nothing was changed, so nothing to put back
    }

    @Test
    fun leavesAProxyTheUserSetWhileConnected() {
        val system = FakeSystem("10.0.2.2:9090")
        val proxy = GlobalProxy(system, FakeMemory())
        proxy.clear()

        system.value = "192.168.1.5:8888" // changed by hand or by a script while zaptun was connected
        proxy.restore()

        assertEquals("192.168.1.5:8888", system.value)
        assertFalse(proxy.hasPending())
    }

    @Test
    fun picksUpANewerProxyOnReconnect() {
        val system = FakeSystem("10.0.2.2:9090")
        val memory = FakeMemory()
        val proxy = GlobalProxy(system, memory)
        proxy.clear()

        system.value = "192.168.1.5:8888"
        assertEquals(GlobalProxy.Outcome.Cleared("192.168.1.5:8888"), proxy.clear())
        proxy.restore()
        assertEquals("192.168.1.5:8888", system.value)
    }

    @Test
    fun recoversAfterACrash() {
        // The process died while connected: a new instance finds the old value in memory.
        val system = FakeSystem(":0")
        val proxy = GlobalProxy(system, FakeMemory(pending = "10.0.2.2:9090"))

        assertTrue(proxy.hasPending())
        proxy.restore()

        assertEquals("10.0.2.2:9090", system.value)
    }
}
