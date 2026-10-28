package com.mockloc

import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * GPX 1.1 import/export for routes (REFACTOR-PLAN 2.4). Reads `<trkpt>`/`<wpt>`/`<rtept>` and
 * writes a minimal `<trk>` document; both directions are checked on a plain JVM by
 * `tools/kotlin/RouteCheck.kt`. DOM parsing is used instead of string scraping so namespaces and
 * optional child elements (elevations, names) can't break the coordinate read.
 */
object Gpx {

    fun parse(text: String): List<Route.Pt> {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            // GPX namespaces vary (1.0/1.1, extensions); non-NS mode reads unprefixed names fine.
            factory.isNamespaceAware = false
            factory.newDocumentBuilder().parse(text.byteInputStream())
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Route.Pt>()
        for (tag in listOf("trkpt", "rtept", "wpt")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
                if (lat in -90.0..90.0 && lon in -180.0..180.0) out.add(Route.Pt(lat, lon))
            }
        }
        return out
    }

    fun export(name: String, waypoints: List<Route.Pt>): String {
        val out = ByteArrayOutputStream()
        out.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="mockloc" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>${escape(name)}</name><trkseg>
""".toByteArray())
        waypoints.forEach { p ->
            out.write(
                """<trkpt lat="${"%.7f".format(p.lat)}" lon="${"%.7f".format(p.lng)}"></trkpt>
""".toByteArray(),
            )
        }
        out.write("</trkseg></trk>\n</gpx>\n".toByteArray())
        return out.toString("UTF-8")
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
