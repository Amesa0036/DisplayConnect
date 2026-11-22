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
    @Test fun localFileTrialRequiresExplicitSecondConfirmation() {
        val visible=mutableStateOf(false);var picked=0;var sent=0
        compose.setContent { DisplayConnectTheme {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                FirmwareUpdateCard(true,true,false,-1,null,false,allowLocalFile=true,onLocalFile={picked++;visible.value=true},onUpdate={})
                if(visible.value)com.example.displayconnect.ui.components.LocalFirmwareConfirmDialog("0.28.21-175g-ota1-LOCAL",659456,"0123456789abcdef".repeat(4),"Hecate",
                    onCancel={visible.value=false},onConfirm={sent++;visible.value=false})
            }
        } }
        compose.onNodeWithText("Elegir archivo firmado (prueba)").assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(1,picked);assertEquals(0,sent)}
        compose.onNodeWithText("Enviar archivo de prueba").assertIsDisplayed()
        compose.onNodeWithText("la placa se reiniciara",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar").assertIsDisplayed()
        capture("ota-local-confirmation-fixture",dialog=true)
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle{assertEquals(0,sent)}
        compose.onNodeWithText("Elegir archivo firmado (prueba)").performClick()
        compose.onNodeWithText("Enviar por Bluetooth").performClick()
        compose.runOnIdle{assertEquals(1,sent)}
    }
    @Test fun localFileButtonCannotBypassUsbBootstrapOrBusyState() {
        val busy=mutableStateOf(false);var clicks=0
        compose.setContent { DisplayConnectTheme { FirmwareUpdateCard(true,true,busy.value,-1,null,!busy.value,
            allowLocalFile=true,onLocalFile={clicks++},onUpdate={}) } }
        compose.onNodeWithText("Elegir archivo firmado (prueba)").assertIsNotEnabled()
        compose.runOnIdle{busy.value=true}
        compose.onNodeWithText("Elegir archivo firmado (prueba)").assertIsNotEnabled()
        compose.runOnIdle{assertEquals(0,clicks)}
        capture("ota-local-disabled-fixture")
    }
    @Test fun androidVerifiesRsaPssAndRejectsChangedFileBeforeAnyTransfer() {
        // Ephemeral synthetic block, not a bootable firmware or a board transfer.
        val keys=java.security.KeyPairGenerator.getInstance("RSA").apply{initialize(3072)}.generateKeyPair()
        val b=ByteArray(8192){0xff.toByte()};b.fill(0,0,4096);val start=4096
        b[start]=0xe7.toByte();b[start+1]=2;b[start+2]=0;b[start+3]=0
        java.security.MessageDigest.getInstance("SHA-256").digest(b.copyOfRange(0,start)).copyInto(b,start+4)
        val k=keys.public as java.security.interfaces.RSAPublicKey
        fun put(n:java.math.BigInteger,off:Int,len:Int){val le=n.toByteArray().reversedArray();repeat(len){b[start+off+it]=if(it<le.size)le[it] else 0}}
        put(k.modulus,36,384);put(k.publicExponent,420,4)
        val signer=runCatching{java.security.Signature.getInstance("RSASSA-PSS")}.getOrElse{java.security.Signature.getInstance("SHA256withRSA/PSS")}
        signer.initSign(keys.private);signer.setParameter(java.security.spec.PSSParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA256,32,1));signer.update(b,0,start)
        signer.sign().reversedArray().copyInto(b,start+812)
        val crc=java.util.zip.CRC32().apply{update(b,start,1196)}.value;repeat(4){b[start+1196+it]=((crc ushr(it*8)) and 255).toByte()}
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString(""){"%02x".format(it)}
        val verifier=com.example.displayconnect.network.FirmwareSignatureVerifier(sha)
        verifier.requireAuthentic(b)
        b[500]=1
        assertTrue(runCatching{verifier.requireAuthentic(b)}.isFailure)
    }
    private fun capture(name:String,dialog:Boolean=false) {
        compose.waitForIdle()
        val root=if(dialog)compose.onNode(isRoot() and hasAnyDescendant(hasText("Enviar archivo de prueba"))) else compose.onRoot()
        val bitmap=root.captureToImage().asAndroidBitmap()
        val colors=mutableMapOf<Int,Int>()
        for(y in 0 until 64)for(x in 0 until 64){val c=bitmap.getPixel(x*(bitmap.width-1)/63,y*(bitmap.height-1)/63) and 0x00f0f0f0;colors[c]=(colors[c]?:0)+1}
        assertTrue("Blank screenshot $name",(colors.values.maxOrNull()?:4096)<4000)
        val i=InstrumentationRegistry.getInstrumentation();val file=File(i.targetContext.getExternalFilesDir(null),"$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        val fd=i.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
