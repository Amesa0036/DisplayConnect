package com.example.displayconnect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.displayconnect.ui.components.*
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
class FirmwareChannelsUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun trialRequiresAcceptAndCancelDoesNotSend(){
  val visible=mutableStateOf(false);var fetched=0;var sent=0
  compose.setContent{DisplayConnectTheme{
   FirmwareUpdateCard(true,true,false,-1,null,false,allowTrial=true,onTrial={fetched++;visible.value=true},onUpdate={})
   if(visible.value)RemoteFirmwareConfirmDialog("0.28.23-175g-ota1-trial-LOCAL",659456,"Hecate",true,"0.28.21-175g-ota1-LOCAL",onCancel={visible.value=false},onConfirm={sent++;visible.value=false})
  }}
  compose.onNodeWithText("Buscar prueba Hecate").performClick()
  compose.runOnIdle{assertEquals(1,fetched);assertEquals(0,sent)}
  compose.onNodeWithText("Instalar prueba Hecate").assertIsDisplayed()
  compose.onNodeWithText("SOLO DIAGNOSTICO",substring=true).assertExists()
  compose.waitForIdle()
  val bmp=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val file=File(ctx.getExternalFilesDir(null),"hecate-trial-confirm.png")
  file.outputStream().use{check(bmp.compress(Bitmap.CompressFormat.PNG,100,it))}
  val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/hecate-trial-confirm.png")
  android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use{it.readBytes()}
  val checked=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("ls -l /data/local/tmp/hecate-trial-confirm.png")
  val listing=android.os.ParcelFileDescriptor.AutoCloseInputStream(checked).use{String(it.readBytes())}
  check(listing.contains("hecate-trial-confirm.png")&&!listing.contains("No such"))
  compose.onNodeWithText("Cancelar").performClick()
  compose.runOnIdle{assertEquals(0,sent)}
  compose.onNodeWithText("Buscar prueba Hecate").performClick()
  compose.onNodeWithText("Aceptar y enviar prueba").performClick()
  compose.runOnIdle{assertEquals(1,sent)}
 }
}
