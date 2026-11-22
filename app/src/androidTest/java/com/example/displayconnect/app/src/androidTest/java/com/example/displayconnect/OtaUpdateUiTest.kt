package com.example.displayconnect

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.network.HardwareUpdatePolicy
import com.example.displayconnect.network.OtaMessages
import com.example.displayconnect.ui.components.FirmwareUpdateCard
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Actual production card in an Android emulator. Presentation fixtures only:
 * no radio, flash, signature verification or physical board is simulated as proof. */
class OtaUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private data class ViewState(val connected:Boolean=false,val can:Boolean=false,val busy:Boolean=false,val progress:Int=-1,val message:String?=null,val usb:Boolean=false)
    @Test fun otaSafetyMessagesAndControlsRenderInAndroid() {
        val state=mutableStateOf(ViewState());var clicks=0
        compose.setContent { DisplayConnectTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=48.dp)) {
                val s=state.value
                FirmwareUpdateCard(s.connected,s.can,s.busy,s.progress,s.message,s.usb){ clicks++ }
            }
        } }
        compose.onNodeWithText("Buscar actualizacion").assertIsNotEnabled()
        compose.onNodeWithText("Conecta la placa primero.").assertIsDisplayed()
        capture("ota-disconnected")

        compose.runOnIdle { state.value=ViewState(connected=true,can=true,usb=true) }
        compose.onNodeWithText("Buscar actualizacion").assertIsNotEnabled()
        compose.onNodeWithText("Esta Hecate necesita primero",substring=true).assertIsDisplayed()
        capture("ota-needs-usb")

        compose.runOnIdle { state.value=ViewState(connected=true,can=true) }
        compose.onNodeWithText("Buscar actualizacion").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1,clicks);state.value=ViewState(true,true,true,-1,OtaMessages.CHECKING) }
        compose.onNodeWithText("Actualizando...").assertIsNotEnabled()
        compose.onNodeWithText(OtaMessages.CHECKING).assertIsDisplayed()
        capture("ota-checking")

        compose.runOnIdle { state.value=ViewState(true,true,true,37,OtaMessages.TRANSFERRING) }
        compose.onNodeWithText("37%").assertIsDisplayed()
        compose.onNodeWithText("Actualizando...").assertIsNotEnabled()
        capture("ota-transferring")

        val accepted=OtaMessages.accepted("0.28.20-175g-ota1-LOCAL")
        compose.runOnIdle { state.value=ViewState(true,true,false,-1,accepted) }
        compose.onNodeWithText(accepted).assertIsDisplayed()
        compose.onNodeWithText("transferido y aceptado",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Buscar actualizacion").assertIsEnabled()
        capture("ota-accepted-not-confirmed")

        compose.runOnIdle { state.value=ViewState(true,true,false,-1,OtaMessages.invalidDownload()) }
        compose.onNodeWithText(OtaMessages.invalidDownload()).assertIsDisplayed()
        capture("ota-invalid-download")

        val failure=OtaMessages.failed("Conexion perdida durante la actualizacion")
        compose.runOnIdle { state.value=ViewState(false,false,false,-1,failure) }
        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithText("Buscar actualizacion").assertIsNotEnabled()
        capture("ota-disconnected-during-transfer")
    }
    @Test fun androidPolicyRejectsForeignImageAndCChannelForG() {
        val g=HardwareUpdatePolicy.G
        assertTrue(runCatching { HardwareUpdatePolicy.requireOtaTarget(g,null) }.isFailure)
        assertTrue(runCatching { HardwareUpdatePolicy.requireOtaTarget(g,false) }.isFailure)
        HardwareUpdatePolicy.requireOtaTarget(g,true)
        assertNotEquals(HardwareUpdatePolicy.manifestName(HardwareUpdatePolicy.C),HardwareUpdatePolicy.manifestName(g))
        val bin=ByteArray(256);bin[0]=0xe9.toByte();intArrayOf(0x32,0x54,0xcd,0xab).forEachIndexed { i,v->bin[32+i]=v.toByte() }
        "muse-gadget".toByteArray().copyInto(bin,80)
        assertTrue(runCatching { HardwareUpdatePolicy.requireImage(g,bin) }.isFailure)
        assertTrue(runCatching { HardwareUpdatePolicy.requireAck("OTA:ACK 8192",4096) }.isFailure)
    }
    @Test fun actualSettingsKeepsOtaDisabledWithoutBoard() {
        compose.setContent { DisplayConnectTheme { com.example.displayconnect.ui.screens.SettingsScreen(onNavigateBack={}) } }
        compose.onNodeWithText("Firmware de la placa").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Buscar actualizacion").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Conecta la placa primero.").performScrollTo().assertIsDisplayed()
        capture("ota-actual-settings")
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        val colors=mutableMapOf<Int,Int>()
        for(y in 0 until 64)for(x in 0 until 64){val c=bitmap.getPixel(x*(bitmap.width-1)/63,y*(bitmap.height-1)/63) and 0x00f0f0f0;colors[c]=(colors[c]?:0)+1}
        assertTrue("Blank screenshot $name",(colors.values.maxOrNull()?:4096)<4000)
        val i=InstrumentationRegistry.getInstrumentation();val file=File(i.targetContext.getExternalFilesDir(null),"$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        val fd=i.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
