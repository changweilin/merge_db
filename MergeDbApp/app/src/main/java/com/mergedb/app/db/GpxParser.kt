package com.mergedb.app.db

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object GpxParser {

    data class GpxTrack(val points: List<LatLon>, val name: String?)

    fun parse(data: ByteArray): GpxTrack {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(data.inputStream(), null)

        val points = mutableListOf<LatLon>()
        var trackName: String? = null
        var inName = false
        val nameText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "trkpt" || tag == "wpt" || tag == "rtept") {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            points.add(LatLon(lat, lon))
                        }
                    } else if (tag == "name" && trackName == null) {
                        inName = true
                        nameText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inName) nameText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "name" && inName) {
                        trackName = nameText.toString().trim().ifEmpty { null }
                        inName = false
                    }
                }
            }
            event = parser.next()
        }

        return GpxTrack(points, trackName)
    }

    fun export(points: List<LatLon>, trackName: String): ByteArray {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="MergeDB TSP">""")
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>${trackName.xmlEscape()}</name>")
        sb.appendLine("    <trkseg>")
        for (pt in points) {
            sb.appendLine("""      <trkpt lat="${pt.lat}" lon="${pt.lon}"></trkpt>""")
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun String.xmlEscape() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
