package com.mockloc

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 / GCJ-02 / BD-09 conversion. Everything the GPS stack receives must be WGS-84, while
 * Amap tiles and search results are GCJ-02 - the offset is ~478 m in Shanghai, enough to fail a
 * 100 m geofence. Public functions take (lat, lng).
 *
 * Mirrors tools/coords.py; both are checked against the same anchors.
 */
object CoordTransform {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        val magic = 1.0 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        val dLatM = (dLat * 180.0) / ((A * (1.0 - EE)) / (magic * sqrtMagic) * PI)
        val dLngM = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + dLatM, lng + dLngM)
    }

    /** Iterative inverse; naive subtraction leaves ~1-5 m of residual error. */
    fun gcj02ToWgs84(lat: Double, lng: Double, iterations: Int = 5): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var wLat = lat
        var wLng = lng
        repeat(iterations) {
            val guess = wgs84ToGcj02(wLat, wLng)
            wLat += lat - guess[0]
            wLng += lng - guess[1]
        }
        return doubleArrayOf(wLat, wLng)
    }

    fun gcj02ToBd09(lat: Double, lng: Double): DoubleArray {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        return doubleArrayOf(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    fun bd09ToGcj02(lat: Double, lng: Double): DoubleArray {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return doubleArrayOf(z * sin(theta), z * cos(theta))
    }

    fun bd09ToWgs84(lat: Double, lng: Double): DoubleArray {
        val gcj = bd09ToGcj02(lat, lng)
        return gcj02ToWgs84(gcj[0], gcj[1])
    }

    /**
     * A tapped Amap tile is GCJ-02; a real GPS fix is WGS-84. Everything downstream of this
     * function speaks WGS-84 only.
     */
    fun toWgs84(lat: Double, lng: Double, datum: String): DoubleArray = when (datum) {
        "gcj02" -> gcj02ToWgs84(lat, lng)
        "bd09" -> bd09ToWgs84(lat, lng)
        else -> doubleArrayOf(lat, lng)
    }

    /** Great-circle distance in metres, used by the acceptance checks and the jitter radius. */
    fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLng - aLng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }
}
