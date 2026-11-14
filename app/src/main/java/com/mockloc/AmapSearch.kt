package com.mockloc

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 搜索结果统一条目。
 * @param source geo=门牌号/区域（geocode），poi=POI（place/text 或 around）
 * @param addr 地址/类别行（双行 UI 第二行）
 * @param distM 到当前选点/搜索中心的米数（around 结果有，text 结果无）
 */
class Hit(
    val name: String,
    val addr: String,
    val latGcj: Double,
    val lngGcj: Double,
    val source: String = "poi",
    val distM: Int? = null,
) {
    /** 主行：名称（距离有则附在行尾） */
    val title: String get() = if (distM != null) "$name  · ${fmtDist(distM)}" else name
    /** 副行：地址或门牌等级 */
    val sub: String get() = addr

    private fun fmtDist(m: Int) = if (m >= 1000) "${"%.1f".format(m / 1000f)}km" else "${m}m"
}

/**
 * Amap Web服务 REST client。只有 stdlib + org.json：构建不可本地跑，依赖面越小越不会失败。
 *
 * 三个入口对应三种用户动作：
 * - [geocode]：门牌号/区域 → 坐标（"XX路100号"）
 * - [poiText]：关键词 → POI 列表（"星巴克"、"东方明珠"），按热门排序
 * - [around]：一个坐标 → 周边 POI（补全门牌号下的公司/餐厅，像高德 App 那样列）
 */
object AmapSearch {
    class AmapError(message: String) : Exception(message)

    private const val BASE = "https://restapi.amap.com/v3"

    fun geocode(key: String, address: String, city: String = ""): List<Hit> {
        val query = StringBuilder("key=").append(enc(key))
            .append("&address=").append(enc(address))
            .append("&output=JSON")
        if (city.isNotEmpty()) query.append("&city=").append(enc(city))
        val payload = JSONObject(get("$BASE/geocode/geo?$query"))
        if (payload.optString("status") != "1") throw amapError(payload)
        val list = payload.optJSONArray("geocodes") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val loc = parseLoc(item.optString("location")) ?: return@mapNotNull null
            // 门牌号条目：level 是 door/兴趣点/道路等，addr 用完整格式化地址
            Hit(item.optString("formatted_address"), "门牌 · ${item.optString("level")}", loc[0], loc[1], "geo")
        }
    }

    fun poiText(key: String, query: String, city: String = ""): List<Hit> {
        val q = StringBuilder("key=").append(enc(key))
            .append("&keywords=").append(enc(query))
            .append("&offset=15&page=1&output=JSON")
        if (city.isNotEmpty()) q.append("&city=").append(enc(city)).append("&citylimit=true")
        val payload = JSONObject(get("$BASE/place/text?$q"))
        if (payload.optString("status") != "1") throw amapError(payload)
        val list = payload.optJSONArray("pois") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> poi(list.optJSONObject(i)) }
    }

    fun around(key: String, latGcj: Double, lngGcj: Double, radiusM: Int = 300): List<Hit> {
        val q = "key=${enc(key)}&location=${enc("$lngGcj,$latGcj")}" +
            "&radius=$radiusM&offset=15&page=1&sortrule=distance&output=JSON"
        val payload = JSONObject(get("$BASE/place/around?$q"))
        if (payload.optString("status") != "1") throw amapError(payload)
        val list = payload.optJSONArray("pois") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val h = poi(item) ?: return@mapNotNull null
            // around 自带 distance 字段，比我们算的球面距离更符合高德口径
            val dist = item.optInt("distance", -1).takeIf { it >= 0 }
            Hit(h.name, h.addr, h.latGcj, h.lngGcj, h.source, dist ?: h.distM)
        }.sortedBy { it.distM ?: Int.MAX_VALUE }
    }

    /** poi JSON → Hit；同建筑多个入口坐标去重（按 5 位小数 = ~1m 网格）。 */
    private fun poi(item: JSONObject?): Hit? {
        item ?: return null
        val loc = parseLoc(item.optString("location")) ?: return null
        val name = item.optString("name").trim().ifEmpty { return null }
        val rawAddr = item.optString("address").trim()
        val type = item.optString("type").substringBefore(';').substringBefore('-')
        val addr = when {
            rawAddr.isEmpty() && type.isNotEmpty() -> type
            rawAddr.isNotEmpty() && type.isNotEmpty() && type != rawAddr -> "$rawAddr · $type"
            else -> rawAddr
        }
        val dist = item.optInt("distance", -1).takeIf { it >= 0 }
        return Hit(name, addr.ifEmpty { "无地址" }, loc[0], loc[1], "poi", dist)
    }

    private fun parseLoc(raw: String): DoubleArray? {
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val lng = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        return doubleArrayOf(lat, lng)
    }

    private fun amapError(payload: JSONObject): AmapError {
        val code = payload.optString("infocode")
        val hint = if (code == "10001") "  <- key 无效或不是「Web服务」类型" else ""
        return AmapError("${payload.optString("info")} ($code)$hint")
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "mockloc/1.3")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return BufferedReader(stream.reader()).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
