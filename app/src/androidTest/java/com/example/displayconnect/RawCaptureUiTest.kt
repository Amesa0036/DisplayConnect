package com.example.displayconnect

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.navigation.*
import com.example.displayconnect.ui.components.RawCaptureStatus
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic notice fixture, no board. Does not substitute full Activity screenshots. */
class RawCaptureUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun allNoticesAndStopControls() {
  val state=mutableStateOf(RawCaptureNotice())
  compose.setContent { DisplayConnectTheme { Surface {
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Diagnostico GPS: prueba sintetica",style=MaterialTheme.typography.titleLarge)
    Text(RawCapturePresentation.EXPLANATION)
    RawCaptureStatus(state.value)
    Button(onClick={},enabled=!state.value.copying){Text("Empezar")}
    Button(onClick={},enabled=!state.value.copying){Text("Guardar copia del registro")}
    Button(onClick={}){Text("Parar GPS")}
   }
  } } }
  val cases=listOf(
   "capturing" to Pair("gnss_raw_capability",mapOf<String,Any?>("available" to true,"current" to true)),
   "copying" to Pair("gnss_raw_transfer_start",emptyMap()),
   "received" to Pair("gnss_raw_complete",mapOf("capture_complete" to true)),
   "partial" to Pair("gnss_raw_complete",mapOf("capture_complete" to false)),
   "failed" to Pair("gnss_raw_failure",emptyMap()),
   "no-memory" to Pair("gnss_raw_capability",mapOf("available" to false)),
   "second-session" to Pair("gnss_raw_capability",mapOf("available" to true,"current" to false)),
   "legacy" to Pair("gnss_raw_unsupported",emptyMap()))
  for((name,event) in cases) {
   compose.runOnIdle {state.value=RawCapturePresentation.update(RawCaptureNotice(),event.first,event.second)}
   compose.onNodeWithText(state.value.text).performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("Parar GPS").performScrollTo().assertIsEnabled()
   if(state.value.copying) {
    compose.onNodeWithText("Empezar").assertIsNotEnabled()
    compose.onNodeWithText("Guardar copia del registro").assertIsNotEnabled()
   }
   compose.onNodeWithText("Diagnostico GPS: prueba sintetica").performScrollTo()
   capture("raw-$name.png")
  }
 }
 @Test fun actualSettingsCopyingAndPartial() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DisplayConnectApp
  val sink=app.navigationTrace.newSession()
  sink.record("gnss_raw_transfer_start",emptyMap())
  try {
   compose.setContent {DisplayConnectTheme {com.example.displayconnect.ui.screens.SettingsScreen(onNavigateBack={},section="advanced")}}
   compose.onNodeWithText("GPS parado.",substring=true).performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("Guardar copia del registro").performScrollTo().assertIsNotEnabled()
   compose.onNodeWithText("Borrar registro privado").assertIsNotEnabled()
   capture("raw-settings-copying.png")
   compose.runOnIdle{sink.record("gnss_raw_complete",mapOf("capture_complete" to false))}
   compose.onNodeWithText("Captura parcial:",substring=true).performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("Guardar copia del registro").performScrollTo().assertIsEnabled()
   capture("raw-settings-partial.png")
  } finally {sink.record("gnss_raw_complete",mapOf("capture_complete" to true))}
 }
 private fun capture(name:String) {
  compose.waitForIdle();compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
  android.os.SystemClock.sleep(250)
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  val f=File(c.getExternalFilesDir(null),name)
  f.outputStream().use{i.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
  val d=i.uiAutomation.executeShellCommand("cp ${f.absolutePath} /data/local/tmp/$name")
  android.os.ParcelFileDescriptor.AutoCloseInputStream(d).use{it.readBytes()}
 }
}
