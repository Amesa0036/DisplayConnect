package com.example.displayconnect.routing

import kotlin.math.ceil

data class NextManeuver(val step: RouteStep, val distanceM: Int)

/** Uses ordered OSRM step offsets, never the geographically nearest turn.
 * Keeps a maneuver until 3 m past its ordered offset; no nearest-point search.
 * Missing metadata/off-route returns unknown instead of inventing a turn. */
object ManeuverSelector {
    fun next(route: RouteData, remainingM: Int?, offRoute: Boolean): NextManeuver? {
        if (offRoute || remainingM == null || route.distanceM <= 0) return null
        val traveled = (route.distanceM - remainingM).coerceIn(0.0, route.distanceM)
        val candidates = route.steps.filter {
            it.maneuverType.isNotBlank() && it.maneuverType != "depart" && it.maneuverAtM != null
        }
        val step = candidates.firstOrNull { it.maneuverAtM!! >= traveled - 3.0 }
            ?: candidates.lastOrNull { it.maneuverType == "arrive" }
            ?: return null
        return NextManeuver(step, ceil((step.maneuverAtM!! - traveled).coerceAtLeast(0.0)).toInt())
    }

    fun code(step: RouteStep): String {
        val type = step.maneuverType
        val modifier = step.maneuverModifier
        if (type == "arrive") return "arrive"
        if (modifier == "uturn" || type == "uturn") return "uturn"
        if (type == "roundabout" || type == "rotary" || type == "roundabout turn") return "roundabout"
        return when (modifier) {
            "left" -> "left"
            "right" -> "right"
            "slight left" -> "slight_left"
            "slight right" -> "slight_right"
            "sharp left" -> "sharp_left"
            "sharp right" -> "sharp_right"
            "straight" -> "straight"
            else -> if (type in listOf("new name", "continue", "notification", "depart")) "straight" else "unknown"
        }
    }
}
