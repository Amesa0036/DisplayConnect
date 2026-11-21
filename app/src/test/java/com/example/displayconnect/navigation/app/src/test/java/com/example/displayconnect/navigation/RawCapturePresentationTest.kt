package com.example.displayconnect.navigation
import org.junit.Assert.*
import org.junit.Test
class RawCapturePresentationTest {
 @Test fun statesDoNotClaimPersistence() {
  var s=RawCaptureNotice()
  s=RawCapturePresentation.update(s,"gnss_raw_capability",mapOf("available" to true,"current" to true))
  assertTrue(s.text.contains("placa"));assertFalse(s.copying)
  s=RawCapturePresentation.update(s,"gnss_raw_transfer_start",emptyMap())
  assertTrue(s.copying);assertTrue(s.text.startsWith("GPS parado"));assertTrue(s.text.contains("90 s"))
  s=RawCapturePresentation.update(s,"gnss_raw_complete",mapOf("capture_complete" to true))
  assertFalse(s.copying);assertTrue(s.text.contains("Falta comprobar"))
  s=RawCapturePresentation.update(s,"gnss_raw_complete",mapOf("capture_complete" to false))
  assertTrue(s.text.contains("parcial"));assertTrue(s.text.contains("No es un registro completo"))
 }
 @Test fun failuresAndLegacyAreDistinct() {
  val s=RawCaptureNotice()
  assertTrue(RawCapturePresentation.update(s,"gnss_raw_failure",emptyMap()).text.contains("No se borro"))
  assertTrue(RawCapturePresentation.update(s,"gnss_raw_unsupported",emptyMap()).text.contains("registro habitual"))
  assertTrue(RawCapturePresentation.update(s,"gnss_raw_capability",mapOf("available" to false)).text.contains("reservar"))
  assertTrue(RawCapturePresentation.update(s,"gnss_raw_capability",mapOf("available" to true,"current" to false)).text.contains("primera"))
 }
 @Test fun sessionEndCannotLeaveCopyingOrEraseWarning() {
  val busy=RawCapturePresentation.update(RawCaptureNotice(),"gnss_raw_transfer_start",emptyMap())
  val ended=RawCapturePresentation.update(busy,"session_end",emptyMap())
  assertFalse(ended.copying);assertTrue(ended.text.contains("incompleto"))
  assertEquals(ended,RawCapturePresentation.update(ended,"session_end",emptyMap()))
  assertEquals(RawCaptureNotice(),RawCapturePresentation.update(ended,"session_start",emptyMap()))
 }
}
