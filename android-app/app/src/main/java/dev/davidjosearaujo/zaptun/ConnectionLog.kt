package dev.davidjosearaujo.zaptun

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.davidjosearaujo.zaptun.net.ConnectionRecord
import dev.davidjosearaujo.zaptun.net.Stats

/**
 * What the VPN is doing, for the screen and for logcat (tag "zaptun"): its state, the recent
 * connections (each logged when it opens and again when it ends) and counters for what it refuses.
 */
object ConnectionLog {
    private const val TAG = "zaptun"
    private const val MAX_CONNECTIONS = 300

    private val main = Handler(Looper.getMainLooper())
    private val connections = ArrayDeque<ConnectionRecord>()
    private var listener: (() -> Unit)? = null

    val stats = Stats()

    @Volatile var running = false
        private set
    @Volatile var statusText = ""
        private set

    /** A connection arrived: remember it for the Activity list. */
    fun track(record: ConnectionRecord) {
        synchronized(connections) {
            connections.addFirst(record)
            while (connections.size > MAX_CONNECTIONS) connections.removeLast()
        }
    }

    /** Its route is known: log it now, so a connection that then hangs still shows up in logcat. */
    fun opened(record: ConnectionRecord) = add("open  ${record.describe(System.currentTimeMillis())}")

    /** It ended: log how, with the bytes moved. */
    fun finished(record: ConnectionRecord) = add("end   ${record.describe(System.currentTimeMillis())}")

    /** Newest first. */
    fun connections(): List<ConnectionRecord> = synchronized(connections) { connections.toList() }

    fun clearConnections() {
        synchronized(connections) { connections.clear() }
        stats.reset()
    }

    fun add(text: String) {
        Log.i(TAG, text)
    }

    fun status(running: Boolean, text: String) {
        this.running = running
        statusText = text
        add(text)
        main.post { listener?.invoke() }
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }
}
