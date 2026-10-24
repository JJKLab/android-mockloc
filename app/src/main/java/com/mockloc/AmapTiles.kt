package com.mockloc

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Amap raster tiles are Web-Mercator but georeferenced in GCJ-02, so a tap yields GCJ-02 numbers
 * and every injection path converts back to WGS-84 before it reaches the GPS stack.
 */
class AmapTiles : XYTileSource(
    "AMap",
    3,
    19,
    256,
    ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}",
    ),
    "© 高德地图",
)
