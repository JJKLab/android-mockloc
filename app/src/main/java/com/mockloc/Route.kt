package com.mockloc

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route engine: waypoints in, time-parameterised positions out. Pure Kotlin on purpose - the whole
 * progression is checked on a plain JVM by `tools/kotlin/RouteCheck.kt` (REFACTOR-PLAN 2.1), the
 * same discipline as the coordinate and NMEA layers.
 *
 * The path is a Catmull-Rom spline through the waypoints, densely sampled once into a polyline with
 * cumulative distances; progression then walks that polyline at constant speed. Every consumer
 * (tier-0 provider, the module's config sampling, the root module's shell loop) derives its position
 * from the same epoch anchor (`MOCKLOC_ROUTE_STARTED`), so two layers cannot disagree about where
 * along the route "now" is.
 */
object Route {

    data class Pt(val lat: Double, val lng: Double)

    data class Sample(val lat: Double, val lng: Double, val bearingDeg: Double, val speedMps: Double)

    /** Dense polyline plus cumulative distance at each node. */
    class Path(val pts: List<Pt>, val cum: List<Double>) {
        val totalMeters: Double get() = cum.last()
    }

    private const val SAMPLES_PER_SEGMENT = 24

    /** Metres per degree, the same equirectangular scaling the jitter math already uses. */
    private const val METERS_PER_DEG_LAT = 111_132.0

    private fun metersPerDegLng(lat: Double) = 111_412.8 * cos(Math.toRadians(lat))

    private fun distMeters(a: Pt, b: Pt): Double {
        val dLat = (b.lat - a.lat) * METERS_PER_DEG_LAT
        val dLng = (b.lng - a.lng) * metersPerDegLng((a.lat + b.lat) / 2.0)
        return sqrt(dLat * dLat + dLng * dLng)
    }

    /** Initial bearing a→b in degrees, [0,360). */
    fun bearingDeg(a: Pt, b: Pt): Double {
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2Compat(y, x)) + 360.0) % 360.0
    }

    /** java.lang.Math.atan2 via kotlin: keep the import surface small and JVM-only. */
    private fun atan2Compat(y: Double, x: Double): Double = Math.atan2(y, x)

    /**
     * Catmull-Rom through the waypoints (endpoints duplicated so the first and last segments stay
     * smooth), sampled into a walkable polyline. Fewer than two waypoints yields a degenerate path
     * the callers treat as "stand still".
     */
    fun build(waypoints: List<Pt>, samplesPerSegment: Int = SAMPLES_PER_SEGMENT): Path? {
        if (waypoints.size < 2) return null
        val pts = ArrayList<Pt>()
        var i = 0
        while (i < waypoints.size - 1) {
            val p0 = waypoints[(i - 1).coerceAtLeast(0)]
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val p3 = waypoints[(i + 2).coerceAtMost(waypoints.size - 1)]
            for (s in 0 until samplesPerSegment) {
                pts.add(catmull(p0, p1, p2, p3, s.toDouble() / samplesPerSegment))
            }
            i++
        }
        pts.add(waypoints.last())
        val cum = ArrayList<Double>(pts.size)
        var acc = 0.0
        cum.add(0.0)
        for (k in 1 until pts.size) {
            acc += distMeters(pts[k - 1], pts[k])
            cum.add(acc)
        }
        return Path(pts, cum)
    }

    /** Uniform Catmull-Rom (tension 0.5); passes exactly through p1 and p2. */
    private fun catmull(p0: Pt, p1: Pt, p2: Pt, p3: Pt, t: Double): Pt {
        val t2 = t * t
        val t3 = t2 * t
        fun term(a: Double, b: Double, c: Double, d: Double) =
            0.5 * ((2.0 * b) + (-a + c) * t + (2.0 * a - 5.0 * b + 4.0 * c - d) * t2 +
                (-a + 3.0 * b - 3.0 * c + d) * t3)
        return Pt(term(p0.lat, p1.lat, p2.lat, p3.lat), term(p0.lng, p1.lng, p2.lng, p3.lng))
    }

    /** Position and heading at a distance along the path; clamped at both ends. */
    fun sampleAtDistance(path: Path, distanceMeters: Double): Sample {
        val pts = path.pts
        val cum = path.cum
        val dist = distanceMeters.coerceIn(0.0, path.totalMeters)
        // Binary search for the segment containing dist.
        var lo = 0
        var hi = cum.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= dist) lo = mid else hi = mid
        }
        val a = pts[lo]
        val b = pts[hi]
        val segLen = (cum[hi] - cum[lo]).coerceAtLeast(1e-9)
        val f = ((dist - cum[lo]) / segLen).coerceIn(0.0, 1.0)
        val bearing = if (dist + 1e-6 >= path.totalMeters && lo > 0) {
            bearingDeg(pts[lo - 1], pts[lo])
        } else {
            bearingDeg(a, b)
        }
        return Sample(
            lat = a.lat + (b.lat - a.lat) * f,
            lng = a.lng + (b.lng - a.lng) * f,
            bearingDeg = bearing,
            speedMps = 0.0, // filled by the caller, which knows the configured speed
        )
    }

    /**
     * Position at wall-clock [nowMillis] given the epoch anchor [startedMillis]. Loop mode wraps
     * modulo the total traversal time; non-loop mode parks at the end (a spoofer that silently
     * teleports back to the start on finish is its own tell).
     */
    fun sampleElapsed(
        waypoints: List<Pt>,
        speedMps: Double,
        loop: Boolean,
        startedMillis: Long,
        nowMillis: Long,
    ): Sample? {
        if (waypoints.size < 2 || speedMps <= 0.0 || startedMillis <= 0L) return null
        val path = build(waypoints) ?: return null
        val elapsedSec = ((nowMillis - startedMillis).coerceAtLeast(0L)) / 1000.0
        var dist = speedMps * elapsedSec
        if (loop) {
            val total = path.totalMeters.coerceAtLeast(1e-6)
            dist %= total
        } else {
            dist = min(dist, path.totalMeters)
        }
        val at = sampleAtDistance(path, dist)
        // Speed reported in the fix is the configured one, not the chord length of the last tick.
        return at.copy(speedMps = speedMps)
    }

    /** Conveniences for the wire format: `lat,lng;lat,lng;...` */
    fun encode(waypoints: List<Pt>): String =
        waypoints.joinToString(";") { "${"%.7f".format(it.lat)},${"%.7f".format(it.lng)}" }

    fun decode(text: String): List<Pt> = text.split(';').mapNotNull { token ->
        val parts = token.split(',')
        val a = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
        val b = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
        // Accept either order within a pair: what people paste is often lng first.
        if (abs(a) <= 90.0) Pt(a, b) else Pt(b, a)
    }
}
