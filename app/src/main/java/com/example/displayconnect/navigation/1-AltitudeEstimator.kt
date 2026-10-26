package com.example.displayconnect.navigation

/** Barometer follows relative changes; its absolute anchor is still approximate GPS MSL. */
class AltitudeEstimator {
    data class Reading(val metres: Double?, val reference: String, val source: String, val accuracy: Float?, val notice: String)
    private var anchorPressure: Float? = null
    private var anchorHeight = 0.0
    private var anchorTime = 0L
    private var stableFixes = 0
    private var lastFix = -1L
    private var lastRef: String? = null
    private var lastSource: String? = null
    val filter = AltitudeFilter()
    fun reset() { anchorPressure = null; stableFixes = 0; lastFix = -1L; lastRef = null; lastSource = null; filter.reset() }
    fun update(msl: Double?, wgs84: Double?, error: Float?, pressure: Float?, pressureReady: Boolean,
               now: Long, fix: Long, pressureNotice: String): Reading {
        val mslValid=msl?.takeIf { it.isFinite() && it in -1500.0..20000.0 }
        val ref = if (mslValid != null) "msl" else "wgs84"
        val gps = (mslValid ?: wgs84)?.takeIf { it.isFinite() && it in -1500.0..20000.0 && now-fix in 0..3000 &&
            (error == null || (error.isFinite() && error in 0f..50f)) }
        var value = gps
        var source = "gps"
        var notice = pressureNotice
        val usable = pressureReady && pressure != null && pressure.isFinite() && pressure in 300f..1100f
        if (gps == null || !usable || ref != "msl") {
            anchorPressure = null; stableFixes = 0
            if (usable) notice = if (ref != "msl") "Barometro disponible; GPS aprox. sin conversion al nivel del mar" else "Sin altura GPS valida para calibrar"
        } else {
            if (anchorPressure != null && now-anchorTime > 30*60*1000) { anchorPressure = null; stableFixes = 0 }
            if (anchorPressure == null) {
                if (fix > lastFix) stableFixes = if (error != null && error.isFinite() && error in 0f..15f) stableFixes+1 else 0
                notice = "Barometro con datos; usando GPS hasta calibrar (5 lecturas GPS MSL con error estimado <=15 m)"
                if (stableFixes >= 5) { anchorPressure = pressure; anchorHeight = gps; anchorTime = now }
            }
            anchorPressure?.let { p0 ->
                val candidate = anchorHeight + 44330.0*(1.0-Math.pow((pressure!!/p0).toDouble(),0.190294957))
                if (kotlin.math.abs(candidate-gps) <= 30.0) {
                    value = candidate; source = "gps_baro"
                    notice = "GPS + barometro, calibrado con GPS aprox.; no es una cota exacta"
                    // Slow correction, never switch the datum to weather-standard pressure.
                    anchorHeight += (gps-candidate)*0.03
                } else {
                    anchorPressure = null; stableFixes = 0
                    notice = "Barometro no concuerda con GPS; usando GPS aprox. y recalibrando"
                }
            }
        }
        lastFix=fix
        if (ref != lastRef || source != lastSource) filter.reset()
        lastRef=ref;lastSource=source
        return Reading(value, ref, source, error, notice)
    }
}
