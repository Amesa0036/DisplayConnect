package com.example.displayconnect.routing

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lectura de archivos GPX: tracks (<trkpt>) y rutas (<rtept>), con varios segmentos. */
object GpxParser {

    data class GpxTrack(
        val name: String,
        val points: List<LatLon>,
        val distanceM: Double
    )

    fun parse(xml: String, fallbackName: String): GpxTrack {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var name = ""
        val raw = mutableListOf<LatLon>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> if (name.isBlank()) name = parser.nextText().trim()
                    "trkpt", "rtept" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                            val last = raw.lastOrNull()
                            if (last == null || haversineMeters(last, LatLon(lat, lon)) > 1.0) {
                                raw.add(LatLon(lat, lon))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        val points = decimate(raw)
        return GpxTrack(
            name = name.ifBlank { fallbackName },
            points = points,
            distanceM = totalDistance(points)
        )
    }

    /** Reduce puntos: espaciado minimo y tope para que el map-matching sea manejable. */
    private fun decimate(points: List<LatLon>): List<LatLon> {
        var spacing = 50.0
        repeat(4) {
            val result = spaced(points, spacing)
            if (result.size <= 1800) return result
            spacing *= 1.6
        }
        return spaced(points, spacing).take(1800)
    }

    private fun spaced(points: List<LatLon>, minSpacingM: Double): List<LatLon> {
        if (points.size < 3) return points
        val out = mutableListOf(points.first())
        var lastKept = points.first()
        for (i in 1 until points.lastIndex) {
            if (haversineMeters(lastKept, points[i]) >= minSpacingM) {
                out.add(points[i])
                lastKept = points[i]
            }
        }
        out.add(points.last())
        return out
    }

    fun totalDistance(points: List<LatLon>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) total += haversineMeters(points[i], points[i + 1])
        return total
    }

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun formatKm(distanceM: Double): String {
        val km = distanceM / 1000.0
        val text = if (km < 20) ((km * 10).roundToInt() / 10.0).toString() else km.roundToInt().toString()
        return text.replace('.', ',')
    }
}
