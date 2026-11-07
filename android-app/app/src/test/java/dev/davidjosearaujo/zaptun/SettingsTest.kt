package dev.davidjosearaujo.zaptun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun readsThePrefsFileAsAndroidWritesIt() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="proxy_host">127.0.0.1</string>
    <int name="proxy_port" value="9191" />
    <boolean name="intercept_all_apps" value="true" />
    <string name="excluded_apps">com.example.bank,com.example.mail</string>
    <string name="apps"></string>
    <string name="ports">80,443</string>
    <boolean name="all_ports" value="true" />
    <string name="ca_sha256">a&amp;b</string>
    <boolean name="vpn_wanted" value="true" />
</map>
"""
        val s = Settings.fromMap(Settings.parsePrefsXml(xml))

        assertEquals(
            Settings(
                proxyHost = "127.0.0.1",
                proxyPort = 9191,
                interceptAllApps = true,
                excludedApps = setOf("com.example.bank", "com.example.mail"),
                ports = setOf(80, 443),
                allPorts = true,
                caSha256 = "a&b",
            ),
            s,
        )
    }

    @Test
    fun anEmptyPrefsFileMeansTheDefaults() {
        assertEquals(Settings(), Settings.fromMap(Settings.parsePrefsXml("<map />")))
    }

    @Test
    fun restoreDefaultsResetsEverythingExceptTheCaFingerprint() {
        val changed = Settings(
            proxyHost = "127.0.0.1",
            proxyPort = 9191,
            interceptAllApps = false,
            includedApps = setOf("com.example.shop"),
            excludedApps = setOf("com.example.bank"),
            ports = emptySet(),
            allPorts = true,
            caSha256 = "abc123",
        )

        val restored = changed.restoreDefaults()

        assertEquals(Settings(caSha256 = "abc123"), restored)
        assertEquals("10.0.2.2", restored.proxyHost)
        assertEquals(9090, restored.proxyPort)
        assertTrue(restored.interceptAllApps)
        assertEquals(Settings.DEFAULT_PORTS, restored.ports)
        assertEquals(setOf(80, 443, 8000, 8008, 8080, 8443, 8888, 9000), restored.ports)
    }

    @Test
    fun parsesPortsAndDropsInvalidOnes() {
        assertEquals(setOf(80, 443, 8080), Settings.parsePorts("80, 443 8080"))
        assertEquals(setOf(9443, 3000), Settings.parsePorts("9443,\n3000"))
        assertEquals(setOf(65535, 1), Settings.parsePorts("0 1 65535 65536 -5 abc"))
        assertEquals(emptySet<Int>(), Settings.parsePorts(" ,, "))
    }

    @Test
    fun parsesApps() {
        assertEquals(setOf("a.b", "c.d"), Settings.parseApps(" a.b, c.d\n a.b"))
        assertEquals(emptySet<String>(), Settings.parseApps(""))
    }

    @Test
    fun appSelectionFollowsTheMode() {
        val all = Settings(excludedApps = setOf("bank"))
        assertTrue(all.intercepts("shop"))
        assertFalse(all.intercepts("bank"))
        assertFalse(all.withApp("shop", intercepted = false).intercepts("shop"))
        assertTrue(all.withApp("bank", intercepted = true).intercepts("bank"))

        val only = Settings(interceptAllApps = false, includedApps = setOf("shop"))
        assertTrue(only.intercepts("shop"))
        assertFalse(only.intercepts("bank"))
        assertTrue(only.withApp("bank", intercepted = true).intercepts("bank"))
        assertFalse(only.withApp("shop", intercepted = false).intercepts("shop"))
    }
}
