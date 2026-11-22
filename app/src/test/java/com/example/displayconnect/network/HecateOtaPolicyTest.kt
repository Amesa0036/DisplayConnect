package com.example.displayconnect.network
import org.junit.Test
import org.junit.Assert.*
class HecateOtaPolicyTest {
 private fun image(project: String = "hecate-175g", version: String = "0.28.20-175g-ota1-LOCAL"): ByteArray = ByteArray(256).also { b ->
  b[0]=0xe9.toByte();intArrayOf(0x32,0x54,0xcd,0xab).forEachIndexed { i,v -> b[32+i]=v.toByte() }
  project.toByteArray().copyInto(b,80);version.toByteArray().copyInto(b,48)
 }
 @Test fun modelChannelsStaySeparate() {
  assertEquals("firmware.json",HardwareUpdatePolicy.manifestName(HardwareUpdatePolicy.C))
  assertEquals("firmware-hecate-175g.json",HardwareUpdatePolicy.manifestName(HardwareUpdatePolicy.G))
  assertTrue(runCatching { HardwareUpdatePolicy.manifestName(null) }.isFailure)
 }
 @Test fun capabilityIsExplicitForG() {
  HardwareUpdatePolicy.requireOtaTarget(HardwareUpdatePolicy.G,true)
  for(enabled in listOf(null,false)) assertTrue(runCatching { HardwareUpdatePolicy.requireOtaTarget(HardwareUpdatePolicy.G,enabled) }.isFailure)
  HardwareUpdatePolicy.requireOtaTarget(HardwareUpdatePolicy.C,null)
  assertTrue(runCatching { HardwareUpdatePolicy.requireOtaTarget(HardwareUpdatePolicy.C,false) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireOtaTarget(null,true) }.isFailure)
 }
 @Test fun imagesNeverCrossFamiliesOrLoseOta() {
  HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,image(),"0.28.20-175g-ota1-LOCAL")
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.C,image()) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,image("muse-gadget")) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,image(version="0.28.19-175g-rawdiag-LOCAL")) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,image(),"other") }.isFailure)
  val b=image();for(i in 48..79)b[i]='X'.code.toByte()
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,b) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G,ByteArray(0x400001)) }.isFailure)
 }
 @Test fun manifestsRequireGIdentityAndGeneration() {
  val g=HardwareUpdatePolicy.G;val sha="a".repeat(64)
  HardwareUpdatePolicy.requireManifest(g,g,"hecate-175g","ota1","0.28.20-175g-ota1-LOCAL",655360,sha)
  assertTrue(runCatching { HardwareUpdatePolicy.requireManifest(g,null,null,null,"0.28.20-175g-ota1-LOCAL",655360,sha) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireManifest(g,HardwareUpdatePolicy.C,"muse-gadget","ota1","0.28.20-175g-ota1-LOCAL",655360,sha) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireManifest(g,g,"hecate-175g","ota2","0.28.20-175g-ota1-LOCAL",655360,sha) }.isFailure)
  assertTrue(runCatching { HardwareUpdatePolicy.requireManifest(g,g,"hecate-175g","ota1","0.28.20-175g-ota1-LOCAL",655360,"Z".repeat(64)) }.isFailure)
  HardwareUpdatePolicy.requireManifest(HardwareUpdatePolicy.C,null,null,null,"0.24.0-shadow-r3",655360,sha)
 }
 @Test fun acksAreExactAndCannotJumpOrTruncate() {
  HardwareUpdatePolicy.requireAck("OTA:ACK 4096",4096)
  for(bad in listOf("OTA:ACK","OTA:ACK 4","OTA:ACK 8192","OTA:ACK 4096 extra","OTA:OK"))
   assertTrue(runCatching { HardwareUpdatePolicy.requireAck(bad,4096) }.isFailure)
 }
}
