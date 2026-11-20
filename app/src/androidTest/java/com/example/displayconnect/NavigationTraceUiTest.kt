package com.example.displayconnect

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.navigation.FileNavigationTrace
import com.example.displayconnect.protocol.NavMessage
import com.example.displayconnect.routing.TripProgress
import com.example.displayconnect.ui.screens.NavMirror
import com.example.displayconnect.ui.screens.SettingsScreen
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import com.example.displayconnect.utils.TransmissionHub
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Emulator tests use only synthetic events. Never export a real-device trace to CI. */
class NavigationTraceUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun privateRecordControlsAndDeleteConfirmation() {
        TransmissionHub.setNavigating(false)
        compose.setContent { DisplayConnectTheme { SettingsScreen(onNavigateBack = {},section="advanced") } }
        compose.onNodeWithText("Registro privado de navegacion").assertIsDisplayed()
        compose.onNodeWithText("Guardar copia del registro").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Borrar registro privado").assertIsDisplayed().performClick()
        compose.onNodeWithText("Se borra el registro de navegacion",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar").assertIsDisplayed().performClick()
        capture("navigation-private-controls.png")
    }
    @Test fun guardWarningDoesNotHideStop() {
        TransmissionHub.updateProgressGuard("Avance retenido: movimiento o tramo sin confirmar. La guia conserva el ultimo avance aceptado.")
        try {
            compose.setContent { DisplayConnectTheme {
                NavMirror(nav=null,trip=TripProgress(379,120),destLabel="Prueba sintetica",onStop={})
            } }
            compose.onNodeWithText("Avance retenido",substring=true).assertIsDisplayed()
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            capture("navigation-guard-warning.png")
        } finally { TransmissionHub.updateProgressGuard(null) }
    }
    @Test fun privateTraceStaysInNoBackupAndExportsOnlyOnRequest() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.noBackupFilesDir,"trace-instrumented-synthetic")
        val trace=FileNavigationTrace(dir)
        trace.newSession().record("synthetic",mapOf("lat" to 0.0,"lon" to 0.0))
        val bytes=trace.snapshot()
        assertTrue(dir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath+"/"))
        assertTrue(bytes.toString(Charsets.UTF_8).contains("synthetic"))
        trace.clear();assertTrue(dir.listFiles()!!.isEmpty())
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}
    }
}
