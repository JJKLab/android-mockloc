package com.mockloc

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * 地图显示源三选一（v1.3.0）：高德（GCJ 瓦片，默认）/ 谷歌（WGS 瓦片，需国际网络）/
 * 百度（BD09 瓦片，中心原点索引）。每个源携带两件事：瓦片 URL 构造 + 坐标进/出转换——
 * osmdroid 内部永远按"标准 Web-Mercator 的 WGS lat/lng"投影视图，所以接入非 WGS 瓦片的
 * 唯一干净做法是把**画上图的每个坐标先转成该源的坐标系再当 WGS 喂进去**（高德喂 GCJ、
 * 百度喂 BD09），瓦片和标记就天然对齐；点回读走反向转换。谷歌是恒等。
 */
enum class MapSource(val label: String, val hint: String) {
    AMAP("高德", "GCJ 瓦片 · 国内直连"),
    GOOGLE("谷歌", "WGS 瓦片 · 需国际网络"),
    BAIDU("百度", "BD09 瓦片 · 国内直连");

    fun tileSource(): XYTileSource = when (this) {
        AMAP -> AmapTiles()
        GOOGLE -> GoogleTiles()
        BAIDU -> BaiduTiles()
    }

    /** WGS-84 → 该源的显示坐标（画标记/画线/设中心）。 */
    fun toDisplay(lat: Double, lng: Double): DoubleArray = when (this) {
        AMAP -> CoordTransform.wgs84ToGcj02(lat, lng)
        GOOGLE -> doubleArrayOf(lat, lng)
        BAIDU -> CoordTransform.wgs84ToBd09(lat, lng)
    }

    /** 该源的显示坐标 → WGS-84（点图/回读）。 */
    fun toWgs(lat: Double, lng: Double): DoubleArray = when (this) {
        AMAP -> CoordTransform.gcj02ToWgs84(lat, lng)
        GOOGLE -> doubleArrayOf(lat, lng)
        BAIDU -> CoordTransform.bd09ToWgs84(lat, lng)
    }

    /** 高德系 GCJ 坐标（搜索 API 的返回值）→ 该源显示坐标。 */
    fun fromGcj(lat: Double, lng: Double): DoubleArray = when (this) {
        AMAP -> doubleArrayOf(lat, lng)
        GOOGLE -> CoordTransform.gcj02ToWgs84(lat, lng)
        BAIDU -> CoordTransform.gcj02ToBd09(lat, lng)
    }
}

/** 谷歌街道瓦片：标准 WGS Web-Mercator，z/x/y 顺序同高德一样要手工重建（osmdroid 6.1 的占位符缺失）。 */
class GoogleTiles : XYTileSource(
    "GoogleMaps",
    3,
    19,
    256,
    ".png",
    arrayOf(BASE),
    "© Google",
) {
    override fun getTileURLString(tile: Long): String {
        val built = super.getTileURLString(tile)
        val match = TAIL.find(built) ?: return built
        val (z, x, y) = match.destructured
        val host = HOSTS[x.toInt() % HOSTS.size]
        return "$host/vt/lyrs=m&hl=zh-CN&gl=cn&x=$x&y=$y&z=$z"
    }

    private companion object {
        const val BASE = "https://mt0.google.com/vt/lyrs=m"
        val HOSTS = (0..3).map { "https://mt$it.google.com" }
        val TAIL = Regex("""(\d+)/(\d+)/(\d+)""")
    }
}

/**
 * 百度瓦片：BD09 参考系 + **中心原点索引**（x/y 以 BD09(0,0) 所在瓦片为 0，y 向北增长），与
 * slippy 网格差一个原点平移和 y 翻转——这是各家百度瓦片接入（leaflet-tileLayer-baidu 等）的
 * 公式：bx = x - 2^(z-1)，by = 2^(z-1) - 1 - y。对齐精度依赖"BD09MC ≈ 标准 Mercator(BD09LL)"
 * 的近似，装机目视校验过偏差在瓦片内。
 */
class BaiduTiles : XYTileSource(
    "BaiduMaps",
    3,
    19,
    256,
    ".png",
    arrayOf(BASE),
    "© 百度地图",
) {
    override fun getTileURLString(tile: Long): String {
        val built = super.getTileURLString(tile)
        val match = TAIL.find(built) ?: return built
        val (zRaw, xRaw, yRaw) = match.destructured
        val z = zRaw.toInt()
        val half = 1L shl (z - 1)
        val bx = xRaw.toLong() - half
        val by = half - 1 - yRaw.toLong()
        val host = HOSTS[((bx + by).toInt()).mod(HOSTS.size)]
        return "$host/tile/?qt=tile&x=$bx&y=$by&z=$z&styles=pl&scaler=1&udt=$UDT"
    }

    private companion object {
        const val BASE = "https://maponline0.bdimg.com/tile"
        val HOSTS = (0..3).map { "https://maponline$it.bdimg.com" }
        const val UDT = "20241015"
        val TAIL = Regex("""(\d+)/(\d+)/(\d+)""")
    }
}
