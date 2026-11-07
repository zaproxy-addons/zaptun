package dev.davidjosearaujo.zaptun

import android.os.Build
import android.os.IBinder
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Turns Android's always-on VPN, with "Block connections without VPN" (lockdown), on or off for
 * zaptun. With lockdown, nothing leaves the device around zaptun: not while it reconnects, not
 * after a crash, not before it starts after a boot.
 *
 * Only system code may change this (CONTROL_ALWAYS_ON_VPN is a signature permission, so `pm grant`
 * can't give it to the app), so this runs from adb as root, with zaptun's own APK as the class path,
 * the way scrcpy runs its server:
 *
 *   CLASSPATH=$(pm path dev.davidjosearaujo.zaptun | cut -d: -f2) \
 *       app_process /system/bin dev.davidjosearaujo.zaptun.AlwaysOnVpn on|off|status
 *
 * The system service is found by method name, not by binder transaction number, so the same code
 * works across Android versions.
 */
object AlwaysOnVpn {
    private const val PACKAGE = "dev.davidjosearaujo.zaptun"

    /** What "on" asks Android for, given zaptun's settings. */
    data class Plan(val lockdown: Boolean, val allowlist: List<String>)

    /**
     * Lockdown blocks every app that isn't on the VPN, so:
     *  - "only these apps": the others aren't on the VPN and would lose their network, so no lockdown
     *    (always-on alone still brings zaptun back after a boot or a crash);
     *  - "all apps except...": the excluded apps are allowed around the VPN (Android 10+); before
     *    Android 10 there is no such list, so no lockdown then.
     */
    fun plan(settings: Settings, sdk: Int): Plan = when {
        !settings.interceptAllApps -> Plan(lockdown = false, allowlist = emptyList())
        settings.excludedApps.isEmpty() -> Plan(lockdown = true, allowlist = emptyList())
        sdk >= Build.VERSION_CODES.Q -> Plan(lockdown = true, allowlist = settings.excludedApps.sorted())
        else -> Plan(lockdown = false, allowlist = emptyList())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(args.firstOrNull())
        } catch (e: Throwable) {
            val cause = if (e is InvocationTargetException) e.targetException else e
            System.err.println("zaptun: always-on VPN: ${cause.javaClass.simpleName}: ${cause.message}")
            1
        }
        System.exit(code)
    }

    private fun run(command: String?): Int {
        val user = currentUser()
        val vpn = VpnControl()
        when (command) {
            "on" -> {
                val plan = plan(savedSettings(user), Build.VERSION.SDK_INT)
                val previous = vpn.alwaysOnPackage(user)
                if (previous != null && previous != PACKAGE) println("Replacing the always-on VPN $previous with zaptun.")
                if (!vpn.setAlwaysOn(user, PACKAGE, plan.lockdown, plan.allowlist)) {
                    System.err.println("zaptun: Android refused to make zaptun the always-on VPN")
                    return 2
                }
                println(
                    when {
                        !plan.lockdown -> "zaptun is the always-on VPN (no lockdown: only some apps are intercepted)."
                        plan.allowlist.isEmpty() -> "zaptun is the always-on VPN, with lockdown: nothing goes around it."
                        else -> "zaptun is the always-on VPN, with lockdown; excluded apps go around it: ${plan.allowlist.joinToString()}."
                    },
                )
            }
            "off" -> {
                val current = vpn.alwaysOnPackage(user)
                if (current != PACKAGE) {
                    println(if (current == null) "No always-on VPN to turn off." else "The always-on VPN is $current, not zaptun: left alone.")
                    return 0
                }
                if (!vpn.setAlwaysOn(user, null, false, emptyList())) return 2
                println("Always-on VPN and lockdown are off.")
            }
            "status" -> println("always-on: ${vpn.alwaysOnPackage(user) ?: "none"}, lockdown: ${vpn.lockdown(user)}")
            else -> {
                System.err.println("usage: AlwaysOnVpn on|off|status")
                return 1
            }
        }
        return 0
    }

    /** zaptun's settings as last saved, read straight from its prefs file (this runs as root, outside the app). */
    private fun savedSettings(user: Int): Settings {
        val file = File("/data/user/$user/$PACKAGE/shared_prefs/zaptun.xml")
        return if (file.exists()) Settings.fromMap(Settings.parsePrefsXml(file.readText())) else Settings()
    }

    private fun currentUser(): Int = runCatching {
        Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
    }.getOrDefault(0)

    /** The system's VPN service: IVpnManager since Android 12, IConnectivityManager before. */
    private class VpnControl {
        private val service: Any

        init {
            val (name, stub) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "vpn_management" to "android.net.IVpnManager\$Stub"
            } else {
                "connectivity" to "android.net.IConnectivityManager\$Stub"
            }
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, name) as IBinder?
                ?: throw IllegalStateException("no $name service")
            service = Class.forName(stub).getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        }

        private fun method(name: String) = service.javaClass.methods.first { it.name == name }

        fun alwaysOnPackage(user: Int) = method("getAlwaysOnVpnPackage").invoke(service, user) as String?

        fun lockdown(user: Int) = runCatching { method("isVpnLockdownEnabled").invoke(service, user) as Boolean }.getOrDefault(false)

        /** Android 10+ takes a lockdown allowlist as a fourth argument; Android 8 and 9 don't. */
        fun setAlwaysOn(user: Int, pkg: String?, lockdown: Boolean, allowlist: List<String>): Boolean {
            val m = method("setAlwaysOnVpnPackage")
            val result = if (m.parameterCount >= 4) {
                m.invoke(service, user, pkg, lockdown, allowlist.ifEmpty { null })
            } else {
                m.invoke(service, user, pkg, lockdown)
            }
            return result as? Boolean ?: true // some versions return void
        }
    }
}
