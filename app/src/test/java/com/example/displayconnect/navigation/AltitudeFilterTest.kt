package com.example.displayconnect.navigation
import org.junit.Assert.*
import org.junit.Test
class AltitudeFilterTest {
    @Test fun validityAndNegativeHeight() {
        val f=AltitudeFilter()
        assertEquals(-10, f.update(-12.0, 5f,1000,1000))
        assertNull(f.update(null, 5f,2000,2000))
        assertNull(f.update(Double.NaN, 5f,2000,2000))
        assertNull(f.update(100.0, 51f,2000,2000))
        assertNull(f.update(100.0, Float.NaN,2000,2000))
        assertNull(f.update(100.0, 5f,2000,6000))
        assertNull(f.update(100.0, 5f,6001,6000))
        assertEquals(100, f.update(101.0, null,6000,6000))
    }
    @Test fun medianAndGaps() {
        val f=AltitudeFilter()
        assertEquals(100,f.update(100.0,5f,1000,1000))
        f.update(102.0,5f,1500,1500)
        assertEquals(100,f.update(900.0,5f,2000,2000))
        assertEquals(300,f.update(300.0,5f,6000,6000))
        f.reset()
        assertEquals(500,f.update(500.0,5f,6500,6500))
    }
}
