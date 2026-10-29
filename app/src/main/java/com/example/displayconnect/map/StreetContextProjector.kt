package com.example.displayconnect.map

import com.example.displayconnect.routing.LatLon
import kotlin.math.*

object StreetContextProjector {
    // Must match NAV_MAX_STREET_SEGMENTS in the CYD firmware.
    const val MAX_SEGMENTS = 128

    /**
     * [priority] receives the index of a way in [ways] and returns its tier; lower tiers
     * are offered first inside each density-grid cell. Roundabout arcs carry maneuvers
     * (tier 0) and the link/major roads connecting them keep the interchange readable
     * (tier 1), so under budget pressure minor streets are sacrificed first. Order
     * within each tier and the 6x3 grid distribution are unchanged; the budget is
     * still hard-capped.
     */
    fun projectSegments(
        centerLat: Double, centerLon: Double, ways: List<List<LatLon>>,
        scaleMeters: Double, maxSegments: Int = MAX_SEGMENTS,
        priority: (Int) -> Int = { 0 }
    ): List<IntArray> {
        val budget = maxSegments.coerceIn(0, MAX_SEGMENTS)
        if (budget == 0) return emptyList()
        val visible = ways.mapIndexedNotNull { index, way ->
            ScreenGeometry.visibleRuns(way.map {
                MapProjector.projectUnclipped(centerLat, centerLon, it.lat, it.lon, scaleMeters)
            }).takeIf { it.isNotEmpty() }?.let { index to it }
        }
        // First simplify all visible streets equally, keeping distant roads as well as central roads.
        var simplified = visible.map { (index, runs) -> index to runs.map { ScreenGeometry.simplify(it) } }
        for (tolerance in listOf(2.0, 3.0)) {
            if (simplified.sumOf { (_, runs) -> runs.sumOf { it.size - 1 } } <= budget) break
            simplified = visible.map { (index, runs) -> index to runs.map { ScreenGeometry.simplify(it, tolerance) } }
        }
        // In very dense areas, distribute complete ways over a 6x3 screen grid.
        // Priority ways are offered first inside each cell; stable sort keeps the
        // previous relative order of everything else.
        val groups = simplified.groupBy { (_, runs) ->
            val points = runs.flatten()
            val x = (points.minOf { it.x } + points.maxOf { it.x }) / 2
            val y = (points.minOf { it.y } + points.maxOf { it.y }) / 2
            (x / (MapProjector.MAP_WIDTH / 6.0)).toInt().coerceIn(0, 5) +
                6 * (y / (MapProjector.MAP_HEIGHT / 3.0)).toInt().coerceIn(0, 2)
        }.toSortedMap().values.map { cell ->
            java.util.ArrayDeque(cell.sortedBy { (index, _) -> priority(index) }.map { it.second })
        }
        val result = mutableListOf<IntArray>()
        while (groups.any { it.isNotEmpty() }) {
            for (group in groups) {
                if (group.isEmpty()) continue
                val runs = group.removeFirst()
                if (runs.sumOf { it.size - 1 } > budget - result.size) continue
                for (run in runs) for ((a, b) in run.zipWithNext()) {
                    result.add(intArrayOf(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt()))
                }
                if (result.size == budget) return result
            }
        }
        return result
    }
}
