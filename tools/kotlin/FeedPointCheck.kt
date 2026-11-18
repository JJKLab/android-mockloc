import com.mockloc.Config
import com.mockloc.Route
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * Standalone acceptance check for the single "feed point" algorithm (v1.3.0 走查 P0-1/P0-2/P0-3).
 * Not part of the APK sources.
 *
 * What it pins down:
 *  - the jitter is deterministic per 1s window, so tier-0 (MockService) and tier-3 (Config hooks)
 *    compute the same point instead of two independent random walks;
 *  - a route point legitimately moves >200m away from the centre, which is exactly what the old
 *    patrol criterion (compare against the centre) misread as drift;
 *  - a large jitter radius also produces points >200m from the centre, same false-positive;
 *  - [Config.applyJitter] delegates to [Config.feedPoint], so the two cannot fork again.
 *
 * Config reads one hard-coded path, so this writes that same path from the JVM (same approach as
 * ConfigCheck). If the directory cannot be created the check reports SKIP, not a fake pass.
 */
private var fails = 0

private fun check(label: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"} $label${if (detail.isEmpty()) "" else ": $detail"}")
    if (!ok) fails++
}

/** Equirectangular distance in metres, the same scaling the feed-point math uses. */
private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val dLat = (bLat - aLat) * 111_132.0
    val dLng = (bLng - aLng) * 111_412.8 * cos(Math.toRadians((aLat + bLat) / 2.0))
    return sqrt(dLat * dLat + dLng * dLng)
}

fun main() {
    val now = 1_764_000_000_000L
    val centerLat = 31.2304
    val centerLng = 121.4737

    // 1) 确定性：同一时间窗内两次取样逐位相同（档 0 与档 3 一致的前提）。
    val a = Config.feedPoint(centerLat, centerLng, 50.0, emptyList(), 1.4, false, 0L, now)
    val b = Config.feedPoint(centerLat, centerLng, 50.0, emptyList(), 1.4, false, 0L, now)
    check("同窗口抖动确定", a == b, "$a vs $b")

    // 2) 跨窗口应给出不同偏移（不是恒定向量）。
    val c = Config.feedPoint(centerLat, centerLng, 50.0, emptyList(), 1.4, false, 0L, now + 1000L)
    check("跨窗口抖动变化", a != c)

    // 3) 半径 0 → 精确中心。
    val pinned = Config.feedPoint(centerLat, centerLng, 0.0, emptyList(), 1.4, false, 0L, now)
    check("半径0钉中心", pinned.lat == centerLat && pinned.lng == centerLng)

    // 4) 抖动不超出半径（圆盘均匀，sqrt 抽样）。
    val r = 500.0
    var maxOff = 0.0
    for (w in 0 until 200) {
        val p = Config.feedPoint(centerLat, centerLng, r, emptyList(), 1.4, false, 0L, now + w * 1000L)
        maxOff = maxOf(maxOff, distanceMeters(centerLat, centerLng, p.lat, p.lng))
    }
    check("抖动不超半径", maxOff <= r + 1e-6, "max=%.2f r=%.0f".format(maxOff, r))

    // 5) 路线推进：速度=配置值，且沿路线走出 >200m（旧巡检判据会误判成漂移）。
    val wpts = listOf(
        Route.Pt(31.2304, 121.4737),
        Route.Pt(31.2404, 121.4837),
        Route.Pt(31.2504, 121.4937),
    )
    val started = now
    val p0 = Config.feedPoint(centerLat, centerLng, 0.0, wpts, 1.4, false, started, started + 1000L)
    val p1 = Config.feedPoint(centerLat, centerLng, 0.0, wpts, 1.4, false, started, started + 200_000L)
    check("路线速度=配置值", p1.speedMps == 1.4)
    val moved = distanceMeters(p0.lat, p0.lng, p1.lat, p1.lng)
    check("路线 200s 走了 >200m", moved > 200.0, "moved=%.1f".format(moved))
    val fromCenter = distanceMeters(centerLat, centerLng, p1.lat, p1.lng)
    check("路线点离中心 >200m（旧判据会误杀）", fromCenter > 200.0, "d=%.1f".format(fromCenter))

    // 6) 大半径抖动也会产生离中心 >200m 的合法喂点（同类误判）。
    var bigExceeds = false
    for (w in 0 until 50) {
        val p = Config.feedPoint(centerLat, centerLng, 400.0, emptyList(), 1.4, false, 0L, now + w * 1000L)
        if (distanceMeters(centerLat, centerLng, p.lat, p.lng) > 200.0) bigExceeds = true
    }
    check("大半径(400m)产生离中心 >200m 的喂点", bigExceeds)

    // 7) Config.applyJitter 必须委托给同一个 feedPoint，否则两层会再次分叉。
    val file = File(Config.DEFAULT_PATH)
    val dir = file.parentFile
    if (dir == null || (!dir.exists() && !dir.mkdirs())) {
        println("SKIP: 写不了 ${Config.DEFAULT_PATH}（这个平台不允许创建该目录）")
        exitProcess(2)
    }
    file.writeText(
        """
        MOCKLOC_ENABLED=true
        MOCKLOC_LAT=31.2304000
        MOCKLOC_LNG=121.4737000
        MOCKLOC_RADIUS=0.0
        MOCKLOC_ROUTE_WAYPOINTS=31.2304000,121.4737000;31.2404000,121.4837000
        MOCKLOC_ROUTE_STARTED=1764000000000
        MOCKLOC_SPEED_MPS=1.4
        """.trimIndent(),
    )
    Config.refresh()
    val t = 1_764_000_050_000L
    Config.applyJitter(t)
    val expect = Config.feedPoint(
        Config.centerLat, Config.centerLng, Config.radius,
        Config.routeWaypoints, Config.speedMps, Config.routeLoop, Config.routeStartedMs, t,
    )
    check(
        "applyJitter 与 feedPoint 一致",
        Config.currentLat == expect.lat && Config.currentLng == expect.lng,
        "got=${Config.currentLat},${Config.currentLng} want=${expect.lat},${expect.lng}",
    )

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
