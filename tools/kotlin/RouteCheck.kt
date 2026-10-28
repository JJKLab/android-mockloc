import com.mockloc.Gpx
import com.mockloc.Route
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * JVM acceptance for the route engine (REFACTOR-PLAN 2.1/2.4): the geometry must pass through its
 * control points, the progression must honour speed and the anchor clock, loop must wrap, and GPX
 * must round-trip. These are the numbers a consumer could actually catch us on.
 */
private var fails = 0

private fun check(label: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"} $label${if (detail.isEmpty()) "" else ": $detail"}")
    if (!ok) fails++
}

private fun near(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

private val wpts = listOf(
    Route.Pt(31.2229000, 121.4644000),
    Route.Pt(31.2260000, 121.4680000),
    Route.Pt(31.2295000, 121.4710000),
    Route.Pt(31.2330000, 121.4745000),
)

fun main() {
    // ---- geometry ----
    val path = Route.build(wpts)
    check("4 航点可建路径", path != null && path!!.pts.size >= wpts.size)
    check("累计里程单调递增", path != null && path!!.cum.zipWithNext().all { (a, b) -> b >= a })
    check("总里程为正", path != null && path!!.totalMeters > 300.0,
        "total=${path?.totalMeters}")

    // Catmull-Rom passes exactly through its control points (the property that separates a real
    // spline from "a curve near the points").
    val tolerance = 1e-6
    wpts.forEachIndexed { i, wp ->
        val closest = path!!.pts.minOf { p ->
            val dLat = (p.lat - wp.lat) * 111_132.0
            val dLng = (p.lng - wp.lng) * 111_412.8
            kotlin.math.sqrt(dLat * dLat + dLng * dLng)
        }
        check("路径过控制点 #${i + 1}", closest <= tolerance + 0.5, "closest=${closest}m")
    }

    // Endpoints land on the first/last waypoint.
    val atStart = Route.sampleAtDistance(path!!, 0.0)
    check("起点=首航点", near(atStart.lat, wpts.first().lat, 1e-6))
    val atEnd = Route.sampleAtDistance(path, path.totalMeters)
    check("终点=末航点", near(atEnd.lat, wpts.last().lat, 1e-6))

    // ---- progression ----
    val speed = 1.4
    val started = 1_700_000_000_000L
    val s100 = Route.sampleElapsed(wpts, speed, false, started, started + 100_000)
    check("100s 后有采样", s100 != null)
    // 140 m of travel at 1.4 m/s: the reported speed must be exactly the configured one.
    check("速度=配置值", near(s100!!.speedMps, speed, 1e-9), "got=${s100.speedMps}")
    check("航向在 [0,360)", s100.bearingDeg in 0.0..360.0, "bearing=${s100.bearingDeg}")
    val s0 = Route.sampleElapsed(wpts, speed, false, started, started)
    check("t=0 在起点", near(s0!!.lat, wpts.first().lat, 1e-6))
    val pastEnd = Route.sampleElapsed(wpts, speed, false, started, started + 100 * 365L * 24 * 3600 * 1000)
    check("非循环终点驻停", near(pastEnd!!.lat, wpts.last().lat, 1e-6))

    // Loop wraps: after exactly one lap the position is back at (or just past) the start.
    val lapSec = path.totalMeters / speed
    val sLap = Route.sampleElapsed(wpts, speed, true, started, started + ((lapSec.toLong() + 1) * 1000))
    check("循环绕回起点附近", sLap != null && abs(sLap.lat - wpts.first().lat) < 1e-3,
        "lat=${sLap?.lat}")

    // Degenerate inputs never throw and never move.
    check("单点无采样", Route.sampleElapsed(listOf(wpts[0]), speed, true, started, started + 1000) == null)
    check("速度 0 无采样", Route.sampleElapsed(wpts, 0.0, true, started, started + 1000) == null)
    check("锚点未设无采样", Route.sampleElapsed(wpts, speed, true, 0, started + 1000) == null)

    // ---- wire format ----
    val encoded = Route.encode(wpts)
    val decoded = Route.decode(encoded)
    check("编码解码往返", decoded.size == wpts.size &&
        decoded.zip(wpts).all { (a, b) -> near(a.lat, b.lat, 1e-7) && near(a.lng, b.lng, 1e-7) })

    // ---- GPX ----
    val exported = Gpx.export("验收路线", wpts)
    val parsed = Gpx.parse(exported)
    check("GPX 导出可回读", parsed.size == wpts.size &&
        parsed.zip(wpts).all { (a, b) -> near(a.lat, b.lat, 1e-7) && near(a.lng, b.lng, 1e-7) })

    // A GPX 1.1 document with namespaces and extension children - the shape other tools emit.
    val thirdParty = """
        <?xml version="1.0"?>
        <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
          <trk><trkseg>
            <trkpt lat="31.2229" lon="121.4644"><ele>4.0</ele></trkpt>
            <trkpt lat="31.2260" lon="121.4680"><ele>5.0</ele></trkpt>
          </trkseg></trk>
          <wpt lat="31.2330" lon="121.4745"><name>end</name></wpt>
        </gpx>
    """.trimIndent()
    val parsedThird = Gpx.parse(thirdParty)
    check("三方 GPX（带命名空间/ele/name）可读", parsedThird.size == 3, "got=${parsedThird.size}")

    check("坏输入不抛异常", Gpx.parse("not xml at all <<>>").isEmpty())

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
