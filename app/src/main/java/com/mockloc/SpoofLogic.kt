package com.mockloc

import android.location.Location
import android.os.SystemClock

/**
 * Decisions shared by both hook backends, so the libxposed and legacy paths can never drift
 * apart in behaviour — only in plumbing.
 */
object SpoofLogic {

    fun isActive(packageName: String?): Boolean {
        Config.refresh()
        return Config.shouldSpoof(packageName)
    }

    /**
     * Builds the Location handed to pull-style APIs. A freshly constructed Location carries the
     * mock bit as false by definition, which is why this path needs no `setMock` reflection.
     *
     * Speed and course come from the config's current story (route mode feeds real values, parked
     * feeds zero) rather than the original fix's - a static position reporting 5 m/s of real
     * motion was one more inconsistency a detector could read for free.
     */
    fun fakeLocation(original: Location?, provider: String?, pkg: String? = null): Location {
        val name = provider?.takeUnless { it.isEmpty() } ?: Config.provider
        val out = Location(name)
        out.latitude = Config.currentLatFor(pkg)
        out.longitude = Config.currentLngFor(pkg)
        out.accuracy = Config.accuracyFor(pkg)
        out.speed = Config.currentSpeedMps.toFloat()
        out.bearing = Config.currentBearingDeg.toFloat()

        if (original != null) {
            // Keep the real fix's timing so recency checks and motion heuristics stay consistent.
            out.time = original.time
            out.elapsedRealtimeNanos = original.elapsedRealtimeNanos
            // Altitude: a pinned value wins; otherwise the original's real terrain value is the
            // least-contradicting choice (the App-side publisher resolves real terrain itself,
            // REFACTOR-PLAN 1.5).
            if (Config.alt >= 0.0) out.altitude = Config.alt else out.altitude = original.altitude
        } else {
            out.time = System.currentTimeMillis()
            out.elapsedRealtimeNanos = SystemClock.elapsedRealtime() * 1_000_000L
            if (Config.alt >= 0.0) out.altitude = Config.alt
        }
        return out
    }
}
