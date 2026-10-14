package com.example.displayconnect.routing

import kotlin.math.*

/** Constrain only a fresh, moving GPS fix. Never use a compass at standstill. */
object ForwardReroute {
    fun reliableBearing(bearing: Float, speedMps: Float, accuracyM: Float): Float? =
        bearing.takeIf { it.isFinite() && speedMps.isFinite() && speedMps >= 2.5f &&
            accuracyM.isFinite() && accuracyM in 0f..20f }
            ?.let { ((it % 360f) + 360f) % 360f }

    fun bearingOption(bearing: Float?): String? = bearing?.takeIf { it.isFinite() }?.let {
        val degrees = ((((it % 360f) + 360f) % 360f).roundToInt()) % 360
        "&bearings=$degrees,60;&continue_straight=true"
    }

    fun accepts(points: List<LatLon>, bearing: Float?): Boolean {
        if (bearing == null || !bearing.isFinite()) return true
        if (points.size < 2) return false
        // Look along the departure, not straight to the destination.
        // A real corner is allowed; a route starting back along our street is not.
        val origin = points.first()
        val target = points.drop(1).firstOrNull { distance(origin, it) >= 15.0 } ?: return true
        val a = Math.toRadians(origin.lat); val b = Math.toRadians(target.lat)
        val dl = Math.toRadians(target.lon - origin.lon)
        val course = Math.toDegrees(atan2(sin(dl)*cos(b), cos(a)*sin(b)-sin(a)*cos(b)*cos(dl)))
        val delta = abs(((course - bearing + 540.0) % 360.0) - 180.0)
        return delta <= 110.0
    }

    private fun distance(a: LatLon, b: LatLon): Double {
        val x = Math.toRadians(b.lon-a.lon)*cos(Math.toRadians((a.lat+b.lat)/2))
        val y = Math.toRadians(b.lat-a.lat)
        return hypot(x,y)*6371000.0
    }
}
