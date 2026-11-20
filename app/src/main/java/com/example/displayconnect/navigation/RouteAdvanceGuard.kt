package com.example.displayconnect.navigation

import com.example.displayconnect.routing.LatLon
import kotlin.math.*

/** Heuristics, not a detector proving physical motion. No time-derived travel budget.
 * Previewed route matches MUST NOT be committed when this guard rejects them. */
class RouteAdvanceGuard {
    data class Fix(val position: LatLon, val accuracyM: Float, val speedMps: Float, val elapsedMs: Long)
    data class Decision(val accept: Boolean, val reason: String, val displacementM: Double,
        val alongDeltaM: Int?, val motionEvidence: Boolean, val warn: Boolean)
    private var anchor: Fix? = null
    private var remaining: Int? = null
    private var previous: Fix? = null
    private var speedVotes = 0
    private var positionVotes = 0

    fun reset() { anchor = null; remaining = null; previous = null; speedVotes = 0; positionVotes = 0 }

    fun evaluate(fix: Fix, candidateRemainingM: Int?): Decision {
        if (!fix.position.lat.isFinite() || !fix.position.lon.isFinite() || fix.position.lat !in -90.0..90.0 ||
            fix.position.lon !in -180.0..180.0 || !fix.accuracyM.isFinite() || fix.accuracyM !in 0f..40f)
            return Decision(false,"invalid_fix",0.0,null,false,true)
        val base = anchor
        if (base == null) {
            commit(fix, candidateRemainingM)
            return Decision(true, "initial", 0.0, null, false, false)
        }
        val prior = previous
        val dt = prior?.let { fix.elapsedMs - it.elapsedMs } ?: 0
        val displacement = GpsFilter.distanceM(base.position, fix.position)
        val accuracy = maxOf(base.accuracyM, fix.accuracyM).toDouble().coerceIn(1.0,40.0)
        val delta = remaining?.let { old -> candidateRemainingM?.let { old - it } }
        if (dt <= 0) return Decision(false,"nonmonotonic_fix",displacement,delta,false,true)
        if (dt > 5000) {
            previous = fix; speedVotes = 0; positionVotes = 0
            return Decision(false, "reacquiring", displacement, delta, false, true)
        }
        val step = GpsFilter.distanceM(prior!!.position, fix.position)
        speedVotes = if (fix.speedMps.isFinite() && fix.speedMps >= 0.8f && step >= 0.2) speedVotes + 1 else 0
        val prevRadius = GpsFilter.distanceM(base.position, prior.position)
        val a = vector(base.position, prior.position); val b = vector(base.position, fix.position)
        val dot = a.first*b.first+a.second*b.second
        val denom = hypot(a.first,a.second)*hypot(b.first,b.second)
        val coherent = displacement > prevRadius + 0.15 && (denom < 0.01 || dot/denom >= 0.85)
        positionVotes = if (coherent) positionVotes + 1 else 0
        previous = fix
        // Coordinate evidence permits slow movement or missing/zero receiver speed.
        // A coherent bad solution can still mimic movement: retain diagnostics.
        val moving = (speedVotes >= 3 && displacement >= maxOf(3.0, accuracy)) ||
            (positionVotes >= 3 && displacement >= maxOf(12.0, 2*accuracy))
        val allowance = 2*displacement + maxOf(12.0, 2*accuracy)
        if (delta != null && abs(delta.toDouble()) > allowance) {
            return Decision(false, "route_jump", displacement, delta, moving, true)
        }
        if (!moving) {
            return Decision(false, "motion_unconfirmed", displacement, delta, false,
                delta != null && abs(delta) > maxOf(10.0, accuracy))
        }
        commit(fix, candidateRemainingM)
        return Decision(true, "motion_confirmed", displacement, delta, true, false)
    }

    private fun commit(fix: Fix, candidateRemainingM: Int?) {
        anchor = fix; previous = fix; remaining = candidateRemainingM
        speedVotes = 0; positionVotes = 0
    }
    private fun vector(a: LatLon,b: LatLon): Pair<Double,Double> =
        ((b.lon-a.lon)*111320*cos(Math.toRadians((a.lat+b.lat)/2))) to ((b.lat-a.lat)*111320)
}
