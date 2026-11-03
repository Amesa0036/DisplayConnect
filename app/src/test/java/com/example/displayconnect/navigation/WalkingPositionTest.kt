package com.example.displayconnect.navigation
import com.example.displayconnect.routing.LatLon
import org.junit.Assert.*
import org.junit.Test
class WalkingPositionTest {
    @Test fun walkingNeverHidesRawPositionOnPlannedRoute() {
        val raw = LatLon(0.0001, 0.0); val route = LatLon(0.0, 0.0)
        assertEquals(raw, GpsFilter.displayPosition(raw, route, 12.0, 15f, 1.3f, Float.NaN, 90f, walking = true))
        assertEquals(route, GpsFilter.displayPosition(raw, route, 12.0, 15f, 1.3f, Float.NaN, 90f))
    }
}
