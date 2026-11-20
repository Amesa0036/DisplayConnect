package com.example.displayconnect.network
import org.junit.Assert.*
import org.junit.Test
class NavigationDisplayCapabilityTest {
 @Test fun explicitFalseDisablesNavigationFrames() { assertFalse(navigationDisplayCapability(false)) }
 @Test fun legacyAndExplicitTruePreserveNavigationFrames() {
  assertTrue(navigationDisplayCapability(null));assertTrue(navigationDisplayCapability(true))
  assertTrue(BoardInfo("id","name","address").navigationDisplay)
 }
 @Test fun malformedCapabilityDoesNotChangeLegacyBehaviour() {
  assertTrue(navigationDisplayCapability("false"));assertTrue(navigationDisplayCapability(0))
 }
}
