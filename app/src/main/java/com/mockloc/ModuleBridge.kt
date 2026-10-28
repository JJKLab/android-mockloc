package com.mockloc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The App's side of the config contract, and the single place that knows its text format.
 *
 * Why this exists: the App's own injection (档 1) and the module's substitution (档 3) were two
 * disconnected channels - picking a point in the App moved the system test provider, while the
 * module kept using whatever was last written into `/data/local/tmp/mockloc.conf`, so the App could
 * say Shanghai and WeChat would still report Beijing.
 *
 * The App cannot write that file (an app process has no write access to /data/local/tmp), so the
 * bridge is: App -> its own private prefs -> whoever has root publishes it as the conf. Three
 * consumers do that today: `tools/mockloc.py sync` from a PC, the KernelSU module's loop on the
 * phone, and [RootInjector] straight from the App when su is granted.
 *
 * `render` is deliberately pure and public so `ConfigCheck.kt` can feed it straight back through
 * `Config.parseInto`: the writer and the reader get checked against each other instead of against a
 * hand-typed sample. Every key the parser reads has a line here - a missing line is how the probe
 * and the blind scopes got silently reset by old syncs (README C30).
 */
class ModuleBridge(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun publish(lat: Double, lng: Double, label: String, global: Boolean = false) {
        prefs.edit()
            .putString(KEY_LAT, "%.7f".format(lat))
            .putString(KEY_LNG, "%.7f".format(lng))
            .putString(KEY_LABEL, label)
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_GLOBAL, global)
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
     * minutes; with it off the engine re-derived the real place from its own server lookup). It
     * breaks the host app's own WiFi features, so it is scoped per package - and either way it has
     * to survive a sync, which means it belongs in the renderer.
     */
    fun blindScope(): Scope = Scope.parse(prefs.getString(KEY_BLIND, "false").orEmpty())

    fun setBlindScope(scope: Scope) {
        prefs.edit().putString(KEY_BLIND, scope.render()).apply()
        store()
    }

    /** Convenience for the "blind everything in scope" case. */
    fun setBlind(on: Boolean) = setBlindScope(if (on) Scope.All else Scope.None)

    fun blind(): Boolean = blindScope().enabled

    // ---------------------------------------------------------------- route (REFACTOR-PLAN 2.1/2.3)

    /** Starts (or restarts) the route clock: the epoch anchor every consumer samples from. */
    fun setRoute(waypoints: List<Route.Pt>, speedMps: Double, loop: Boolean, startedMs: Long) {
        prefs.edit()
            .putString(KEY_ROUTE_WPTS, Route.encode(waypoints))
            .putString(KEY_SPEED, "%.1f".format(speedMps.coerceIn(0.1, 60.0)))
            .putBoolean(KEY_ROUTE_LOOP, loop)
            .putString(KEY_ROUTE_STARTED, startedMs.toString())
            .apply()
        store()
    }

    /** Stopping a route parks the conf back on the centre point rather than freezing mid-path. */
    fun stopRoute() {
        prefs.edit().putString(KEY_ROUTE_STARTED, "0").apply()
        store()
    }

    fun routeWaypoints(): List<Route.Pt> = Route.decode(prefs.getString(KEY_ROUTE_WPTS, "").orEmpty())

    fun routeSpeed(): Double = prefs.getString(KEY_SPEED, "1.4").orEmpty().toDoubleOrNull() ?: 1.4

    fun routeLoop(): Boolean = prefs.getBoolean(KEY_ROUTE_LOOP, false)

    fun routeStarted(): Long = prefs.getString(KEY_ROUTE_STARTED, "0").orEmpty().toLongOrNull() ?: 0L

    fun routeRunning(): Boolean = routeStarted() > 0L && routeWaypoints().size >= 2

    // ---------------------------------------------------------------- per-app points (2.2)

    fun appPoints(): Map<String, DoubleArray> {
        val raw = prefs.getString(KEY_APP_POINTS, "{}").orEmpty()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, DoubleArray>()
        obj.keys().forEach { pkg ->
            val arr = obj.optJSONArray(pkg) ?: return@forEach
            if (arr.length() >= 2) {
                out[pkg] = doubleArrayOf(
                    arr.optDouble(0, 0.0), arr.optDouble(1, 0.0), arr.optDouble(2, 5.0),
                )
            }
        }
        return out
    }

    fun setAppPoint(pkg: String, lat: Double, lng: Double) = editAppPoints {
        it.put(pkg, JSONArray().put(lat).put(lng).put(5.0))
    }

    fun clearAppPoint(pkg: String) = editAppPoints { it.remove(pkg) }

    private inline fun editAppPoints(edit: (JSONObject) -> Unit) {
        val obj = runCatching {
            JSONObject(prefs.getString(KEY_APP_POINTS, "{}").orEmpty())
        }.getOrDefault(JSONObject())
        edit(obj)
        prefs.edit().putString(KEY_APP_POINTS, obj.toString()).apply()
        store()
    }

    // ---------------------------------------------------------------- phase-3 switches

    fun stackClean(): Boolean = prefs.getBoolean(KEY_STACK_CLEAN, true)

    fun setStackClean(on: Boolean) {
        prefs.edit().putBoolean(KEY_STACK_CLEAN, on).apply()
        store()
    }

    fun hidePkgs(): List<String> =
        prefs.getString(KEY_HIDE_PKGS, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setHidePkgs(list: List<String>) {
        prefs.edit().putString(KEY_HIDE_PKGS, list.joinToString(",")).apply()
        store()
    }

    fun sensorFreeze(): Boolean = prefs.getBoolean(KEY_SENSOR_FREEZE, false)

    fun setSensorFreeze(on: Boolean) {
        prefs.edit().putBoolean(KEY_SENSOR_FREEZE, on).apply()
        store()
    }

    fun forgeWifi(): Boolean = prefs.getBoolean(KEY_FORGE_WIFI, false)

    fun setForgeWifi(on: Boolean) {
        prefs.edit().putBoolean(KEY_FORGE_WIFI, on).apply()
        store()
    }

    fun gmsSpoof(): Boolean = prefs.getBoolean(KEY_GMS, true)

    fun setGms(on: Boolean) {
        prefs.edit().putBoolean(KEY_GMS, on).apply()
        store()
    }

    // ---------------------------------------------------------------- injection parameters

    /**
     * Injection parameters the App side owns (tier-0 provider and the rendered conf share them).
     * `altMeters = -1` keeps "resolve at publish time" (Elevation); any other value pins it.
     */
    fun setInjectionParams(acc: Float, radius: Double, intervalSec: Int, altMeters: Double) {
        prefs.edit()
            .putString(KEY_ACC, "%.1f".format(acc))
            .putString(KEY_RADIUS, "%.1f".format(radius))
            .putString(KEY_INTERVAL, intervalSec.coerceAtLeast(1).toString())
            .putString(KEY_ALT, "%.1f".format(altMeters))
            .apply()
        store()
    }

    fun injectionIntervalSec(): Int =
        prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL).orEmpty().toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun injectionAcc(): Float =
        prefs.getString(KEY_ACC, DEFAULT_ACC).orEmpty().toFloatOrNull() ?: 5f

    fun injectionRadius(): Double =
        prefs.getString(KEY_RADIUS, DEFAULT_RADIUS).orEmpty().toDoubleOrNull() ?: 0.0

    /** -1 = resolve real terrain at publish time (see [Elevation]). */
    fun injectionAlt(): Double =
        prefs.getString(KEY_ALT, DEFAULT_ALT).orEmpty().toDoubleOrNull() ?: -1.0

    /** Whether the current App pick should be maintained globally by the root module loop. */
    fun globalFlag(): Boolean = prefs.getBoolean(KEY_GLOBAL, false)

    fun render(): String = render(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        lat = prefs.getString(KEY_LAT, "0").orEmpty(),
        lng = prefs.getString(KEY_LNG, "0").orEmpty(),
        label = prefs.getString(KEY_LABEL, "").orEmpty(),
        targets = targets(),
        providers = prefs.getString(KEY_PROVIDERS, DEFAULT_PROVIDERS).orEmpty(),
        probe = prefs.getBoolean(KEY_PROBE, false),
        blind = blindScope(),
        global = prefs.getBoolean(KEY_GLOBAL, false),
        acc = prefs.getString(KEY_ACC, DEFAULT_ACC).orEmpty(),
        radius = prefs.getString(KEY_RADIUS, DEFAULT_RADIUS).orEmpty(),
        interval = prefs.getString(KEY_INTERVAL, DEFAULT_INTERVAL).orEmpty(),
        alt = prefs.getString(KEY_ALT, DEFAULT_ALT).orEmpty(),
        speed = prefs.getString(KEY_SPEED, DEFAULT_SPEED).orEmpty(),
        routeWaypoints = prefs.getString(KEY_ROUTE_WPTS, "").orEmpty(),
        routeStarted = prefs.getString(KEY_ROUTE_STARTED, "0").orEmpty(),
        routeLoop = prefs.getBoolean(KEY_ROUTE_LOOP, false),
        appPoints = appPoints().map { (pkg, point) ->
            pkg to "%.7f,%.7f,%.1f".format(point[0], point[1], point.getOrElse(2) { 5.0 })
        },
        stackClean = prefs.getBoolean(KEY_STACK_CLEAN, true),
        hidePkgs = hidePkgs(),
        sensorFreeze = prefs.getBoolean(KEY_SENSOR_FREEZE, false),
        forgeWifi = prefs.getBoolean(KEY_FORGE_WIFI, false),
        gms = prefs.getBoolean(KEY_GMS, true),
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
        const val KEY_GLOBAL = "module_global"
        const val KEY_ACC = "module_acc"
        const val KEY_RADIUS = "module_radius"
        const val KEY_INTERVAL = "module_interval"
        const val KEY_ALT = "module_alt"
        const val KEY_SPEED = "module_speed"
        const val KEY_ROUTE_WPTS = "module_route"
        const val KEY_ROUTE_STARTED = "module_route_started"
        const val KEY_ROUTE_LOOP = "module_route_loop"
        const val KEY_APP_POINTS = "module_app_points"
        const val KEY_STACK_CLEAN = "module_stack_clean"
        const val KEY_HIDE_PKGS = "module_hide_pkgs"
        const val KEY_SENSOR_FREEZE = "module_sensor_freeze"
        const val KEY_FORGE_WIFI = "module_forge_wifi"
        const val KEY_GMS = "module_gms"
        const val DEFAULT_PROVIDERS = "gps,network,fused"

        // Rendered defaults stay byte-identical to what previous builds produced for the values
        // they used to hardcode — except INTERVAL, which was 15 in the conf while MockService
        // pushed at 1s. The two sides now share one number and 1s is the measured default.
        const val DEFAULT_ACC = "5.0"
        const val DEFAULT_RADIUS = "0.0"
        const val DEFAULT_INTERVAL = "1"
        const val DEFAULT_ALT = "-1.0"
        const val DEFAULT_SPEED = "1.4"

        /** The exact body the module reads; keep in sync with Config.kt's key names. */
        fun render(
            enabled: Boolean,
            lat: String,
            lng: String,
            label: String,
            targets: List<String>,
            providers: String = DEFAULT_PROVIDERS,
            probe: Boolean = false,
            blind: Scope = Scope.None,
            global: Boolean = false,
            acc: String = DEFAULT_ACC,
            radius: String = DEFAULT_RADIUS,
            interval: String = DEFAULT_INTERVAL,
            alt: String = DEFAULT_ALT,
            speed: String = DEFAULT_SPEED,
            routeWaypoints: String = "",
            routeStarted: String = "0",
            routeLoop: Boolean = false,
            appPoints: List<Pair<String, String>> = emptyList(),
            stackClean: Boolean = true,
            hidePkgs: List<String> = emptyList(),
            sensorFreeze: Boolean = false,
            forgeWifi: Boolean = false,
            gms: Boolean = true,
        ): String = buildString {
            appendLine("MOCKLOC_ENABLED=${if (enabled) "true" else "false"}")
            appendLine("MOCKLOC_GLOBAL=${if (global) "true" else "false"}")
            appendLine("MOCKLOC_PROVIDERS=$providers")
            appendLine("MOCKLOC_LAT=$lat")
            appendLine("MOCKLOC_LNG=$lng")
            appendLine("MOCKLOC_ACC=${if (acc.isBlank()) DEFAULT_ACC else acc}")
            appendLine("MOCKLOC_RADIUS=${if (radius.isBlank()) DEFAULT_RADIUS else radius}")
            appendLine("MOCKLOC_ALT=${if (alt.isBlank()) DEFAULT_ALT else alt}")
            appendLine("MOCKLOC_INTERVAL=${if (interval.isBlank()) DEFAULT_INTERVAL else interval}")
            appendLine("MOCKLOC_LOOP_INTERVAL=${if (interval.isBlank()) DEFAULT_INTERVAL else interval}")
            appendLine("MOCKLOC_TARGETS=${targets.joinToString(",")}")
            appendLine("MOCKLOC_LABEL=$label")
            appendLine("MOCKLOC_GNSS=true")
            appendLine("MOCKLOC_BLIND_WIFI=${blind.render()}")
            appendLine("MOCKLOC_BLIND_CELL=${blind.render()}")
            appendLine("MOCKLOC_PROBE=${if (probe) "true" else "false"}")
            appendLine("MOCKLOC_SPEED_MPS=${if (speed.isBlank()) DEFAULT_SPEED else speed}")
            appendLine("MOCKLOC_ROUTE_WAYPOINTS=$routeWaypoints")
            appendLine("MOCKLOC_ROUTE_STARTED=${if (routeStarted.isBlank()) "0" else routeStarted}")
            appendLine("MOCKLOC_ROUTE_LOOP=$routeLoop")
            appendLine("MOCKLOC_STACK_CLEAN=$stackClean")
            appendLine("MOCKLOC_HIDE_PKGS=${hidePkgs.joinToString(",")}")
            appendLine("MOCKLOC_SENSOR_FREEZE=$sensorFreeze")
            appendLine("MOCKLOC_FORGE_WIFI=$forgeWifi")
            appendLine("MOCKLOC_GMS=$gms")
            appPoints.forEach { (pkg, point) -> appendLine("MOCKLOC_APP.$pkg=$point") }
        }
    }
}
