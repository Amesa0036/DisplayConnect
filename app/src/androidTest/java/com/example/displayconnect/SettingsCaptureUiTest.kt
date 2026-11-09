package com.example.displayconnect
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.ui.screens.SettingsScreen
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
class SettingsCaptureUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun captureActualPassiveSettingsWithSafetyText() {
        compose.setContent { DisplayConnectTheme { SettingsScreen(onNavigateBack = {}) } }
        compose.onNodeWithText("Prueba pasiva del sensor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Firmware r3:", substring = true).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "settings-passive-info.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        retain("settings-passive-info.png", context)
        compose.onNodeWithText("Parar y descargar CSV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Iniciar captura").assertExists()
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "settings-passive-buttons.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        retain("settings-passive-buttons.png", context)
    }
    private fun retain(name: String, context: android.content.Context) {
        val source=File(context.getExternalFilesDir(null), name).absolutePath
        val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp $source /data/local/tmp/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
