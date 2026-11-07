package dev.davidjosearaujo.zaptun

/**
 * Handles Android's global proxy (Settings.Global.http_proxy, "host:port"). Apps that honour it send
 * their traffic to that proxy and never meet the VPN's interception, so zaptun switches it off while
 * it is connected and puts the old value back when it disconnects.
 *
 * The state needed to put it back is kept in [memory], so a crash or a killed process doesn't leave
 * the proxy off: [restore] runs again the next time the app starts.
 */
class GlobalProxy(private val backend: Backend, private val memory: Memory) {

    interface Backend {
        fun read(): String?

        /** Returns false if the change was refused (no permission, no root). */
        fun write(value: String): Boolean
    }

    interface Memory {
        /** The value to put back; "" means the proxy was unset. Null means nothing is waiting to be restored. */
        var pending: String?
    }

    sealed class Outcome {
        /** There was no global proxy to switch off. */
        object NotSet : Outcome()

        /** The proxy [was] switched off. */
        data class Cleared(val was: String) : Outcome()

        /** The proxy is [current] and couldn't be switched off. */
        data class Failed(val current: String) : Outcome()
    }

    /** The proxy that is set now, or null if none is. */
    fun current(): String? = read().takeIf { it.isNotEmpty() }

    /** Switches the global proxy off, remembering what it was. Safe to call again while already off. */
    fun clear(): Outcome {
        val now = current() ?: return if (memory.pending != null) Outcome.Cleared(memory.pending!!) else Outcome.NotSet
        if (!backend.write(OFF)) return Outcome.Failed(now)
        memory.pending = now
        return Outcome.Cleared(now)
    }

    /**
     * Puts the old proxy back, unless something else has set a proxy since: then that is the user's
     * newer choice and is left alone.
     */
    fun restore() {
        val saved = memory.pending ?: return
        memory.pending = null
        if (saved.isEmpty() || current() != null) return
        backend.write(saved)
    }

    /** True if [restore] has something to do. */
    fun hasPending() = memory.pending != null

    private fun read(): String {
        val v = backend.read()?.trim().orEmpty()
        return if (v == OFF || v == "null") "" else v
    }

    companion object {
        /** What `settings put global http_proxy :0` writes: Android's way of saying "no proxy". */
        const val OFF = ":0"
    }
}
