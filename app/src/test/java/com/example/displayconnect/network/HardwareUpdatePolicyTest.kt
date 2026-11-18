package com.example.displayconnect.network
import org.junit.Test
import org.junit.Assert.*
class HardwareUpdatePolicyTest {
 @Test fun explicitAndLegacyIdentity() {
  assertEquals(HardwareUpdatePolicy.C,HardwareUpdatePolicy.resolve(null,"0.24.0-shadow-r3"))
  assertEquals(HardwareUpdatePolicy.G,HardwareUpdatePolicy.resolve(HardwareUpdatePolicy.G,"0.24.0-shadow-r3"))
  assertNull(HardwareUpdatePolicy.resolve("unknown","0.24.0-shadow-r3"))
  assertNull(HardwareUpdatePolicy.resolve(null,"0.24.0-175g-prep1"))
 }
 @Test fun rejectGChannelAndWrongBinary() {
  assertTrue(runCatching { HardwareUpdatePolicy.requireLegacyOtaTarget(HardwareUpdatePolicy.G) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireCImage(ByteArray(0)) }.isFailure)
  val b=ByteArray(256);b[0]=0xe9.toByte()
  intArrayOf(0x32,0x54,0xcd,0xab).forEachIndexed { i,v -> b[32+i]=v.toByte() }
  "hecate-175g".toByteArray().copyInto(b,80)
  assertTrue(runCatching { HardwareUpdatePolicy.requireCImage(b) }.isFailure)
  for(i in 80..111)b[i]=0
  "muse-gadget".toByteArray().copyInto(b,80)
  HardwareUpdatePolicy.requireCImage(b)
 }
}
