package dev.davidjosearaujo.zaptun

import dev.davidjosearaujo.zaptun.AlwaysOnVpn.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class AlwaysOnVpnTest {
    private val android14 = 34
    private val android9 = 28

    @Test
    fun allAppsGetLockdown() {
        assertEquals(Plan(lockdown = true, allowlist = emptyList()), AlwaysOnVpn.plan(Settings(), android14))
    }

    @Test
    fun excludedAppsAreAllowedAroundTheLockdown() {
        val s = Settings(excludedApps = setOf("com.example.mail", "com.example.bank"))
        assertEquals(Plan(lockdown = true, allowlist = listOf("com.example.bank", "com.example.mail")), AlwaysOnVpn.plan(s, android14))
    }

    @Test
    fun beforeAndroid10ExcludedAppsMeanNoLockdown() {
        // There is no allowlist there, so lockdown would cut the excluded apps off.
        val s = Settings(excludedApps = setOf("com.example.bank"))
        assertEquals(Plan(lockdown = false, allowlist = emptyList()), AlwaysOnVpn.plan(s, android9))
        assertEquals(Plan(lockdown = true, allowlist = emptyList()), AlwaysOnVpn.plan(Settings(), android9))
    }

    @Test
    fun onlySomeAppsMeansNoLockdown() {
        // The other apps aren't on the VPN, and lockdown would take their network away.
        val s = Settings(interceptAllApps = false, includedApps = setOf("com.example.shop"))
        assertEquals(Plan(lockdown = false, allowlist = emptyList()), AlwaysOnVpn.plan(s, android14))
    }
}
