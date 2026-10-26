package com.example.displayconnect.navigation

/** WGS84 ellipsoid height only. Never substitutes mean sea level height. */
class AltitudeFilter {
    private val samples = ArrayDeque<Double>()
    private var lastMs: Long? = null

    fun reset() { samples.clear(); lastMs = null }

    fun update(heightM: Double?, uncertaintyM: Float?, fixMs: Long, nowMs: Long): Int? {
        val gap = lastMs?.let { fixMs - it }
        if (heightM == null || !heightM.isFinite() || heightM !in -1500.0..20000.0 ||
            nowMs - fixMs !in 0..3000 ||
            (uncertaintyM != null && (!uncertaintyM.isFinite() || uncertaintyM < 0f || uncertaintyM > 50f))) {
            reset()
            return null
        }
        if (gap != null && (gap <= 0 || gap > 3000)) samples.clear()
        lastMs = fixMs
        samples.addLast(heightM)
        while (samples.size > 3) samples.removeFirst()
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return (kotlin.math.round(median / 5.0) * 5).toInt()
    }
}
