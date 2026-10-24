package com.mockloc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Place(val name: String, val lat: Double, val lng: Double, val note: String) {
    val label: String get() = "$name  ${"%.5f".format(lat)},${"%.5f".format(lng)}"
}

/** Saved points, stored as JSON in this app's private prefs (never in the world-readable mirror). */
class Places(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun all(): List<Place> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Place(
                o.optString("name"),
                o.optDouble("lat", 0.0),
                o.optDouble("lng", 0.0),
                o.optString("note"),
            )
        }
    }

    fun add(place: Place) {
        val items = all().toMutableList()
        items.removeAll { it.name == place.name && it.lat == place.lat && it.lng == place.lng }
        items.add(place)
        save(items)
    }

    fun removeAt(index: Int) {
        val items = all().toMutableList()
        if (index in items.indices) {
            items.removeAt(index)
            save(items)
        }
    }

    private fun save(items: List<Place>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("note", it.note))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "places"
    }
}
