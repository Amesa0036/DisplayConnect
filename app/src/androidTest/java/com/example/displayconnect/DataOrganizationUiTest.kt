package com.example.displayconnect

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.ui.navigation.DisplayConnectNavHost
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File

class DataOrganizationUiTest {
    @get:Rule val compose = createComposeRule()
    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    @After fun retainLastVisibleScreen() {
        try { snapshot("ui-last-screen.png") } catch (_: Throwable) { }
        try { compose.onRoot(useUnmergedTree = true).printToLog("RutapalagosUiTree") } catch (_: Throwable) { }
    }
    private fun launchData() {
        compose.setContent { DisplayConnectTheme {
            DisplayConnectNavHost(onRequestLocationPermission = {}, onRequestBluetoothPermission = {})
        } }
        compose.onNodeWithText("¿A dónde?").assertIsDisplayed()
        compose.onNodeWithText("Altitud").assertDoesNotExist()
        snapshot("ui-home.png")
        compose.onNodeWithText("Placa y datos").performClick()
        compose.onNodeWithText("Tu placa").assertIsDisplayed()
        snapshot("ui-data-top.png")
    }
    @Test fun onboardGpsDiagnosticDoesNotStartDisconnected() {
        launchData()
        compose.onNodeWithText("Sensores").performClick()
        compose.onNodeWithText("GPS propio Hecate · prueba").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Iniciar prueba GPS").performScrollTo().assertIsNotEnabled()
        snapshot("ui-gnss.png")
    }

    @Test fun dataMenuAndReturnToNavigate() {
        launchData()
        compose.onNodeWithText("Capturas y diagnóstico").performScrollTo().assertIsDisplayed()
        snapshot("ui-data.png")
        compose.onNodeWithText("Volver a navegar").performScrollTo().performClick()
        compose.onNodeWithText("¿A dónde?").assertIsDisplayed()
        compose.onNodeWithText("Altitud").assertDoesNotExist()
    }
    @Test fun sensorsAreSeparateFromBoardControls() {
        launchData()
        compose.onNodeWithText("Sensores").performClick()
        compose.onNodeWithText("Altitud").assertIsDisplayed()
        compose.onNodeWithText(resource(R.string.ble_scan)).assertDoesNotExist()
        snapshot("ui-sensors.png")
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Tu placa").performClick()
        snapshot("ui-board-before-scroll.png")
        compose.onNodeWithText(resource(R.string.ble_scan)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Altitud").assertDoesNotExist()
        snapshot("ui-board.png")
        compose.onNodeWithText("Extras de la placa").performScrollTo().performClick()
        compose.onNodeWithText("Hora en la placa").assertIsDisplayed()
        snapshot("ui-extras.png")
    }
    @Test fun settingsDoNotShowCaptureControls() {
        launchData()
        compose.onNodeWithText("Ajustes").performScrollTo().performClick()
        compose.onNodeWithText("Iniciar sombra").assertDoesNotExist()
        compose.onNodeWithText("Firmware de la placa").performScrollTo().assertIsDisplayed()
        snapshot("ui-preferences.png")
    }
    @Test fun advancedControlsKeepTheirSafetyGuards() {
        launchData()
        compose.onNodeWithText("Capturas y diagnóstico").performScrollTo().performClick()
        compose.onNodeWithText("Iniciar sombra").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Parar y descargar sombra").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Iniciar captura").performScrollTo().assertIsNotEnabled()
        snapshot("ui-advanced.png")
    }
    @Test fun routeDetailsKeepCoordinatesAndOfflineStatus() {
        launchData()
        compose.onNodeWithText("Rutas y mapas").performClick()
        compose.onNodeWithText("No hay una descarga activa.", substring = true).assertIsDisplayed()
        snapshot("ui-routes.png")
        compose.onNodeWithText(resource(R.string.dest_lat)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resource(R.string.dest_lon)).performScrollTo().assertIsDisplayed()
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.getExternalFilesDir(null), name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val fd = instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
