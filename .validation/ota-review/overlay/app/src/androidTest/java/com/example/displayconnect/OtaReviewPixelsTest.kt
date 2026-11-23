package com.example.displayconnect
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OtaReviewPixelsTest {
 @Test fun renderActualFirmwareCardInjectedStates(){
  val i=InstrumentationRegistry.getInstrumentation()
  fun shot(name:String){File(i.targetContext.getExternalFilesDir(null),name).outputStream().use{check(requireNotNull(i.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG,100,it))}}
  for(s in 0..5){ActivityScenario.launch(OtaReviewActivity::class.java).use{activity->
   activity.onActivity{it.state=s};i.waitForIdleSync()
   shot("ota-review-$s-top.png")
   i.uiAutomation.executeShellCommand("input swipe 360 1100 360 250 400").let{fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use{it.readBytes()}};i.waitForIdleSync();shot("ota-review-$s-bottom.png")
  }}
 }
}
