package com.example.displayconnect.routing

import org.junit.Assert.*
import org.junit.Test

class RouteProfileTest {
    @Test fun storedCarMigratesToMotorcycleWithoutDeletingAnything() {
        assertEquals(RouteProfile.MOTORCYCLE, RouteProfile.fromStorage("car"))
        assertEquals(RouteProfile.MOTORCYCLE, RouteProfile.fromStorage(null))
        assertEquals(RouteProfile.MOTORCYCLE, RouteProfile.fromStorage("unknown"))
        assertEquals(RouteProfile.WALKING, RouteProfile.fromStorage("walking"))
        assertEquals(RouteProfile.BIKE, RouteProfile.fromStorage("bike"))
        // CAR stays available internally (speed limits, offline keys, GPX).
        assertEquals("driving", RouteProfile.CAR.osrmProfile)
    }
}
