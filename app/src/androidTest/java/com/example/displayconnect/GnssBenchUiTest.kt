package com.example.displayconnect
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.ui.screens.MainScreen
import com.example.displayconnect.ui.theme.DisplayConnectTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
class GnssBenchUiTest {
 @get:Rule val compose = createComposeRule()
 @Test fun captureDisconnectedBench() {
  compose.setContent { DisplayConnectTheme { MainScreen({}, {}, {}, {}, "sensors") } }
  compose.onNodeWithText("GPS propio Hecate · prueba").performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Iniciar prueba GPS").assertIsNotEnabled()
  compose.onNodeWithText("Parar prueba GPS").assertIsNotEnabled()
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  File(context.getExternalFilesDir(null), "gnss-ble-bench.png").outputStream().use {
   compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
  }
 }
}
