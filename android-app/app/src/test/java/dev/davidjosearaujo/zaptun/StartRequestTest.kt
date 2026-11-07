package dev.davidjosearaujo.zaptun

import dev.davidjosearaujo.zaptun.StartRequest.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class StartRequestTest {
    private fun decide(action: String?, hasIntent: Boolean = true, wanted: Boolean = false) =
        StartRequest.decide(action, hasIntent, wanted)

    @Test
    fun startAndStopAreHonouredWhateverWasWanted() {
        for (wanted in listOf(true, false)) {
            assertEquals(Action.START, decide(ZapVpnService.ACTION_START, wanted = wanted))
            assertEquals(Action.STOP, decide(ZapVpnService.ACTION_STOP, wanted = wanted))
        }
    }

    @Test
    fun androidStartingAnAlwaysOnVpnConnects() {
        assertEquals(Action.START, decide(StartRequest.ALWAYS_ON_ACTION))
        assertEquals(Action.START, decide("android.net.VpnService"))
    }

    @Test
    fun aRestartAfterTheProcessWasKilledReconnectsOnlyIfTheUserHadLeftItConnected() {
        assertEquals(Action.START, decide(null, hasIntent = false, wanted = true))
        assertEquals(Action.STOP, decide(null, hasIntent = false, wanted = false))
    }

    @Test
    fun anythingElseIsIgnored() {
        assertEquals(Action.IGNORE, decide("some.other.ACTION"))
        assertEquals(Action.IGNORE, decide(null, hasIntent = true))
    }
}
