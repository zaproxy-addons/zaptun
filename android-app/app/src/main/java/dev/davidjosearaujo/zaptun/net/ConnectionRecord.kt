package dev.davidjosearaujo.zaptun.net

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One TCP connection through the relay, from the app's connect to its end, for the log and the Activity list. */
class ConnectionRecord(val id: Long, val startedAt: Long) {
    enum class State { WAITING, OPEN, CLOSED, FAILED }

    enum class Closer { APP, SERVER }

    @Volatile var kind: Sniffer.Kind = Sniffer.Kind.SILENT
    @Volatile var host: String = "?"
    @Volatile var port: Int = 0
    @Volatile var route: TcpRelay.Route? = null

    /** WAITING until the app sends its first bytes (or gives up), OPEN while data can flow. */
    @Volatile var state = State.WAITING
        private set

    /** Which side ended the stream first: a clean close, as opposed to an error. */
    @Volatile var closedBy: Closer? = null
        private set

    @Volatile var error: String? = null
        private set

    @Volatile var endedAt = 0L
        private set

    /** Bytes sent by the app, and bytes sent back to it. */
    val up = AtomicLong()
    val down = AtomicLong()

    private val finished = AtomicBoolean()

    fun opened() {
        state = State.OPEN
    }

    @Synchronized
    fun closedFirstBy(who: Closer) {
        if (closedBy == null) closedBy = who
    }

    /** Keeps the first error: later ones are usually just our own sockets closing. */
    @Synchronized
    fun failedWith(message: String) {
        if (error == null) error = message
    }

    /** Ends the record. Returns true only for the call that ended it, so it is reported once. */
    fun finish(now: Long): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        endedAt = now
        state = if (error != null) State.FAILED else State.CLOSED
        return true
    }

    fun target() = "$host:$port"

    /** One line for logcat and the screen, e.g. `TLS api.example.com:443 → ZAP · closed by app after 1.2 s · ↑1.2 KB ↓5.1 KB`. */
    fun describe(now: Long): String {
        val age = format(duration((if (endedAt != 0L) endedAt else now) - startedAt))
        val status = when (state) {
            State.WAITING -> "waiting for the app to send data ($age)"
            State.OPEN -> "open $age"
            State.CLOSED -> "closed${closedBy?.let { " by ${it.name.lowercase()}" } ?: ""} after $age"
            State.FAILED -> "failed after $age: $error"
        }
        val route = when (route) {
            TcpRelay.Route.PROXY -> " → ZAP"
            TcpRelay.Route.DIRECT -> " → direct"
            null -> ""
        }
        return "${kindLabel(kind)} ${target()}$route · $status · ↑${bytes(up.get())} ↓${bytes(down.get())}"
    }

    companion object {
        fun kindLabel(kind: Sniffer.Kind) = when (kind) {
            Sniffer.Kind.TLS -> "TLS"
            Sniffer.Kind.HTTP -> "HTTP"
            Sniffer.Kind.OTHER -> "other protocol"
            Sniffer.Kind.SILENT -> "silent"
            Sniffer.Kind.PORT_SKIPPED -> "not intercepted"
        }

        private fun duration(ms: Long) = maxOf(0L, ms)

        fun format(ms: Long): String = when {
            ms < 1_000 -> "$ms ms"
            ms < 60_000 -> "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
            else -> "${ms / 60_000} min ${ms % 60_000 / 1000} s"
        }

        fun bytes(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, n / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.ROOT, n / (1024.0 * 1024.0))
        }
    }
}
