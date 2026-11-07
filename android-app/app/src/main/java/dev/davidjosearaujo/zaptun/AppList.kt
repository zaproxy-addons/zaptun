package dev.davidjosearaujo.zaptun

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Rows of installed apps that use the network, each with an "intercepted" switch, inside [container]. */
class AppList(
    private val container: LinearLayout,
    private val isIntercepted: (String) -> Boolean,
    private val onToggle: (String) -> Unit,
) {
    class App(val pkg: String, val label: String, val icon: Drawable, val system: Boolean, val updated: Long)

    private var all: List<App> = emptyList()
    private var showSystem = false

    // Rows are only built once the section is open: inflating one per installed app is the slow part.
    private var visible = false
    private var dirty = false
    private val switches = mutableMapOf<String, Switch>()

    /** The apps shown now, so a reload can reuse their labels and icons. */
    fun known(): Map<String, App> = all.associateBy { it.pkg }

    /** Shows [apps]; if the list didn't change (a reload that found nothing new) nothing is rebuilt. */
    fun setApps(apps: List<App>) {
        if (apps.map { it.pkg to it.label } == all.map { it.pkg to it.label }) {
            all = apps
            return
        }
        all = apps
        rebuild()
    }

    fun setShowSystem(show: Boolean) {
        showSystem = show
        rebuild()
    }

    /** Called when the section opens or closes; builds the rows if they were postponed. */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible && dirty) rebuild()
    }

    /** Updates the switches in place, e.g. after a toggle or a mode change. */
    fun refresh() {
        for ((pkg, switch) in switches) switch.isChecked = isIntercepted(pkg)
    }

    private fun rebuild() {
        if (!visible) {
            dirty = true
            return
        }
        dirty = false
        container.removeAllViews()
        switches.clear()
        val inflater = LayoutInflater.from(container.context)
        for (app in all) {
            if (app.system && !showSystem) continue
            val row = inflater.inflate(R.layout.item_app, container, false)
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.label).text = app.label
            row.findViewById<TextView>(R.id.pkg).text = app.pkg
            val switch = row.findViewById<Switch>(R.id.intercepted)
            switch.isChecked = isIntercepted(app.pkg)
            switches[app.pkg] = switch
            row.setOnClickListener { onToggle(app.pkg) }
            container.addView(row)
        }
        container.visibility = if (container.childCount == 0) View.GONE else View.VISIBLE
    }

    companion object {
        /**
         * Apps that request INTERNET, except zaptun itself, sorted by label. Call off the UI thread.
         * Loading labels and icons is the slow part, so apps in [known] that haven't been updated
         * since are reused as they are.
         */
        fun load(context: Context, known: Map<String, App> = emptyMap()): List<App> {
            val pm = context.packageManager
            return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { p ->
                    p.packageName != context.packageName &&
                        p.applicationInfo != null &&
                        p.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                }
                .map { p ->
                    known[p.packageName]?.takeIf { it.updated == p.lastUpdateTime } ?: run {
                        val info = p.applicationInfo!!
                        App(
                            pkg = p.packageName,
                            label = info.loadLabel(pm).toString(),
                            icon = info.loadIcon(pm),
                            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                            updated = p.lastUpdateTime,
                        )
                    }
                }
                .sortedBy { it.label.lowercase() }
        }
    }
}
