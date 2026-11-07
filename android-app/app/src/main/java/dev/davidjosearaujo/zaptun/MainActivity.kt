package dev.davidjosearaujo.zaptun

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.davidjosearaujo.zaptun.net.ConnectionRecord

/**
 * The single zaptun screen: connection status, ZAP CA trust, intercepted ports and apps.
 * The ZAP add-on drives it over adb, e.g.:
 *   adb shell am start -n dev.davidjosearaujo.zaptun/.MainActivity \
 *       --es host 127.0.0.1 --ei port 9090 --es apps 'com.example.app' --es ca_sha256 <hex> --ez start true
 *   adb shell am start -n dev.davidjosearaujo.zaptun/.MainActivity --ez stop true
 * An empty "apps" means all apps. "ports" (e.g. '80,443') replaces the intercepted ports.
 * "all_ports" (--ez all_ports true) also looks for HTTP on every other port.
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var proxy: TextView
    private lateinit var toggle: Button
    private lateinit var systemTrust: TextView
    private lateinit var userTrust: TextView
    private lateinit var trustHint: TextView
    private lateinit var ports: FlowLayout
    private lateinit var newPort: EditText
    private lateinit var portsSummary: TextView
    private lateinit var allPorts: Switch
    private lateinit var allApps: Switch
    private lateinit var systemApps: CheckBox
    private lateinit var appsHint: TextView
    private lateinit var appsSummary: TextView
    private lateinit var apps: AppList
    private lateinit var activitySummary: TextView
    private lateinit var activityStats: TextView
    private lateinit var activityEmpty: TextView
    private lateinit var activityList: LinearLayout
    private var activityOpen = false

    private val main = Handler(Looper.getMainLooper())

    // App changes need a new VPN session; wait for a pause in toggling before reconnecting.
    private val reconnect = Runnable { if (ConnectionLog.running) launchService() }

    // Apps installed or removed while this screen is showing.
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }
    private var loadingApps = false
    private var reloadAgain = false

    // Keeps the Activity section current (bytes and ages change while connections are open).
    private val activityTick = object : Runnable {
        override fun run() {
            renderActivity()
            main.postDelayed(this, ACTIVITY_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings.load(this)

        apps = AppList(findViewById(R.id.apps_list), { settings.intercepts(it) }) { pkg ->
            update(settings.withApp(pkg, !settings.intercepts(pkg)), reconnectVpn = true)
        }

        status = findViewById(R.id.status)
        proxy = findViewById(R.id.proxy)
        toggle = findViewById(R.id.toggle)
        systemTrust = findViewById(R.id.system_trust)
        userTrust = findViewById(R.id.user_trust)
        trustHint = findViewById(R.id.trust_hint)
        ports = findViewById(R.id.ports)
        newPort = findViewById(R.id.new_port)
        portsSummary = findViewById(R.id.ports_summary)
        allPorts = findViewById(R.id.all_ports)
        allApps = findViewById(R.id.all_apps)
        systemApps = findViewById(R.id.system_apps)
        appsHint = findViewById(R.id.apps_hint)
        appsSummary = findViewById(R.id.apps_summary)
        activitySummary = findViewById(R.id.activity_summary)
        activityStats = findViewById(R.id.activity_stats)
        activityEmpty = findViewById(R.id.activity_empty)
        activityList = findViewById(R.id.activity_list)

        collapsible(R.id.ports_header, R.id.ports_body, R.id.ports_chevron, KEY_PORTS_OPEN)
        collapsible(R.id.apps_header, R.id.apps_body, R.id.apps_chevron, KEY_APPS_OPEN) { apps.setVisible(it) }
        collapsible(R.id.activity_header, R.id.activity_body, R.id.activity_chevron, KEY_ACTIVITY_OPEN) {
            activityOpen = it
            renderActivity()
        }
        findViewById<View>(R.id.activity_clear).setOnClickListener {
            ConnectionLog.clearConnections()
            renderActivity()
        }
        findViewById<View>(R.id.add_port).setOnClickListener { addPorts() }
        findViewById<View>(R.id.restore_ports).setOnClickListener {
            update(settings.copy(ports = Settings.DEFAULT_PORTS, allPorts = false), reconnectVpn = false)
        }
        // Read live by the VPN, like the ports: no reconnect.
        allPorts.setOnCheckedChangeListener { _, checked ->
            if (checked != settings.allPorts) update(settings.copy(allPorts = checked), reconnectVpn = false)
        }
        findViewById<View>(R.id.restore_all).setOnClickListener { confirmRestoreAll() }
        newPort.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) addPorts() }
        }

        toggle.setOnClickListener { if (ConnectionLog.running) stopVpn() else startVpn() }
        proxy.setOnClickListener { editProxy() }
        allApps.setOnCheckedChangeListener { _, checked ->
            if (checked != settings.interceptAllApps) update(settings.copy(interceptAllApps = checked), reconnectVpn = true)
        }
        systemApps.setOnCheckedChangeListener { _, checked -> apps.setShowSystem(checked) }

        appsHint.setText(R.string.apps_loading)

        handleExtras(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
        checkTrust() // the CA may have just been injected; onResume doesn't run if we're already in front
    }

    override fun onResume() {
        super.onResume()
        ConnectionLog.setListener(::render)
        // A crash or a killed process while connected leaves Android's global proxy switched off.
        if (!ConnectionLog.running) AndroidGlobalProxy.create(this).restore()
        render()
        checkTrust()
        main.post(activityTick)
        // Apps may have been installed while we were away (Play Store, an APK installer).
        reloadApps()
        registerReceiver(
            packageChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
    }

    override fun onPause() {
        main.removeCallbacks(activityTick)
        unregisterReceiver(packageChanges)
        ConnectionLog.setListener(null)
        super.onPause()
    }

    /** Reloads the app list in the background; a request that arrives during a load runs one more after it. */
    private fun reloadApps() {
        if (loadingApps) {
            reloadAgain = true
            return
        }
        loadingApps = true
        val known = apps.known()
        Thread {
            val loaded = AppList.load(this, known)
            main.post {
                loadingApps = false
                apps.setApps(loaded)
                render()
                if (reloadAgain) {
                    reloadAgain = false
                    reloadApps()
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) launchService() else toast(getString(R.string.permission_denied))
        }
    }

    /** Settings and start/stop from adb (the ZAP add-on). */
    private fun handleExtras(intent: Intent) {
        val extras = intent.extras ?: return
        if (extras.getBoolean("stop")) {
            stopVpn()
            return
        }
        var s = settings
        extras.getString("host")?.let { s = s.copy(proxyHost = it) }
        if (extras.containsKey("port")) s = s.copy(proxyPort = extras.getInt("port"))
        extras.getString("apps")?.let { list ->
            val chosen = Settings.parseApps(list)
            s = if (chosen.isEmpty()) s.copy(interceptAllApps = true) else s.copy(interceptAllApps = false, includedApps = chosen)
        }
        extras.getString("ports")?.let { s = s.copy(ports = Settings.parsePorts(it)) }
        if (extras.containsKey("all_ports")) s = s.copy(allPorts = extras.getBoolean("all_ports"))
        extras.getString("ca_sha256")?.let { s = s.copy(caSha256 = it.ifBlank { null }) }
        update(s, reconnectVpn = false)
        if (extras.getBoolean("start")) startVpn()
    }

    private fun update(s: Settings, reconnectVpn: Boolean) {
        settings = s
        s.save(this)
        render()
        if (reconnectVpn) {
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, RECONNECT_DELAY_MS)
        }
    }

    private fun render() {
        val running = ConnectionLog.running
        val failure = ConnectionLog.statusText.takeIf { !running && it.startsWith("Failed") }
        status.text = failure ?: getString(if (running) R.string.status_on else R.string.status_off)
        status.setTextColor(getColor(if (running) R.color.ok else R.color.bad))
        proxy.text = getString(R.string.proxy_line, settings.proxyHost, settings.proxyPort)
        toggle.setText(if (running) R.string.disconnect else R.string.connect)
        toggle.backgroundTintList = ColorStateList.valueOf(getColor(if (running) R.color.button_disconnect else R.color.button_connect))

        renderPorts()

        allApps.isChecked = settings.interceptAllApps
        val excluded = settings.excludedApps.size
        appsHint.text = if (settings.interceptAllApps) {
            getString(R.string.apps_all_hint, excluded)
        } else {
            getString(R.string.apps_selected_hint, settings.includedApps.size)
        }
        appsSummary.text = when {
            !settings.interceptAllApps -> getString(R.string.apps_summary_only, settings.includedApps.size)
            excluded > 0 -> getString(R.string.apps_summary_all_except, excluded)
            else -> getString(R.string.apps_summary_all)
        }
        apps.refresh()
    }

    /** The recent connections (newest first) and the counters of what was refused, while the section is open. */
    private fun renderActivity() {
        val connections = ConnectionLog.connections()
        activitySummary.text = resources.getQuantityString(R.plurals.activity_summary, connections.size, connections.size)
        if (!activityOpen) return

        val shown = connections.take(ACTIVITY_ROWS)
        while (activityList.childCount > shown.size) activityList.removeViewAt(activityList.childCount - 1)
        while (activityList.childCount < shown.size) activityList.addView(activityRow())
        val now = System.currentTimeMillis()
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val normal = themeColor(android.R.attr.textColorPrimary)
        shown.forEachIndexed { i, c ->
            (activityList.getChildAt(i) as TextView).apply {
                text = getString(R.string.activity_row, clock.format(java.util.Date(c.startedAt)), c.describe(now))
                setTextColor(if (c.state == ConnectionRecord.State.FAILED) getColor(R.color.bad) else normal)
            }
        }
        activityEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        activityStats.text = activityStats()
    }

    private fun activityRow() = TextView(this).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 11f
        setPadding(0, dp(3), 0, dp(3))
        setTextIsSelectable(true)
    }

    private fun activityStats(): String {
        val s = ConnectionLog.stats
        fun ports(list: List<Pair<Int, Long>>) = list.take(5).joinToString(", ") { "${it.first}×${it.second}" }
        val lines = mutableListOf<String>()
        if (s.udpFlows.get() > 0) lines += getString(R.string.activity_udp_passed, ports(s.udpFlowsByPort()))
        if (s.udpRefused.get() > 0) lines += getString(R.string.activity_udp_refused, ports(s.udpByPort()))
        if (s.ipv6Refused.get() > 0) lines += getString(R.string.activity_ipv6_refused, s.ipv6Refused.get())
        if (s.dnsQueries.get() > 0) {
            lines += getString(R.string.activity_dns, s.dnsQueries.get(), s.dnsFailed.get(), s.dnsTruncated.get())
        }
        return if (lines.isEmpty()) getString(R.string.activity_nothing_refused) else lines.joinToString("\n")
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return getColor(value.resourceId)
    }

    /** Tapping [headerId] shows or hides [bodyId]; the state is remembered across launches. */
    private fun collapsible(headerId: Int, bodyId: Int, chevronId: Int, key: String, onChange: (Boolean) -> Unit = {}) {
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<ImageView>(chevronId)
        val prefs = Settings.prefs(this)
        fun show(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 0f else -90f
            onChange(open)
        }
        show(prefs.getBoolean(key, false))
        findViewById<View>(headerId).setOnClickListener {
            val open = body.visibility != View.VISIBLE
            prefs.edit().putBoolean(key, open).apply()
            show(open)
        }
    }

    private fun renderPorts() {
        ports.removeAllViews()
        for (port in settings.ports.sorted()) {
            ports.addView(chip("$port  ✕") { update(settings.copy(ports = settings.ports - port), reconnectVpn = false) })
        }
        if (settings.ports.isEmpty()) {
            ports.addView(TextView(this).apply { setText(R.string.ports_none); setTextColor(getColor(R.color.bad)) })
        }
        val count = resources.getQuantityString(R.plurals.ports_summary, settings.ports.size, settings.ports.size)
        portsSummary.text = if (settings.allPorts) getString(R.string.ports_summary_all, count) else count
        allPorts.isChecked = settings.allPorts
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setBackgroundResource(R.drawable.chip)
        val h = dp(12)
        val v = dp(6)
        setPadding(h, v, h, v)
        setOnClickListener { onClick() }
    }

    private fun addPorts() {
        val added = Settings.parsePorts(newPort.text.toString())
        if (added.isEmpty()) {
            newPort.error = getString(R.string.invalid_ports)
            return
        }
        newPort.text.clear()
        update(settings.copy(ports = settings.ports + added), reconnectVpn = false)
    }

    private fun confirmRestoreAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_all_title)
            .setMessage(getString(R.string.restore_all_message, Settings.DEFAULT_HOST, Settings.DEFAULT_PORT))
            .setPositiveButton(R.string.restore_all_confirm) { _, _ ->
                update(settings.restoreDefaults(), reconnectVpn = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editProxy() {
        val host = EditText(this).apply { setText(settings.proxyHost); setHint(R.string.proxy_host); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val port = EditText(this).apply { setText(settings.proxyPort.toString()); setHint(R.string.proxy_port); inputType = InputType.TYPE_CLASS_NUMBER }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(host); addView(port) }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_proxy)
            .setView(padded(form))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = port.text.toString().trim().toIntOrNull()
                val h = host.text.toString().trim()
                if (h.isEmpty() || p == null || p !in 1..65535) toast(getString(R.string.invalid_proxy))
                else update(settings.copy(proxyHost = h, proxyPort = p), reconnectVpn = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkTrust() {
        listOf(systemTrust, userTrust).forEach { it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_unknown, 0, 0, 0) }
        trustHint.setText(R.string.trust_checking)
        val sha256 = settings.caSha256
        Thread {
            val result = runCatching { CaTrust.check(sha256) }
            main.post {
                result.onSuccess { t ->
                    mark(systemTrust, t.system)
                    mark(userTrust, t.user)
                    val name = t.name ?: getString(R.string.zap_ca)
                    trustHint.text = when {
                        t.system -> getString(R.string.trust_system_ok, name)
                        t.user -> getString(R.string.trust_user_only, name)
                        else -> getString(R.string.trust_missing)
                    }
                }.onFailure { e ->
                    trustHint.text = getString(R.string.trust_error, e.message)
                }
            }
        }.start()
    }

    private fun mark(view: TextView, ok: Boolean) =
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(if (ok) R.drawable.ic_check else R.drawable.ic_cross, 0, 0, 0)

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        @Suppress("DEPRECATION")
        if (consent != null) startActivityForResult(consent, REQUEST_VPN) else launchService()
    }

    private fun launchService() {
        startService(Intent(this, ZapVpnService::class.java).setAction(ZapVpnService.ACTION_START))
    }

    private fun stopVpn() {
        main.removeCallbacks(reconnect)
        startService(Intent(this, ZapVpnService::class.java).setAction(ZapVpnService.ACTION_STOP))
    }

    private fun padded(view: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_VPN = 1
        private const val RECONNECT_DELAY_MS = 1200L
        private const val KEY_PORTS_OPEN = "ui_ports_open"
        private const val KEY_APPS_OPEN = "ui_apps_open"
        private const val KEY_ACTIVITY_OPEN = "ui_activity_open"
        private const val ACTIVITY_REFRESH_MS = 1000L
        private const val ACTIVITY_ROWS = 100
    }
}
