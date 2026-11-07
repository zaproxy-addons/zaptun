package dev.davidjosearaujo.zaptun

import android.content.Context
import android.content.SharedPreferences

/**
 * What zaptun captures. Apps: either every app except [excludedApps], or only [includedApps].
 * Ports: connections to other ports go straight to their destination, never to ZAP.
 */
data class Settings(
    val proxyHost: String = DEFAULT_HOST,
    val proxyPort: Int = DEFAULT_PORT,
    val interceptAllApps: Boolean = true,
    val includedApps: Set<String> = emptySet(),
    val excludedApps: Set<String> = emptySet(),
    val ports: Set<Int> = DEFAULT_PORTS,
    /** Also look for HTTP on every other port (see TcpRelay's probes). */
    val allPorts: Boolean = false,
    /** SHA-256 of ZAP's root CA (DER), set by the ZAP add-on; used to show whether it's trusted. */
    val caSha256: String? = null,
) {
    /**
     * Everything the user can change goes back to its default. The CA fingerprint isn't a setting:
     * it comes from the ZAP add-on (or script) and says which CA to look for, so it stays.
     */
    fun restoreDefaults() = Settings(caSha256 = caSha256)

    fun intercepts(pkg: String) = if (interceptAllApps) pkg !in excludedApps else pkg in includedApps

    fun withApp(pkg: String, intercepted: Boolean) = when {
        interceptAllApps -> copy(excludedApps = if (intercepted) excludedApps - pkg else excludedApps + pkg)
        else -> copy(includedApps = if (intercepted) includedApps + pkg else includedApps - pkg)
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_HOST, proxyHost)
            .putInt(KEY_PORT, proxyPort)
            .putBoolean(KEY_ALL_APPS, interceptAllApps)
            .putString(KEY_INCLUDED, includedApps.sorted().joinToString(","))
            .putString(KEY_EXCLUDED, excludedApps.sorted().joinToString(","))
            .putString(KEY_PORTS, ports.sorted().joinToString(","))
            .putBoolean(KEY_ALL_PORTS, allPorts)
            .putString(KEY_CA, caSha256)
            .apply()
    }

    companion object {
        // 10.0.2.2 is the emulator's alias for the host's 127.0.0.1. The ZAP add-on uses
        // 127.0.0.1 instead, tunnelled to ZAP with adb reverse.
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 9090
        val DEFAULT_PORTS = setOf(80, 443, 8000, 8008, 8080, 8443, 8888, 9000)

        private const val KEY_HOST = "proxy_host"
        private const val KEY_PORT = "proxy_port"
        private const val KEY_ALL_APPS = "intercept_all_apps"
        private const val KEY_INCLUDED = "apps" // the add-on's "only these apps" list
        private const val KEY_EXCLUDED = "excluded_apps"
        const val KEY_PORTS = "ports"
        const val KEY_ALL_PORTS = "all_ports"
        private const val KEY_CA = "ca_sha256"

        fun load(context: Context) = load(prefs(context))

        fun load(p: SharedPreferences) = fromMap(p.all)

        /** From the stored values: SharedPreferences.getAll(), or the prefs file read as root by [AlwaysOnVpn]. */
        fun fromMap(m: Map<String, *>): Settings {
            val included = parseApps(m[KEY_INCLUDED] as? String ?: "")
            return Settings(
                proxyHost = m[KEY_HOST] as? String ?: DEFAULT_HOST,
                proxyPort = m[KEY_PORT] as? Int ?: DEFAULT_PORT,
                // Before per-app toggles, a non-empty "apps" list meant "only these apps".
                interceptAllApps = m[KEY_ALL_APPS] as? Boolean ?: included.isEmpty(),
                includedApps = included,
                excludedApps = parseApps(m[KEY_EXCLUDED] as? String ?: ""),
                ports = (m[KEY_PORTS] as? String)?.let(::parsePorts) ?: DEFAULT_PORTS,
                allPorts = m[KEY_ALL_PORTS] as? Boolean ?: false,
                caSha256 = m[KEY_CA] as? String,
            )
        }

        /**
         * The values in a SharedPreferences XML file, typed as getAll() returns them. Only the types
         * zaptun stores (string, boolean, int) are read.
         */
        fun parsePrefsXml(xml: String): Map<String, Any> {
            val values = HashMap<String, Any>()
            Regex("""<(boolean|int) name="([^"]*)" value="([^"]*)"\s*/>""").findAll(xml).forEach {
                val (type, name, value) = it.destructured
                values[unescape(name)] = if (type == "boolean") value == "true" else value.toIntOrNull() ?: return@forEach
            }
            Regex("""<string name="([^"]*)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                values[unescape(it.groupValues[1])] = unescape(it.groupValues[2])
            }
            Regex("""<string name="([^"]*)"\s*/>""").findAll(xml).forEach { values[unescape(it.groupValues[1])] = "" }
            return values
        }

        private fun unescape(s: String) =
            s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private val SEPARATORS = charArrayOf(',', ' ', '\n')

        private fun tokens(s: String) = s.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        fun parseApps(s: String) = tokens(s).toSet()

        /** Ports from "80, 443 8080"; invalid entries are dropped. */
        fun parsePorts(s: String) = tokens(s).mapNotNull { it.toIntOrNull() }.filter { it in 1..65535 }.toSet()

        private const val KEY_WANTED = "vpn_wanted"

        /** Whether the user left the VPN connected: if Android kills the app, the VPN comes back only if so. */
        fun wanted(context: Context) = prefs(context).getBoolean(KEY_WANTED, false)

        fun setWanted(context: Context, wanted: Boolean) = prefs(context).edit().putBoolean(KEY_WANTED, wanted).apply()

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("zaptun", Context.MODE_PRIVATE)
    }
}
