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

    @Volatile
    var accuracy: Float = 5f
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
        var nextProviders = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused",
        )
        var nextLabel = ""
        var nextTargets = emptySet<String>()
        var nextGlobal = false
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
                "MOCKLOC_PROVIDERS" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }?.let { nextProviders = it }
                "MOCKLOC_LABEL" -> nextLabel = value
                "MOCKLOC_TARGETS" -> nextTargets = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        }
        enabled = nextEnabled
        centerLat = nextLat
        centerLng = nextLng
        radius = nextRadius
        accuracy = nextAccuracy
        providers = nextProviders
        provider = nextProviders.first()
        global = nextGlobal
        label = nextLabel
        targets = nextTargets
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

    private fun applyJitter() {
        if (radius <= 0.0) {
            currentLat = centerLat
            currentLng = centerLng
            return
        }
        // sqrt keeps the sample uniform over the disc instead of piling up at the centre.
        val dist = radius * kotlin.math.sqrt(Random.nextDouble())
        val bearing = Random.nextDouble() * 2.0 * Math.PI
        val dLat = dist * cos(bearing) / 111132.0
        val dLng = dist * sin(bearing) / (111412.8 * cos(Math.toRadians(centerLat)))
        currentLat = centerLat + dLat
        currentLng = centerLng + dLng
    }
}
