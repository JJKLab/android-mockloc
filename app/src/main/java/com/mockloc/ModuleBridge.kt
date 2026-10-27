package com.mockloc

import android.content.Context

/**
 * The App's side of the config contract, and the single place that knows its text format.
 *
 * Why this exists: the App's own injection (档 1) and the module's substitution (档 3) were two
 * disconnected channels - picking a point in the App moved the system test provider, while the
 * module kept using whatever was last written into `/data/local/tmp/mockloc.conf`, so the App could
 * say Shanghai and WeChat would still report Beijing.
 *
 * The App cannot write that file (an app process has no write access to /data/local/tmp), so the
 * bridge is: App -> its own private prefs -> whoever has root publishes it as the conf. Two consumers
 * do that today: `tools/mockloc.py sync` from a PC, and the KernelSU module's loop on the phone.
 *
 * `render` is deliberately pure and public so `ConfigCheck.kt` can feed it straight back through
 * `Config.parseInto`: the writer and the reader get checked against each other instead of against a
 * hand-typed sample.
 */
class ModuleBridge(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun publish(lat: Double, lng: Double, label: String) {
        prefs.edit()
            .putString(KEY_LAT, "%.7f".format(lat))
            .putString(KEY_LNG, "%.7f".format(lng))
            .putString(KEY_LABEL, label)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        store()
    }

    fun clear() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        store()
    }

    /**
     * The rendered body is stored alongside the fields so the PC and the root module can copy it
     * verbatim. One renderer (this file) instead of three that can drift apart - the reason the
     * earlier "App picks Shanghai, WeChat shows Beijing" bug could exist at all.
     */
    private fun store() {
        prefs.edit().putString(KEY_CONF, render()).apply()
    }

    fun targets(): List<String> =
        prefs.getString(KEY_TARGETS, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setTargets(list: List<String>) {
        prefs.edit().putString(KEY_TARGETS, list.joinToString(",")).apply()
    }

    fun render(): String = render(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        lat = prefs.getString(KEY_LAT, "0").orEmpty(),
        lng = prefs.getString(KEY_LNG, "0").orEmpty(),
        label = prefs.getString(KEY_LABEL, "").orEmpty(),
        targets = targets(),
        providers = prefs.getString(KEY_PROVIDERS, DEFAULT_PROVIDERS).orEmpty(),
    )

    companion object {
        const val PREFS = "settings"
        const val KEY_ENABLED = "module_enabled"
        const val KEY_LAT = "module_lat"
        const val KEY_LNG = "module_lng"
        const val KEY_LABEL = "module_label"
        const val KEY_TARGETS = "module_targets"
        const val KEY_PROVIDERS = "module_providers"
        const val KEY_CONF = "module_conf"
        const val DEFAULT_PROVIDERS = "gps,network,fused"

        /** The exact body the module reads; keep in sync with Config.kt's key names. */
        fun render(
            enabled: Boolean,
            lat: String,
            lng: String,
            label: String,
            targets: List<String>,
            providers: String = DEFAULT_PROVIDERS,
        ): String = buildString {
            appendLine("MOCKLOC_ENABLED=${if (enabled) "true" else "false"}")
            appendLine("MOCKLOC_GLOBAL=false")
            appendLine("MOCKLOC_PROVIDERS=$providers")
            appendLine("MOCKLOC_LAT=$lat")
            appendLine("MOCKLOC_LNG=$lng")
            appendLine("MOCKLOC_ACC=5.0")
            appendLine("MOCKLOC_RADIUS=0.0")
            appendLine("MOCKLOC_INTERVAL=15")
            appendLine("MOCKLOC_TARGETS=${targets.joinToString(",")}")
            appendLine("MOCKLOC_LABEL=$label")
            appendLine("MOCKLOC_GNSS=true")
            appendLine("MOCKLOC_BLIND_WIFI=false")
            appendLine("MOCKLOC_BLIND_CELL=false")
        }
    }
}
