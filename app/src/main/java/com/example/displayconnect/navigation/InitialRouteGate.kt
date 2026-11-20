package com.example.displayconnect.navigation

import com.example.displayconnect.routing.RouteProgressCalculator
import com.example.displayconnect.routing.TripProgress
import kotlin.math.abs

/** Conservative initial association only. Latches uncertainty until an explicit fresh choice.
 * Coordinates and reported speed cannot prove physical movement. No timer grants confidence. */
class InitialRouteGate {
    var confirmed = false; private set
    var awaitingChoice = false; private set
    fun reset() { confirmed=false; awaitingChoice=false }
    fun assess(candidates: List<RouteProgressCalculator.InitialCandidate>, preview: TripProgress,
        accuracyM: Float): String? {
        if (confirmed) return null
        if (awaitingChoice) return "choice_required"
        val best=candidates.firstOrNull()
        val reason=when {
            best==null || preview.remainingDistanceM==null -> "no_association"
            preview.remainingDistanceM<=maxOf(15.0,2*accuracyM.toDouble()) -> "initial_arrival_unconfirmed"
            candidates.any { abs(it.remainingM-best.remainingM)>maxOf(30.0,2*accuracyM.toDouble()) } -> "multiple_branches"
            best.distanceM>maxOf(20.0,1.5*accuracyM) -> "initial_lateral_uncertainty"
            else -> null
        }
        if(reason==null) confirmed=true else awaitingChoice=true
        return reason
    }
    fun confirmExplicit(segment: Int, candidates: List<RouteProgressCalculator.InitialCandidate>): Boolean {
        if(!awaitingChoice || candidates.none { it.segment==segment }) return false
        confirmed=true;awaitingChoice=false;return true
    }
}
