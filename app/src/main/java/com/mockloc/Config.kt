package com.mockloc

import android.location.Location
import android.location.LocationManager
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Spoof settings mirrored into every hooked process.
 *
 * Source of truth is the flat file written by the CLI and the root module; an app process
 * cannot read /data/adb, so the root module publishes it here with the shelldatafile label.
 */
object Config {
    const val DEFAULT_PATH = "/data/local/tmp/mockloc.conf"
    private const val TTL_MS = 1000L

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var centerLat: Double = 0.0
        private set

    @Volatile
    var centerLng: Double = 0.0
        private set

    /** Jitter radius in metres; 0 pins the centre exactly. */
    @Volatile
    var radius: Double = 0.0
        private set

    /**
     * Route mode (REFACTOR-PLAN 2.1): when waypoints and an epoch anchor are present, the "current
     * point" advances along the route at [speedMps] instead of sitting on the centre. The anchor is
     * wall-clock, so the root module's shell loop, the tier-0 provider and every hooked process
     * sample the same position from the same clock.
     */
    @Volatile
    var speedMps: Double = 1.4
        private set

    @Volatile
    var routeWaypoints: List<Route.Pt> = emptyList()
        private set

    /** Epoch ms the route started at; 0 = not running. */
    @Volatile
    var routeStartedMs: Long = 0L
        private set

    @Volatile
    var routeLoop: Boolean = false
        private set

    /**
     * Per-app fixed points (REFACTOR-PLAN 2.2): `MOCKLOC_APP.<pkg>=lat,lng[,acc]`. A listed app
     * reads its own point instead of the global one, so WeChat can sit in Beijing while Meituan
     * sits in Shanghai. Routes do not compose with these - a per-app point is deliberately static.
     */
    @Volatile
    var appPoints: Map<String, DoubleArray> = emptyMap()
        private set

    /** Phase-3 anti-detection surfaces; each is individually switchable and diagnostics-logged. */
    @Volatile
    var stackClean: Boolean = true
        private set

    /** Packages hidden from PackageManager enumeration inside hooked processes. Empty = no hooks. */
    @Volatile
    var hidePkgs: Set<String> = emptySet()
        private set

    @Volatile
    var sensorFreeze: Boolean = false
        private set

    /** true = fabricate a self-consistent AP list; false = return empty (the measured default). */
    @Volatile
    var forgeWifi: Boolean = false
        private set

    @Volatile
    var gmsSpoof: Boolean = true
        private set

    @Volatile
    var accuracy: Float = 5f
        private set

    /**
     * Fix altitude in metres. `-1` means "not pinned": the publisher (App or CLI) is expected to
     * resolve the real terrain value itself, and the config carries whatever it decided.
     */
    @Volatile
    var alt: Double = -1.0
        private set

    /**
     * The root module's own re-injection cadence, decoupled from the in-app provider's interval
     * (the module forks one `cmd` per provider per tick, so it has a different cost profile than
     * an in-process `setTestProviderLocation`). Missing key falls back to 1s, matching what every
     * reference project converged on.
     */
    @Volatile
    var loopIntervalSec: Int = 1
        private set

    @Volatile
    var provider: String = LocationManager.GPS_PROVIDER
        private set

    /** Every provider name the global layer should cover; `network` left real leaks the truth. */
    @Volatile
    var providers: List<String> = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused",
    )
        private set

    /** Whether the root module is also keeping a system-wide test provider alive. */
    @Volatile
    var global: Boolean = false
        private set

    @Volatile
    var label: String = ""
        private set

    /** Empty means "every package the framework put in scope". */
    @Volatile
    var targets: Set<String> = emptySet()
        private set

    /** Fake the satellite view so a mocked gps does not contradict a zero satellite count. */
    @Volatile
    var gnssSpoof: Boolean = true
        private set

    /**
     * Radio-input blinding, per package rather than global: it genuinely breaks the target app's
     * own WiFi features, so the honest default is "only the apps that need it". Measured on
     * 2026-10-27 that this is the layer 高德地图 actually falls for.
     */
    @Volatile
    var blindWifi: Scope = Scope.None
        private set

    @Volatile
    var blindCell: Scope = Scope.None
        private set

    fun blindWifiFor(packageName: String?) = blindWifi.covers(packageName)

    fun blindCellFor(packageName: String?) = blindCell.covers(packageName)

    /** Log which positioning APIs the target app really calls; changes nothing (see Probe.kt). */
    @Volatile
    var probe: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastLoadedAgo: Long = -1L
        private set

    private var lastCheck = 0L
    private var lastMtime = 0L

    /** Cheap on the hook path: only stats the file once per TTL window. */
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < TTL_MS) return
        lastCheck = now
        val file = File(DEFAULT_PATH)
        if (!file.exists()) {
            lastError = "missing $DEFAULT_PATH"
            enabled = false
            return
        }
        val mtime = file.lastModified()
        if (mtime != lastMtime || lastLoadedAgo < 0) {
            lastMtime = mtime
            try {
                parseInto(file.readText())
                lastError = null
            } catch (t: Throwable) {
                lastError = "read failed: ${t.javaClass.simpleName} ${t.message}"
                enabled = false
            }
        }
        lastLoadedAgo = System.currentTimeMillis() - mtime
        if (enabled) applyJitter()
    }

    private fun parseInto(text: String) {
        var nextEnabled = false
        var nextLat = 0.0
        var nextLng = 0.0
        var nextRadius = 0.0
        var nextAccuracy = 5f
        var nextAlt = -1.0
        var nextLoopInterval = 1
        var nextSpeed = 1.4
        var nextRoute = emptyList<Route.Pt>()
        var nextRouteStarted = 0L
        var nextRouteLoop = false
        val nextAppPoints = HashMap<String, DoubleArray>()
        var nextStackClean = true
        val nextHide = LinkedHashSet<String>()
        var nextSensorFreeze = false
        var nextForgeWifi = false
        var nextGms = true
        var nextProviders = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused",
        )
        var nextLabel = ""
        var nextTargets = emptySet<String>()
        var nextGlobal = false
        var nextGnss = true
        var nextBlindWifi = Scope.None
        var nextBlindCell = Scope.None
        var nextProbe = false
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "MOCKLOC_ENABLED" -> nextEnabled = value == "true" || value == "1"
                "MOCKLOC_GLOBAL" -> nextGlobal = value == "true" || value == "1"
                "MOCKLOC_LAT" -> nextLat = value.toDoubleOrNull() ?: nextLat
                "MOCKLOC_LNG" -> nextLng = value.toDoubleOrNull() ?: nextLng
                "MOCKLOC_RADIUS" -> nextRadius = value.toDoubleOrNull() ?: nextRadius
                "MOCKLOC_ACC" -> nextAccuracy = value.toFloatOrNull() ?: nextAccuracy
                "MOCKLOC_ALT" -> nextAlt = value.toDoubleOrNull() ?: nextAlt
                "MOCKLOC_LOOP_INTERVAL" -> nextLoopInterval = value.toIntOrNull()?.coerceAtLeast(1)
                    ?: nextLoopInterval
                "MOCKLOC_PROVIDERS" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { nextProviders = it }
                "MOCKLOC_LABEL" -> nextLabel = value
                "MOCKLOC_TARGETS" -> nextTargets = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                // Read once at attach time: the hooks they gate are installed per process, not live.
                "MOCKLOC_GNSS" -> nextGnss = value != "false" && value != "0"
                "MOCKLOC_BLIND_WIFI" -> nextBlindWifi = Scope.parse(value)
                "MOCKLOC_BLIND_CELL" -> nextBlindCell = Scope.parse(value)
                "MOCKLOC_PROBE" -> nextProbe = value == "true" || value == "1"
                "MOCKLOC_SPEED_MPS" -> nextSpeed = value.toDoubleOrNull()?.coerceIn(0.1, 60.0) ?: nextSpeed
                "MOCKLOC_ROUTE_WAYPOINTS" -> nextRoute = Route.decode(value)
                "MOCKLOC_ROUTE_STARTED" -> nextRouteStarted = value.toLongOrNull() ?: nextRouteStarted
                "MOCKLOC_ROUTE_LOOP" -> nextRouteLoop = value == "true" || value == "1"
                "MOCKLOC_STACK_CLEAN" -> nextStackClean = value != "false" && value != "0"
                "MOCKLOC_SENSOR_FREEZE" -> nextSensorFreeze = value == "true" || value == "1"
                "MOCKLOC_FORGE_WIFI" -> nextForgeWifi = value == "true" || value == "1"
                "MOCKLOC_GMS" -> nextGms = value != "false" && value != "0"
                "MOCKLOC_HIDE_PKGS" -> value.split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }.forEach { nextHide.add(it) }
                else -> {
                    // Per-app fixed points: MOCKLOC_APP.<pkg>=lat,lng[,acc]. The key contains a dot
                    // by design, which the indexOf('=') split above handles natively.
                    if (key.startsWith("MOCKLOC_APP.")) {
                        val pkg = key.removePrefix("MOCKLOC_APP.")
                        val parts = value.split(',').map { it.trim().toDoubleOrNull() }
                        val lat = parts.getOrNull(0)
                        val lng = parts.getOrNull(1)
                        if (pkg.isNotEmpty() && lat != null && lng != null) {
                            nextAppPoints[pkg] = doubleArrayOf(lat, lng, parts.getOrNull(2) ?: 5.0)
                        }
                    }
                }
            }
        }
        enabled = nextEnabled
        centerLat = nextLat
        centerLng = nextLng
        radius = nextRadius
        accuracy = nextAccuracy
        alt = nextAlt
        loopIntervalSec = nextLoopInterval
        speedMps = nextSpeed
        routeWaypoints = nextRoute
        routeStartedMs = nextRouteStarted
        routeLoop = nextRouteLoop
        appPoints = nextAppPoints
        stackClean = nextStackClean
        hidePkgs = nextHide
        sensorFreeze = nextSensorFreeze
        forgeWifi = nextForgeWifi
        gmsSpoof = nextGms
        providers = nextProviders
        provider = nextProviders.first()
        global = nextGlobal
        label = nextLabel
        targets = nextTargets
        gnssSpoof = nextGnss
        blindWifi = nextBlindWifi
        blindCell = nextBlindCell
        probe = nextProbe
    }

    fun shouldSpoof(packageName: String?): Boolean {
        if (!enabled) return false
        if (packageName.isNullOrEmpty()) return targets.isEmpty()
        return targets.isEmpty() || packageName in targets
    }

    /** Re-jittered once per refresh window so a stationary target still looks like a live fix. */
    @Volatile
    var currentLat: Double = 0.0
        private set

    @Volatile
    var currentLng: Double = 0.0
        private set

    /** The route is live only when there is a path AND a start anchor to sample from. */
    val routeActive: Boolean
        get() = routeWaypoints.size >= 2 && routeStartedMs > 0L

    /**
     * Speed over ground and course the route engine is currently producing; zero when parked.
     * The NMEA and GNSS-rate layers read these so their story matches the position's.
     */
    @Volatile
    var currentSpeedMps: Double = 0.0
        private set

    @Volatile
    var currentBearingDeg: Double = 0.0
        private set

    // One shared jitter offset per refresh: a per-app point and the global point then wobble
    // together instead of each running its own random walk.
    @Volatile
    private var jitterDLat = 0.0

    @Volatile
    private var jitterDLng = 0.0

    private fun applyJitter() {
        var baseLat = centerLat
        var baseLng = centerLng
        // Route mode replaces the centre with the position along the route (REFACTOR-PLAN 2.1);
        // the anchor is wall-clock, so tier-0/2/3 all land on the same spot at the same second.
        if (routeActive) {
            val s = Route.sampleElapsed(
                routeWaypoints, speedMps, routeLoop, routeStartedMs, System.currentTimeMillis(),
            )
            if (s != null) {
                baseLat = s.lat
                baseLng = s.lng
                currentSpeedMps = s.speedMps
                currentBearingDeg = s.bearingDeg
            } else {
                currentSpeedMps = 0.0
                currentBearingDeg = 0.0
            }
        } else {
            currentSpeedMps = 0.0
            currentBearingDeg = 0.0
        }
        if (radius <= 0.0) {
            jitterDLat = 0.0
            jitterDLng = 0.0
        } else {
            // sqrt keeps the sample uniform over the disc instead of piling up at the centre.
            val dist = radius * kotlin.math.sqrt(Random.nextDouble())
            val bearing = Random.nextDouble() * 2.0 * Math.PI
            jitterDLat = dist * cos(bearing) / 111132.0
            jitterDLng = dist * sin(bearing) / (111412.8 * cos(Math.toRadians(centerLat)))
        }
        currentLat = baseLat + jitterDLat
        currentLng = baseLng + jitterDLng
    }

    /** A per-app fixed point overrides the global one entirely (REFACTOR-PLAN 2.2). */
    fun appPointFor(pkg: String?): DoubleArray? = pkg?.let { appPoints[it] }

    fun currentLatFor(pkg: String?): Double = appPointFor(pkg)?.get(0)?.plus(jitterDLat) ?: currentLat

    fun currentLngFor(pkg: String?): Double = appPointFor(pkg)?.get(1)?.plus(jitterDLng) ?: currentLng

    fun accuracyFor(pkg: String?): Float {
        val acc = appPointFor(pkg)?.getOrNull(2)?.toFloat() ?: return accuracy
        return if (acc > 0f) acc else accuracy
    }
}

/**
 * Who a per-package layer applies to. Three states because two are not enough: "off", "everything
 * LSPosed gave us", and "only these apps" are all things someone actually wants, and an empty list
 * cannot tell the last two apart.
 *
 * Wire format is one field: `false`, `true`, or a comma-separated package list.
 */
class Scope private constructor(val mode: Mode, val packages: Set<String>) {
    enum class Mode { NONE, ALL, ONLY }

    fun covers(packageName: String?): Boolean = when (mode) {
        Mode.NONE -> false
        Mode.ALL -> true
        Mode.ONLY -> packageName != null && packageName in packages
    }

    /** True for any app at all - what the status page shows, not what a hook decides. */
    val enabled: Boolean get() = mode != Mode.NONE

    fun render(): String = when (mode) {
        Mode.NONE -> "false"
        Mode.ALL -> "true"
        Mode.ONLY -> packages.joinToString(",")
    }

    override fun toString(): String = render()

    companion object {
        val None = Scope(Mode.NONE, emptySet())
        val All = Scope(Mode.ALL, emptySet())

        fun only(packages: Collection<String>): Scope =
            packages.filter { it.isNotBlank() }.toSet().let { if (it.isEmpty()) None else Scope(Mode.ONLY, it) }

        fun parse(value: String): Scope {
            val text = value.trim()
            if (text.isEmpty() || text == "false" || text == "0") return None
            if (text == "true" || text == "1" || text == "*") return All
            return only(text.split(','))
        }
    }
}
