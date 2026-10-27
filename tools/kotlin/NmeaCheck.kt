import com.mockloc.Nmea
import com.mockloc.Sky
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Standalone acceptance check for the NMEA layer and the satellite geometry. Not part of the APK.
 *
 * The point is to catch framing mistakes on a PC instead of on a phone: the first on-device sample
 * put the checksum star between the sentence id and its fields, which no parser accepts, and that is
 * exactly the class of bug a printed sample plus these assertions would have caught earlier.
 */
private var fails = 0

private fun check(label: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"} $label${if (detail.isEmpty()) "" else ": $detail"}")
    if (!ok) fails++
}

/** NMEA checksum: XOR of every character between '$' and '*', neither included. */
private fun expectedChecksum(payload: String): String {
    var sum = 0
    for (c in payload) sum = sum xor c.code
    return "%02X".format(sum)
}

/** Parse NMEA's ddmm.mmmm hemisphere form back into decimal degrees. */
private fun parseDmm(value: String, hemisphere: String): Double {
    val degrees = value.substring(0, value.length - 7).toDouble()
    val minutes = value.substring(value.length - 7).toDouble()
    val sign = if (hemisphere == "S" || hemisphere == "W") -1.0 else 1.0
    return sign * (degrees + minutes / 60.0)
}

fun main() {
    val lat = 31.2229441
    val lon = 121.4644122
    val now = 1_764_000_000_000L

    // ---- framing ----
    val framed = Nmea.frame("GPGGA", "1,2")
    check("frame 用逗号分隔语句号与字段", framed == "\$GPGGA,1,2*${expectedChecksum("GPGGA,1,2")}", framed)
    check("frame 只有一个 *", framed.count { it == '*' } == 1)

    // ---- dmm ----
    check("dmm 北纬", Nmea.dmm(lat, 'N', 'S') == "3113.3766,N", Nmea.dmm(lat, 'N', 'S'))
    check("dmm 南纬", Nmea.dmm(-33.86, 'N', 'S') == "3351.6000,S", Nmea.dmm(-33.86, 'N', 'S'))
    check("dmm 东经", Nmea.dmm(lon, 'E', 'W') == "12127.8647,E", Nmea.dmm(lon, 'E', 'W'))

    // ---- the whole set, checked the way a consumer would read it ----
    val sentences = Nmea.sentences(lat, lon, now, Sky.SATS)
    check("句子数量 5", sentences.size == 5)
    var framingOk = true
    var roundTripOk = true
    var ggaFieldsOk = true
    var rmcValidOk = true
    for (raw in sentences) {
        val id = raw.substringBefore('*').substringAfter('$').substringBefore(',')
        val starAt = raw.indexOf('*')
        val checksum = raw.substring(starAt + 1)
        val payload = raw.substring(1, starAt)
        if (raw.first() != '$' || starAt < 0 || checksum != expectedChecksum(payload)) {
            println("  校验和/框架不对: $raw")
            framingOk = false
        }
        val fields = payload.substringAfter(',').split(',')
        if (id == "GPGGA") {
            if (fields.size != 14) {
                println("  GGA 字段数 ${fields.size}，应为 14")
                ggaFieldsOk = false
            }
            val parsedLat = parseDmm(fields[1], fields[2])
            val parsedLon = parseDmm(fields[3], fields[4])
            if (abs(parsedLat - lat) > 1e-4 || abs(parsedLon - lon) > 1e-4) {
                println("  GGA 读回 $parsedLat,$parsedLon 与写入 $lat,$lon 不一致")
                roundTripOk = false
            }
            if (fields[6] != Sky.SATS.size.toString()) {
                println("  GGA 卫星数 ${fields[6]} 与星历 ${Sky.SATS.size} 不一致")
            }
        }
        if (id == "GPRMC") {
            // RMC body: time, status, lat, hemi, lon, hemi, speed, course, date, magvar, hemi
            rmcValidOk = rmcValidOk && fields[1] == "A" && fields[6] == "0.0" && fields[7] == "0.0"
        }
    }
    check("五条句子框架与校验和都对", framingOk)
    check("GGA 字段数符合标准", ggaFieldsOk)
    check("GGA 读回坐标与写入一致（±1e-4°）", roundTripOk)
    check("RMC 有效位 A 且速度/航向 0（与静止自洽）", rmcValidOk)

    // ---- geometry: slant range must follow elevation, and stay inside the GPS shell ----
    val overhead = Sky.travelNanos(90.0) / 1e6
    val low = Sky.travelNanos(8.0) / 1e6
    check("天顶传播时间 ≈ 67.4 ms（20 200 km 轨道）", abs(overhead - 67.4) < 1.0, "%.2f ms".format(overhead))
    // 24 900 km 斜距 / c，几何算出来就是这个量级；先前凭印象写的 87 ms 才是错的
    check("8° 仰角传播时间 ≈ 83 ms", abs(low - 83.1) < 1.5, "%.2f ms".format(low))
    check("传播时间随仰角单调递减",
        Sky.travelNanos(74.0) < Sky.travelNanos(41.0) && Sky.travelNanos(41.0) < Sky.travelNanos(8.0))

    // ---- the catalog must be internally consistent ----
    check("星历 svid 不重复", Sky.SATS.map { it.svid }.distinct().size == Sky.SATS.size)
    check("星历 C/N0 随仰角递减",
        Sky.SATS.zipWithNext().all { (a, b) ->
            if (a.elevationDeg > b.elevationDeg) a.cn0DbHz >= b.cn0DbHz else true
        })

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
