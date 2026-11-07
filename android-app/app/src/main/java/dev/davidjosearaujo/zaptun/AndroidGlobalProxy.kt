package dev.davidjosearaujo.zaptun

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.TimeUnit

/** [GlobalProxy] on a real device: the system setting, and the preferences for what to put back. */
object AndroidGlobalProxy {
    private const val TAG = "zaptun"
    private const val KEY = "http_proxy"
    private const val PENDING = "global_proxy_pending"

    fun create(context: Context): GlobalProxy {
        val app = context.applicationContext
        val prefs = Settings.prefs(app)
        val backend = object : GlobalProxy.Backend {
            override fun read(): String? = android.provider.Settings.Global.getString(app.contentResolver, KEY)

            override fun write(value: String) = writeDirectly(app, value) || writeAsRoot(value)
        }
        val memory = object : GlobalProxy.Memory {
            override var pending: String?
                get() = prefs.getString(PENDING, null)
                set(v) = prefs.edit().apply { if (v == null) remove(PENDING) else putString(PENDING, v) }.apply()
        }
        return GlobalProxy(backend, memory)
    }

    /** True if this app may change global settings (granted over adb, see the README). */
    fun canWriteSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Needs WRITE_SECURE_SETTINGS: `adb shell pm grant dev.davidjosearaujo.zaptun android.permission.WRITE_SECURE_SETTINGS`. */
    private fun writeDirectly(context: Context, value: String) = try {
        android.provider.Settings.Global.putString(context.contentResolver, KEY, value)
    } catch (e: SecurityException) {
        false
    }

    /**
     * For rooted phones with a root manager (Magisk): stock builds, including emulator images, only let
     * adb's shell and root use su, not apps, so there this just fails and the permission above is needed.
     */
    private fun writeAsRoot(value: String) = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global $KEY $value"))
        process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Exception) {
        Log.i(TAG, "su isn't available: ${e.message}")
        false
    }

    private const val SU_TIMEOUT_SECONDS = 5L
}
