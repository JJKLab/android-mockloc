package com.mockloc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.widget.Toast
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * 百度瓦片自绘覆盖层（v1.3.0）：百度 zoom 锚定 z18（分辨率 2^(18-z) m/px），瓦片 = floor(MC×2^(z-18)/256)，
 * 网格与标准 slippy 相差 1.674 倍——osmdroid 的 TilesOverlay 无法对齐，只能按投影自算自绘：
 *
 * 1. 视口四角 → WGS → BD09 → MC（近似 = 标准 Mercator(BD09)，K≈1.00005，见 README 附录 G 注）
 * 2. 可见区枚举百度瓦片（中心原点索引，y 北向增长）
 * 3. 每瓦片按其 MC 窗口 → 屏幕 Rect，drawBitmap 缩放贴上
 *
 * 线程：2 线程池拉图，成功后 postInvalidate 重绘。内存缓存 + 进程内去重，磁盘交给 osmdroid 无关。
 */
class BaiduOverlay(private val mapView: org.osmdroid.views.MapView) : Overlay() {

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)

    /** 是否参与绘制（由 Activity 按当前地图源开关）。enabled 与 Overlay.setEnabled 撞签名，改名。 */
    @Volatile
    var active: Boolean = false

    override fun draw(canvas: Canvas, p: Projection) {
        if (!active) return
        val zoom = p.zoomLevel
        val zb = (zoom + Z_BAIDU_OFFSET).toInt().coerceIn(3, 19)
        val tsB = 256.0 * 2.0.pow(18 - zb)

        val w = mapView.width
        val h = mapView.height
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (sx in intArrayOf(0, w.toInt())) for (sy in intArrayOf(0, h.toInt())) {
            val ll = p.fromPixels(sx, sy) ?: continue
            val mc = bd09ToMc(ll.latitude, ll.longitude)
            if (mc[0] < minX) minX = mc[0]
            if (mc[0] > maxX) maxX = mc[0]
            if (mc[1] < minY) minY = mc[1]
            if (mc[1] > maxY) maxY = mc[1]
        }
        if (minX > maxX || minY > maxY) return
        val x0 = floor(minX / tsB).toLong()
        val x1 = floor(maxX / tsB).toLong()
        val y0 = floor(minY / tsB).toLong()
        val y1 = floor(maxY / tsB).toLong()
        if ((x1 - x0) > 12 || (y1 - y0) > 12) return // 视口异常保护（首帧未布局等）

        val pt = Point()
        for (bx in x0..x1) for (by in y0..y1) {
            val key = "$zb/$bx/$by"
            val bmp = cache[key]
            if (bmp != null) {
                // 瓦片窗口 [bx*tsB,(bx+1)*tsB]×[by*tsB,(by+1)*tsB] → 屏幕四角
                val sw = mcToScreen(bx * tsB, by * tsB, p, pt)
                val ne = mcToScreen((bx + 1) * tsB, (by + 1) * tsB, p, pt)
                canvas.drawBitmap(bmp, null, RectF(sw.x.toFloat(), ne.y.toFloat(), ne.x.toFloat(), sw.y.toFloat()), null)
            } else {
                request(key, zb, bx, by)
            }
        }
    }

    private fun request(key: String, zb: Int, bx: Long, by: Long) {
        if (!pending.add(key)) return
        pool.execute {
            val host = HOSTS[((bx + by).toInt()).mod(HOSTS.size)]
            val url = "$host/tile/?qt=tile&x=$bx&y=$by&z=$zb&styles=pl&scaler=1&udt=$UDT"
            val bmp = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36",
                )
                if (conn.responseCode != 200) null
                else BitmapFactory.decodeStream(conn.inputStream)?.takeIf { it.width == 256 }
            }.getOrNull()
            if (bmp != null) {
                cache[key] = bmp
                mapView.postInvalidate()
            }
            pending.remove(key)
        }
    }

    /** MC 米 → 屏幕。伪坐标 = BD09LL 数值喂进 osmdroid 的标准投影（与 MapSource.toDisplay 一致）。 */
    private fun mcToScreen(mcX: Double, mcY: Double, p: Projection, pt: Point): Point {
        val lat = mcToLat(mcY)
        val lng = Math.toDegrees(mcX / EARTH_R)
        p.toPixels(GeoPoint(lat, lng), pt)
        return pt
    }

    private fun bd09ToMc(lat: Double, lng: Double): DoubleArray {
        val lam = Math.toRadians(lng)
        val phi = Math.toRadians(lat)
        val x = EARTH_R * lam
        val y = EARTH_R * ln(tan(Math.PI / 4 + phi / 2))
        return doubleArrayOf(x, y)
    }

    private fun mcToLat(mcY: Double): Double {
        val n = Math.min(Math.max(mcY / EARTH_R, -PI_HALF), PI_HALF)
        return Math.toDegrees(2.0 * Math.atan(Math.exp(n)) - Math.PI / 2)
    }

    private companion object {
        const val EARTH_R = 6378137.0
        const val PI_HALF = Math.PI / 2
        const val Z_BAIDU_OFFSET = 0.7435
        const val UDT = "20241015"
        val HOSTS = (0..3).map { "https://maponline$it.bdimg.com" }
    }
}
