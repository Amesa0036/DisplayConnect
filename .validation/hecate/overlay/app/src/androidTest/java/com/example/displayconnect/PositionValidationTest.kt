package com.example.displayconnect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.ui.components.RemoteFirmwareConfirmDialog
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import com.example.displayconnect.network.GnssPositionPresentation
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PositionValidationTest {
 @get:Rule val compose=createAndroidComposeRule<ValidationActivity>()
 private fun screenshot(name:String) {
  compose.waitForIdle()
  val committed=java.util.concurrent.CountDownLatch(1)
  compose.runOnUiThread {
   val view=compose.activity.window.decorView
   view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
   view.invalidate()
  }
  assertTrue("No committed frame for $name",committed.await(10,java.util.concurrent.TimeUnit.SECONDS))
  val i=InstrumentationRegistry.getInstrumentation()
  i.waitForIdleSync()
  val bitmap=requireNotNull(i.uiAutomation.takeScreenshot())
  File(i.targetContext.getExternalFilesDir(null),name).outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)) }
  // Exclude system bars. A semantic node alone is not visual evidence.
  var ink=0
  for(y in 80 until bitmap.height-80) for(x in 16 until bitmap.width-16) {
   val color=bitmap.getPixel(x,y)
   if(android.graphics.Color.red(color)<180&&android.graphics.Color.green(color)<180&&android.graphics.Color.blue(color)<180)ink++
  }
  assertTrue("Blank content capture $name: $ink dark pixels",ink>=100)
 }
 private fun frame()=JSONObject().put("protocol",1).put("source","lc76g").put("ok",true).put("reader",true).put("supported",true).put("running",true).put("valid_fix",true).put("quality",1).put("satellites",8).put("latitude",0.0).put("longitude",0.0).put("hdop",1.0).put("fix_age_ms",100).put("utc_unix_ms",System.currentTimeMillis()).put("altitude_msl_m",0).put("reason","running").put("responded",true).apply { for(k in listOf("sentences","gga_count","checksum_errors","malformed","overlong","i2c_errors","bytes"))put(k,0) }
 @Test fun confirmationCancelAndAcceptAreExplicit() {
  var sent=0;var cancelled=0
  compose.setContent { DisplayConnectTheme { RemoteFirmwareConfirmDialog("0.28.25-175g-ota1-fix-LOCAL",655360,"Hecate",true,"baseline",onCancel={cancelled++},onConfirm={sent++}) } }
  compose.onNodeWithText("SOLO DIAGNOSTICO",substring=true).assertExists()
  compose.onNodeWithText("Cancelar").assertIsDisplayed()
  compose.onNodeWithText("Aceptar y enviar prueba").assertIsDisplayed()
  screenshot("position-confirm.png")
  compose.onNodeWithText("Cancelar").performClick()
  compose.runOnIdle { assertEquals(1,cancelled);assertEquals(0,sent) }
  compose.onNodeWithText("Aceptar y enviar prueba").performClick()
  compose.runOnIdle { assertEquals(1,sent) }
 }
 @Test fun syntheticPositionRejectedAndStaleText() {
  val wall=System.currentTimeMillis();val good=GnssPositionPresentation.parse(frame().put("utc_unix_ms",wall),10000,wall)
  val text=mutableStateOf(good.display(10000))
  compose.setContent { DisplayConnectTheme { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) { Text("DATOS SINTETICOS, NO GPS REAL");Text(text.value) } } }
  compose.onNodeWithText("Latitud:",substring=true).assertIsDisplayed()
  val root=compose.onRoot().captureToImage().asAndroidBitmap()
  File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"position-valid-root.png").outputStream().use { check(root.compress(Bitmap.CompressFormat.PNG,100,it)) }
  screenshot("position-valid-synthetic.png")
  compose.runOnIdle { text.value=good.display(14001) }
  compose.onNodeWithText("caducada",substring=true).assertExists();compose.onNodeWithText("Latitud:",substring=true).assertDoesNotExist();screenshot("position-stale.png")
  compose.runOnIdle { text.value=GnssPositionPresentation.parse(frame().put("utc_unix_ms",wall-10000),10000,wall).message }
  compose.onNodeWithText("Sin posición válida",substring=true).assertExists();compose.onNodeWithText("Latitud:",substring=true).assertDoesNotExist();screenshot("position-rejected.png")
 }
}
