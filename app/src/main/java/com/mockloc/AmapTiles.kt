package com.mockloc

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Amap raster tiles are Web-Mercator but georeferenced in GCJ-02, so a tap yields GCJ-02 numbers
 * and every injection path converts back to WGS-84 before it reaches the GPS stack.
 *
 * osmdroid 6.1 has no `{x}/{y}/{z}` placeholder support: it appends `z/x/y` to the base URL, which
 * Amap answers with 404 (seen on device as `Problem downloading MapTile: /11/1715/834 Not Found`,
 * while the same tile with real numbers returns 200 image/png). So the parent's own output is
 * parsed and the query-string URL is rebuilt here.
 */
class AmapTiles : XYTileSource(
    "AMap",
    3,
    19,
    256,
    ".png",
    arrayOf(BASE),
    "© 高德地图",
) {

    override fun getTileURLString(tile: Long): String {
        val built = super.getTileURLString(tile)
        val match = TAIL.find(built) ?: return built
        val (z, x, y) = match.destructured
        val host = HOSTS[(x.toInt() + y.toInt()) % HOSTS.size]
        return "$host/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$z"
    }

    private companion object {
        const val BASE = "https://webrd01.is.autonavi.com/appmaptile"
        val HOSTS = (1..4).map { "https://webrd0$it.is.autonavi.com" }
        val TAIL = Regex("""(\d+)/(\d+)/(\d+)""")
    }
}
