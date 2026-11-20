package com.example.displayconnect.navigation
import org.junit.Assert.assertEquals
import org.junit.Test
class FirstPositionTimeoutTest {
 @Test fun coldHecateAllowsThreeMinutes() { assertEquals(180000L, firstPositionTimeoutMs(true)) }
 @Test fun phoneDeadlineIsUnchanged() { assertEquals(45000L, firstPositionTimeoutMs(false)) }
}
