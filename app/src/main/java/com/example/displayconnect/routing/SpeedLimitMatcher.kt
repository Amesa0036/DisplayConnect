package com.example.displayconnect.routing

import com.example.displayconnect.offline.StreetWay
import kotlin.math.*

data class SpeedLimitReading(val kmh: Int? = null, val state: String = "unknown")

/** Advisory OSM only: no inferred country default, travel-speed annotation or last-known fallback. */
class SpeedLimitMatcher {
    private var candidateId: Long? = null
    private var candidateKmh: Int? = null
    private var sinceMs = 0L
    private var lastMs = 0L
    private var count = 0

    fun reset() { candidateId = null; candidateKmh = null; sinceMs = 0; lastMs = 0; count = 0 }

    fun update(position: LatLon, accuracyM: Float, speedMps: Float, heading: Float,
        sampleMs: Long, ways: List<StreetWay>, complete: Boolean, profile: RouteProfile): SpeedLimitReading {
        fun unknown(state: String): SpeedLimitReading { reset(); return SpeedLimitReading(state = state) }
        if (!complete) return unknown("coverage_or_route")
        // Accurate moving fixes with bearing, rather than arbitrary nearest road at a standstill.
        if (!accuracyM.isFinite() || accuracyM !in 0f..15f || !speedMps.isFinite() ||
            speedMps < 2f || !heading.isFinite()) return unknown("gps_or_heading")
        val candidates = ways.filter { it.tags["highway"] in ROAD_TYPES }.mapNotNull { way ->
            val hit = closest(position, way) ?: return@mapNotNull null
            val delta = abs((heading - hit.bearing + 540f) % 360f - 180f)
            val forward = delta <= 35f
            val backward = delta >= 145f
            if (!forward && !backward) return@mapNotNull null
            if ((way.tags["oneway"] in setOf("yes", "1", "true") || way.tags["junction"] == "roundabout") && !forward)
                return@mapNotNull null
            if (way.tags["oneway"] == "-1" && forward) return@mapNotNull null
            Triple(way, hit.distance, forward)
        }.sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return unknown("no_road")
        if (best.second > maxOf(8.0, accuracyM.toDouble()).coerceAtMost(15.0)) return unknown("road_distance")
        // Other parallel roads even without maxspeed count as ambiguity.
        if (ways.any { other -> other.id != best.first.id && other.tags["highway"] in ROAD_TYPES &&
                closest(position, other)?.distance?.let { it < best.second + maxOf(10.0, accuracyM.toDouble()) } == true })
            return unknown("ambiguous_road")
        val value = parse(best.first.tags, best.third, profile) ?: return unknown("unsupported_or_missing")
        if (candidateId != best.first.id || candidateKmh != value || sampleMs <= lastMs || sampleMs - lastMs > 3000) {
            candidateId = best.first.id; candidateKmh = value; sinceMs = sampleMs; count = 0
        }
        lastMs = sampleMs; count++
        return if (count >= 3 && sampleMs - sinceMs >= 2000) SpeedLimitReading(value, "osm")
        else SpeedLimitReading(state = "confirming")
    }

    companion object {
        private val ROAD_TYPES = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service")

        fun parse(tags: Map<String, String>, forward: Boolean, profile: RouteProfile): Int? {
            if (profile !in setOf(RouteProfile.CAR, RouteProfile.MOTORCYCLE)) return null
            // Conditional/lane/vehicle rules may change the effective limit. Do not evaluate them by guess.
            if (tags.keys.any { it.startsWith("maxspeed:") && it !in setOf("maxspeed:forward", "maxspeed:backward", "maxspeed:type") }) return null
            if (tags.keys.any { it.contains(":conditional") }) return null
            if (tags["access"] in setOf("no", "private") || tags["motor_vehicle"] == "no" ||
                (profile == RouteProfile.MOTORCYCLE && tags["motorcycle"] == "no")) return null
            val raw = tags[if (forward) "maxspeed:forward" else "maxspeed:backward"] ?: tags["maxspeed"] ?: return null
            val match = Regex("^([0-9]{1,3})(?:\\s*(km/h|kmh|kph|mph))?$", RegexOption.IGNORE_CASE).matchEntire(raw.trim()) ?: return null
            val number = match.groupValues[1].toInt()
            if (number !in 1..200) return null
            return (number * if (match.groupValues[2].equals("mph", true)) 1.609344 else 1.0).roundToInt().takeIf { it in 1..250 }
        }

        private data class Hit(val distance: Double, val bearing: Float)
        private fun closest(position: LatLon, way: StreetWay): Hit? {
            val cosLat = cos(Math.toRadians(position.lat))
            return way.points.zipWithNext { a, b ->
                val ax = (a.lon - position.lon) * 111320 * cosLat
                val ay = (a.lat - position.lat) * 111320
                val dx = (b.lon - a.lon) * 111320 * cosLat
                val dy = (b.lat - a.lat) * 111320
                val sq = dx * dx + dy * dy
                if (sq < 0.01) null else {
                    val t = (-(ax * dx + ay * dy) / sq).coerceIn(0.0, 1.0)
                    Hit(hypot(ax + t * dx, ay + t * dy), ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat())
                }
            }.filterNotNull().minByOrNull { it.distance }
        }
    }
}
