package com.mockloc

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Hit(val name: String, val detail: String, val latGcj: Double, val lngGcj: Double) {
    val label: String get() = "$name  ${"%.5f".format(latGcj)},${"%.5f".format(lngGcj)}"
}

/**
 * Amap Web服务 REST client. Only stdlib + org.json: the fewer moving parts an APK has, the fewer
 * ways a build we cannot run locally can fail.
 */
object AmapSearch {
    class AmapError(message: String) : Exception(message)

    fun geocode(key: String, address: String, city: String = ""): List<Hit> {
        val query = StringBuilder("key=").append(enc(key))
            .append("&address=").append(enc(address))
            .append("&output=JSON")
        if (city.isNotEmpty()) query.append("&city=").append(enc(city))
        val payload = JSONObject(get("https://restapi.amap.com/v3/geocode/geo?$query"))
        if (payload.optString("status") != "1") {
            val code = payload.optString("infocode")
            val hint = if (code == "10001") "  <- key 无效或不是「Web服务」类型" else ""
            throw AmapError("${payload.optString("info")} ($code)$hint")
        }
        val list = payload.optJSONArray("geocodes") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val parts = item.optString("location").split(',')
            if (parts.size != 2) return@mapNotNull null
            val lng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Hit(item.optString("formatted_address"), item.optString("level"), lat, lng)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "mockloc/0.3")
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
