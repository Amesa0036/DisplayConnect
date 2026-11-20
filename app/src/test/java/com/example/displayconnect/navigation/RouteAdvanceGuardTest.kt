package com.example.displayconnect.navigation

import com.example.displayconnect.routing.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic route and positions only. No user trip data. */
class RouteAdvanceGuardTest {
    private fun p(x:Double,y:Double)=LatLon(y/6371000.0*180/PI,x/6371000.0*180/PI)
    private val route=RouteData(listOf(p(0.0,0.0),p(180.0,0.0),p(180.0,40.0),p(0.0,40.0)),emptyList(),400.0,120.0)
    private fun run(points:List<Pair<Double,Double>>,speed:Float):Pair<Int,List<String>> {
        val guard=RouteAdvanceGuard();val calculator=RouteProgressCalculator(route)
        var shown=0;val reasons=mutableListOf<String>()
        points.forEachIndexed { i,xy ->
            val position=p(xy.first,xy.second);val preview=calculator.update(position,commit=false)
            val d=guard.evaluate(RouteAdvanceGuard.Fix(position,10f,speed,10000L+i*1000),preview.remainingDistanceM)
            if(d.accept){ calculator.update(position);shown=preview.remainingDistanceM!! }
            reasons.add(d.reason)
        }
        return shown to reasons
    }
    @Test fun identicalPositionsDoNotAdvance() {
        assertEquals(379,run(List(601){21.0 to 0.0},0f).first)
    }
    @Test fun routeJumpBetweenCloseBranchesIsNotCommitted() {
        val result=run((0..600).map { val f=it/600.0;(21.0-18*f) to 40*f },0f)
        assertTrue(result.first>300); assertTrue("route_jump" in result.second)
    }
    @Test fun zeroSpeedIsNotAnAbsoluteBlock() {
        assertTrue(run((0..160).map { (21.0+it*0.5) to 0.0 },0f).first<330)
    }
    @Test fun missingSpeedIsNotAnAbsoluteBlock() {
        assertTrue(run((0..160).map { (21.0+it*0.5) to 0.0 },Float.NaN).first<330)
    }
    @Test fun previewDoesNotChangeCommittedSegment() {
        val calc=RouteProgressCalculator(route);calc.update(p(21.0,0.0))
        repeat(30){calc.update(p(3.0,40.0),commit=false)}
        assertEquals(379,calc.update(p(21.0,0.0)).remainingDistanceM)
    }
    @Test fun invalidFixCannotSeedGuard() {
        val guard=RouteAdvanceGuard()
        assertFalse(guard.evaluate(RouteAdvanceGuard.Fix(LatLon(Double.NaN,0.0),5f,0f,1000),1).accept)
        assertEquals("initial",guard.evaluate(RouteAdvanceGuard.Fix(p(0.0,0.0),5f,0f,2000),400).reason)
    }
    @Test fun gapsAndDuplicatesDoNotEarnTravelBudget() {
        val guard=RouteAdvanceGuard();val a=RouteAdvanceGuard.Fix(p(0.0,0.0),5f,0f,1000)
        guard.evaluate(a,400)
        assertFalse(guard.evaluate(a,1).accept)
        assertEquals("reacquiring",guard.evaluate(a.copy(elapsedMs=20000),1).reason)
        assertFalse(guard.evaluate(a.copy(elapsedMs=21000),1).accept)
    }
}
