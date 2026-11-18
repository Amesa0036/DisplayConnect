package com.example.displayconnect

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.ui.screens.SettingsScreen
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import com.example.displayconnect.utils.AppLanguage
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Captura visual del selector de fuente GPS y del aviso cuando la placa no
 *  ofrece GPS propio (sondeo real falla: en el emulador no hay placa BLE).
 *  Sin coordenadas ni datos reales: solo UI de ajustes.
 *  El locale se aplica como en MainActivity: AppLanguage + CompositionLocalProvider. */
class GnssSourceSelectorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureGpsSourceSelectorAndProbeWarning() {
        compose.setContent {
            val language by AppLanguage.language.collectAsState()
            val baseContext = LocalContext.current
            val registryOwner = requireNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current) {
                "The test activity must provide its ActivityResultRegistryOwner before localization"
            }
            val localized = remember(language) { AppLanguage.context(baseContext, language) }
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner,
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration
            ) {
                DisplayConnectTheme { SettingsScreen(onNavigateBack = {}) }
            }
        }
        compose.waitForIdle()

        // Idioma controlado para capturas legibles.
        compose.onNode(hasText("Español") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Fuente de posición").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("GPS del móvil") and hasClickAction()).assertIsDisplayed().assertIsSelected()
        compose.onNode(hasText("GPS de Hecate") and hasClickAction()).assertIsDisplayed().assertIsNotSelected()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "settings-gnss-source.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        retain("settings-gnss-source.png", context)

        // Seleccionar Hecate sin placa: el sondeo real falla y la selección no cambia.
        compose.onNode(hasText("GPS de Hecate") and hasClickAction()).performClick()
        compose.waitUntil(timeoutMillis = 15000) {
            compose.onAllNodesWithText("Esta placa no ofrece GPS propio", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Esta placa no ofrece GPS propio", substring = true).assertIsDisplayed()
        compose.onNode(hasText("GPS del móvil") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("GPS de Hecate") and hasClickAction()).assertIsNotSelected()
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "settings-gnss-probe-fail.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        retain("settings-gnss-probe-fail.png", context)
    }

    private fun retain(name: String, context: android.content.Context) {
        val source = File(context.getExternalFilesDir(null), name).absolutePath
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp $source /data/local/tmp/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
