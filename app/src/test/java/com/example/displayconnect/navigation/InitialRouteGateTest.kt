package com.example.displayconnect.navigation

import com.example.displayconnect.routing.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

/** Synthetic geometry only. Real traces must stay out of source control and CI. */
class InitialRouteGateTest {
    private fun p(x:Double,y:Double)=LatLon(y/6371000.0*180/PI,x/6371000.0*180/PI)
    private val line=RouteData(listOf(p(0.0,0.0),p(500.0,0.0)),emptyList(),500.0,120.0)
    private val loop=RouteData(listOf(p(0.0,0.0),p(200.0,0.0),p(200.0,8.0),p(0.0,8.0)),emptyList(),408.0,120.0)
    @Test fun uniqueStartConfirmsWithoutTimer() {
        val calc=RouteProgressCalculator(line);val pos=p(20.0,0.0);val gate=InitialRouteGate()
        assertNull(gate.assess(calc.initialCandidates(pos,10f),calc.update(pos,commit=false),10f))
        assertTrue(gate.confirmed)
    }
    @Test fun arrivalWithoutHistoryRequiresChoice() {
        val calc=RouteProgressCalculator(line);val pos=p(498.0,0.0);val gate=InitialRouteGate()
        assertEquals("initial_arrival_unconfirmed",gate.assess(calc.initialCandidates(pos,10f),calc.update(pos,commit=false),10f))
        assertFalse(gate.confirmed)
    }
    @Test fun opposingBranchesAreNotMergedBySpatialProximity() {
        val calc=RouteProgressCalculator(loop);val pos=p(20.0,3.0)
        val options=calc.initialCandidates(pos,10f)
        assertTrue(options.maxOf{it.remainingM}-options.minOf{it.remainingM}>300)
        val gate=InitialRouteGate()
        assertEquals("multiple_branches",gate.assess(options,calc.update(pos,commit=false),10f))
        assertFalse(gate.confirmed)
    }
    @Test fun ambiguityDoesNotResolveBecauseLaterDriftLooksUnique() {
        val calc=RouteProgressCalculator(loop);val pos=p(20.0,3.0);val gate=InitialRouteGate()
        gate.assess(calc.initialCandidates(pos,10f),calc.update(pos,commit=false),10f)
        val later=p(100.0,-30.0)
        repeat(100){assertEquals("choice_required",gate.assess(calc.initialCandidates(later,10f),calc.update(later,commit=false),10f))}
        assertFalse(gate.confirmed)
    }
    @Test fun explicitChoiceMustStillExist() {
        val calc=RouteProgressCalculator(loop);val pos=p(20.0,3.0);val gate=InitialRouteGate()
        val options=calc.initialCandidates(pos,10f)
        gate.assess(options,calc.update(pos,commit=false),10f)
        assertFalse(gate.confirmExplicit(999,options))
        assertTrue(gate.confirmExplicit(options.first().segment,options))
        assertTrue(gate.confirmed)
    }
    @Test fun previewDoesNotCommitAndExplicitChoiceIsSingleUpdate() {
        val calc=RouteProgressCalculator(loop);val pos=p(20.0,3.0)
        val options=calc.initialCandidates(pos,10f)
        val beginning=options.maxBy{it.remainingM}
        val selected=calc.update(pos,commit=false,explicitInitialSegment=beginning.segment)
        assertTrue(selected.remainingDistanceM!!>350)
        assertEquals(beginning.segment,selected.matchedSegment)
    }
    @Test fun routeResetInvalidatesPreviousConfidence() {
        val gate=InitialRouteGate();val calc=RouteProgressCalculator(line);val pos=p(20.0,0.0)
        gate.assess(calc.initialCandidates(pos,10f),calc.update(pos,commit=false),10f)
        gate.reset();assertFalse(gate.confirmed);assertFalse(gate.awaitingChoice)
    }
    @Test fun invalidCoordinatesHaveNoChoices() {
        val calc=RouteProgressCalculator(line)
        assertTrue(calc.initialCandidates(LatLon(Double.NaN,0.0),10f).isEmpty())
        assertTrue(calc.initialCandidates(p(0.0,0.0),Float.NaN).isEmpty())
    }
}
