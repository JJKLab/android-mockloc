package com.mockloc

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real terrain altitude for a fix. The old code hardcoded 20.0 m, which handed every detecting app
 * a free consistency check: inject Lhasa (≈3650 m) and report 20 m. OpenTopoData serves the same
 * role 全域's motion engine uses (A11), is keyless, and one request per point is fine at human
 * selection cadence. Results cache on disk keyed by a 0.002° cell (~200 m at the equator) - the
 * grid 全域 documents - so re-injecting the same spot costs no network at all.
 *
 * Every failure path returns null and the caller falls back to the old default; a dead elevation
 * service must never block or break injection.
 */
object Elevation {

    private const val ENDPOINT = "https://api.opentopodata.org/v1/aster30m?locations="
    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 2500

    /** 0.002° cache cell, matching the grid the reference implementation documents. */
    private const val CELL_DEG = 0.002

    fun cellKey(lat: Double, lng: Double): String =
        "elev_${Math.floor(lat / CELL_DEG).toInt()}_${Math.floor(lng / CELL_DEG).toInt()}"

    private fun cacheFile(context: Context, key: String): File =
        File(File(context.filesDir, "elev").apply { mkdirs() }, key)

    /** Cached altitude for a point, or null when it was never fetched (never a network call).
     *  A missing cache file is the normal first-run state, so its absence is a null, never an
     *  exception - `readText()` on an absent file was exactly the crash every fresh install hit
     *  on the first 注入 (README C33a). */
    fun cached(context: Context, lat: Double, lng: Double): Double? {
        val file = cacheFile(context, cellKey(lat, lng))
        if (!file.exists()) return null
        return runCatching { file.readText().trim().toDoubleOrNull() }.getOrNull()
    }

    /**
     * Resolve altitude for a point: cache first, then one HTTP round trip. Blocking - call it from
     * a worker thread, never the main thread.
     */
    fun resolve(context: Context, lat: Double, lng: Double): Double? {
        cached(context, lat, lng)?.let { return it }
        val conn = runCatching {
            (URL("$ENDPOINT$lat,$lng").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "mockloc/0.7")
            }
        }.getOrNull() ?: return null
        return runCatching {
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val elevation = JSONObject(body).getJSONArray("results").getJSONObject(0)
                .optDouble("elevation", Double.NaN)
            if (elevation.isNaN()) return@runCatching null
            // One decimal is far below any altitude-consistency check's resolution.
            val rounded = Math.round(elevation * 10.0) / 10.0
            runCatching { cacheFile(context, cellKey(lat, lng)).writeText(rounded.toString()) }
            rounded
        }.getOrNull().also { runCatching { conn.disconnect() } }
    }
}
