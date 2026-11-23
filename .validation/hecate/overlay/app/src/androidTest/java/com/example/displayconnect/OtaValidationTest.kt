package com.example.displayconnect
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.network.OtaTransferOwner
import com.example.displayconnect.network.OtaTransferService
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OtaValidationTest {
 @get:Rule val activity=ActivityScenarioRule(ValidationActivity::class.java)
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) } }
 private fun waitFor(limit:Long=15000,condition:()->Boolean) { val end=android.os.SystemClock.elapsedRealtime()+limit;while(!condition()&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertTrue(condition()) }
 private fun screenshot(name:String) { File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use { check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG,100,it)) } }
 @Test fun syntheticTaskSurvivesScreenOffAndActivityRecreation() {
  val ticks=AtomicInteger();val context=instrumentation.targetContext
  try {
   shell("pm grant com.example.displayconnect android.permission.POST_NOTIFICATIONS")
   shell("pm grant com.example.displayconnect android.permission.BLUETOOTH_CONNECT")
   activity.scenario.onActivity { OtaTransferOwner.busy.value=true;OtaTransferOwner.submit(it){repeat(100){ticks.incrementAndGet();delay(100)} } }
   waitFor { OtaTransferService.running&&ticks.get()>0 }
   val before=ticks.get();shell("input keyevent 223");shell("input keyevent 3")
   waitFor { ticks.get()>=before+10 };assertTrue(OtaTransferOwner.busy.value)
   shell("input keyevent 224");shell("wm dismiss-keyguard")
   activity.scenario.recreate()
   assertTrue(OtaTransferService.running)
   instrumentation.runOnMainSync { assertTrue(runCatching{OtaTransferOwner.submit(context){error("duplicate task ran")}}.isFailure) }
   shell("cmd statusbar expand-notifications");screenshot("ota-notification.png");shell("cmd statusbar collapse")
   waitFor { !OtaTransferService.running };assertEquals(100,ticks.get());assertFalse(OtaTransferOwner.busy.value)
   assertFalse(shell("dumpsys power").contains("DisplayConnect::Ota"))
  } finally { context.stopService(android.content.Intent(context,OtaTransferService::class.java));shell("input keyevent 224") }
 }
 @Test fun blockedNotificationAndSyntheticFailureReleaseOwnership() {
  val context=instrumentation.targetContext;val entered=AtomicInteger()
  try {
   shell("appops set com.example.displayconnect POST_NOTIFICATION ignore")
   shell("pm grant com.example.displayconnect android.permission.BLUETOOTH_CONNECT")
   activity.scenario.onActivity { OtaTransferOwner.busy.value=true;OtaTransferOwner.submit(it){entered.incrementAndGet();delay(1000);error("synthetic validation failure")} }
   waitFor { entered.get()==1 };waitFor { !OtaTransferService.running }
   assertFalse(OtaTransferOwner.busy.value);assertEquals(-1,OtaTransferOwner.progress.value);assertTrue(OtaTransferOwner.message.value.orEmpty().contains("synthetic validation failure"))
  } finally { context.stopService(android.content.Intent(context,OtaTransferService::class.java));shell("appops set com.example.displayconnect POST_NOTIFICATION allow") }
 }
}
