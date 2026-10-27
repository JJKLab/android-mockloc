package com.mockloc

import java.util.Calendar
import java.util.Locale

/**
 * NMEA 0183 sentence construction, kept free of framework types so it can be run and checked on a
 * plain JVM (see `tools/kotlin/NmeaCheck.kt`) instead of only on a phone.
 *
 * The framing rules that matter: `$<talker+id>,<field>,<field>*<two-hex-digit checksum>`, where the
 * checksum is the XOR of every character between `$` and `*` inclusive of neither, and the star
 * terminates the payload rather than separating the id from the fields.
 */
object Nmea {

    /** Latitude/longitude as `ddmm.mmmm,N`, the form NMEA uses instead of decimal degrees. */
    fun dmm(value: Double, positive: Char, negative: Char): String {
        val hemisphere = if (value >= 0) positive else negative
        val absolute = kotlin.math.abs(value)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60.0
        return String.format(Locale.ROOT, "%02d%07.4f,%c", degrees, minutes, hemisphere)
    }

    fun frame(id: String, body: String): String {
        val payload = "$id,$body"
        var sum = 0
        for (char in payload) sum = sum xor char.code
        return String.format(Locale.ROOT, "$%s*%02X", payload, sum)
    }

    /**
     * GGA (position + fix quality), RMC (position + speed + date), GSA (satellites used and DOP),
     * GLL (lat/lon/time), GST (pseudo-range error). A stationary receiver reports zero speed and
     * course, which is what lets a consumer cross-check the measurement stream's zero Doppler.
     */
    fun sentences(
        latitude: Double,
        longitude: Double,
        utcMillis: Long,
        satellites: List<Sky.Sat>,
        altitudeMeters: Double = 4.0,
        hdop: Double = 0.9,
    ): List<String> {
        val cal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        val time = String.format(
            Locale.ROOT, "%02d%02d%02d.5",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
        val date = String.format(
            Locale.ROOT, "%02d%02d%02d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100,
        )
        val lat = dmm(latitude, 'N', 'S')
        val lon = dmm(longitude, 'E', 'W')
        val used = satellites.size
        val prns = satellites.joinToString(",") { String.format(Locale.ROOT, "%02d", it.svid) }
        val slots = prns + ",,".repeat((12 - used).coerceAtLeast(0))
        val hdopText = String.format(Locale.ROOT, "%.1f", hdop)
        return listOf(
            frame("GPGGA", "$time,$lat,$lon,1,$used,$hdopText," +
                String.format(Locale.ROOT, "%.1f", altitudeMeters) + ",M,-32.0,M,,"),
            frame("GPRMC", "$time,A,$lat,$lon,0.0,0.0,$date,0.0,E"),
            frame("GPGSA", "A,3,$slots,1.6,$hdopText,1.3"),
            frame("GPGLL", "$lat,$lon,$time,A"),
            frame("GPGST", "$time,0.8,$hdopText,$hdopText,,,,,"),
        )
    }
}
