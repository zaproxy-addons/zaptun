package dev.davidjosearaujo.zaptun

/** Decides what a command sent to the VPN service means. */
object StartRequest {
    enum class Action { START, STOP, IGNORE }

    /** Android's own action when it starts an always-on VPN. */
    const val ALWAYS_ON_ACTION = "android.net.VpnService"

    /**
     * [action] is the intent's action, or null with [hasIntent] false when Android restarted the
     * service after killing its process. In that case the VPN comes back only if the user had left it
     * connected ([wanted]), not if they had disconnected.
     */
    fun decide(action: String?, hasIntent: Boolean, wanted: Boolean): Action = when {
        action == ZapVpnService.ACTION_STOP -> Action.STOP
        action == ZapVpnService.ACTION_START -> Action.START
        action == ALWAYS_ON_ACTION -> Action.START
        !hasIntent -> if (wanted) Action.START else Action.STOP
        else -> Action.IGNORE
    }
}
