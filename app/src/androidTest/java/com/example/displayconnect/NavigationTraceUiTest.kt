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
        compose.onNodeWithText("Guardar copia del registro").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Borrar registro privado").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Se borra el registro de navegacion",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Cancelar").assertIsDisplayed().performClick()
        capture("navigation-private-controls.png")
    }
    @Test fun guardWarningDoesNotHideStop() {
        TransmissionHub.updateProgressGuard("Avance retenido: movimiento o tramo sin confirmar. La guia conserva el ultimo avance aceptado.")
        try {
            compose.setContent { DisplayConnectTheme {
                androidx.compose.material3.Surface(color=androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    NavMirror(nav=NavMessage(lat=0.0,lon=0.0,bearing=0f,instruction="Gira a la derecha",
                        distanceM=41,street="Calle de prueba",routeScreenPoints=emptyList(),userScreenPoint=null,
                        remainingDistanceM=379,remainingDurationS=120,maneuver="right",speedKmh=0),
                        trip=TripProgress(379,120),destLabel="Prueba sintetica",onStop={})
                }
            } }
            compose.onNodeWithText("Avance retenido",substring=true).assertIsDisplayed()
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            capture("navigation-guard-warning.png")
        } finally { TransmissionHub.updateProgressGuard(null) }
    }
    @Test fun unconfirmedInitialHasNoArrivalAndKeepsStopAndChoice() {
        TransmissionHub.setNavigating(false)
        TransmissionHub.updateInitialRoute(TransmissionHub.InitialRouteUi("synthetic-ui-token",
            listOf(TransmissionHub.InitialRouteChoice(0,450,12),TransmissionHub.InitialRouteChoice(4,5,10)),true))
        TransmissionHub.updateProgressGuard("Inicio sin confirmar. Elige un tramo solo si lo reconoces, o espera y para.")
        var choices=0
        try {
            compose.setContent { DisplayConnectTheme {
                androidx.compose.material3.Surface(color=androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    NavMirror(nav=NavMessage(0.0,0.0,0f,"Inicio sin confirmar",-1,"",emptyList(),
                        userScreenPoint=null,maneuver="unknown"),trip=TripProgress(),
                        destLabel="Prueba sintetica",onStop={},onSelectInitial={_,_->choices++})
                }
            } }
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            compose.onNodeWithText("0 m").assertDoesNotExist()
            compose.onNodeWithText("Tramo: ~450 m por recorrer").assertIsDisplayed().performClick()
            assertEquals(0,choices)
            compose.onNodeWithText("¿Reconoces este tramo?").assertIsDisplayed()
            capture("navigation-initial-confirm-dialog.png")
            compose.onNodeWithText("Confirmar").performClick()
            assertEquals(1,choices)
            capture("navigation-initial-unconfirmed.png")
            compose.onNodeWithText("Ya estoy en el destino").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            capture("navigation-initial-destination-choice.png")
            compose.onNodeWithText("Ya estoy en el destino").performClick()
            compose.onNodeWithText("¿Ya estás en el destino?").assertIsDisplayed()
            capture("navigation-initial-destination-dialog.png")
            compose.onNodeWithText("Cancelar").performClick()
            assertEquals(1,choices)
            compose.onNodeWithText("Tramo: ~450 m por recorrer").performScrollTo()
            compose.runOnIdle { TransmissionHub.updateInitialRoute(TransmissionHub.initialRoute.value!!.copy(fresh=false)) }
            compose.onNodeWithText("Tramo: ~450 m por recorrer").assertIsNotEnabled()
            compose.onNodeWithText("Sin posición GPS fresca").assertIsDisplayed()
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            capture("navigation-initial-no-fix.png")
            compose.onNodeWithText("Ya estoy en el destino").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Mantén pulsado para parar").assertIsDisplayed()
            capture("navigation-initial-no-fix-destination.png")
        } finally { TransmissionHub.setNavigating(false) }
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
        // Allow the device compositor to present two settled frames after state/scroll changes.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),name)
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}
    }
}
