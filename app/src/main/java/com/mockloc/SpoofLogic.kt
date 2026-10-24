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
     */
    fun fakeLocation(original: Location?, provider: String?): Location {
        val name = provider?.takeUnless { it.isEmpty() } ?: Config.provider
        val out = Location(name)
        out.latitude = Config.currentLat
        out.longitude = Config.currentLng
        out.accuracy = Config.accuracy

        if (original != null) {
            // Keep the real fix's timing so recency checks and motion heuristics stay consistent.
            out.time = original.time
            out.elapsedRealtimeNanos = original.elapsedRealtimeNanos
            out.bearing = original.bearing
            out.speed = original.speed
            out.altitude = original.altitude
        } else {
            out.time = System.currentTimeMillis()
            out.elapsedRealtimeNanos = SystemClock.elapsedRealtime() * 1_000_000L
        }
        return out
    }
}
