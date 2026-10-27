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
        store()
    }

    /** The probe is the only way to see what a target app actually calls. */
    fun setProbe(on: Boolean) {
        prefs.edit().putBoolean(KEY_PROBE, on).apply()
        store()
    }

    fun probe(): Boolean = prefs.getBoolean(KEY_PROBE, false)

    /**
     * Blinding WiFi/cell is the switch that actually moves 高德地图's blue dot (2026-10-27: with it
     * on, the dot held at the injected point through a fresh locate, a pull-to-refresh and two
     * minutes; with it off the engine re-derived the real place from its own server lookup). It is
     * also the switch that breaks the target app's own WiFi features, so it stays opt-in - but it
     * has to survive a sync, which means it belongs in the renderer.
     */
    fun setBlind(on: Boolean) {
        prefs.edit().putBoolean(KEY_BLIND, on).apply()
        store()
    }

    fun blind(): Boolean = prefs.getBoolean(KEY_BLIND, false)

    fun render(): String = render(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        lat = prefs.getString(KEY_LAT, "0").orEmpty(),
        lng = prefs.getString(KEY_LNG, "0").orEmpty(),
        label = prefs.getString(KEY_LABEL, "").orEmpty(),
        targets = targets(),
        providers = prefs.getString(KEY_PROVIDERS, DEFAULT_PROVIDERS).orEmpty(),
        probe = prefs.getBoolean(KEY_PROBE, false),
        blind = prefs.getBoolean(KEY_BLIND, false),
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
        const val KEY_PROBE = "module_probe"
        const val KEY_BLIND = "module_blind"
        const val DEFAULT_PROVIDERS = "gps,network,fused"

        /** The exact body the module reads; keep in sync with Config.kt's key names. */
        fun render(
            enabled: Boolean,
            lat: String,
            lng: String,
            label: String,
            targets: List<String>,
            providers: String = DEFAULT_PROVIDERS,
            probe: Boolean = false,
            blind: Boolean = false,
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
            appendLine("MOCKLOC_BLIND_WIFI=${if (blind) "true" else "false"}")
            appendLine("MOCKLOC_BLIND_CELL=${if (blind) "true" else "false"}")
            appendLine("MOCKLOC_PROBE=${if (probe) "true" else "false"}")
        }
    }
}
