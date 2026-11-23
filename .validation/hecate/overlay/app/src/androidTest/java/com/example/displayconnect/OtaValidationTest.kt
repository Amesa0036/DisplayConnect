package com.example.displayconnect
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.displayconnect.network.OtaTransferOwner
import com.example.displayconnect.network.OtaTransferService
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.Lifecycle
import android.app.ActivityManager
import android.os.PowerManager
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
  val finishGate=CompletableDeferred<Unit>()
  var taskId=-1
  try {
   shell("appops set com.example.displayconnect POST_NOTIFICATION allow")
   shell("pm grant com.example.displayconnect android.permission.POST_NOTIFICATIONS")
   shell("pm grant com.example.displayconnect android.permission.BLUETOOTH_CONNECT")
   activity.scenario.onActivity { taskId=it.taskId;OtaTransferOwner.busy.value=true;OtaTransferOwner.submit(it){repeat(100){index->if(index==90)finishGate.await();ticks.incrementAndGet();delay(100)} } }
   waitFor { OtaTransferService.running&&ticks.get()>0 }
   val before=ticks.get();shell("input keyevent 3");shell("input keyevent 223")
   waitFor { !context.getSystemService(PowerManager::class.java).isInteractive }
   waitFor { activity.scenario.state==Lifecycle.State.CREATED }
   waitFor { ticks.get()>=before+10 };assertTrue(OtaTransferOwner.busy.value)
   shell("input keyevent 224");shell("wm dismiss-keyguard")
   waitFor { context.getSystemService(PowerManager::class.java).isInteractive }
   val task=context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId==taskId }
   task.moveToFront()
   waitFor { activity.scenario.state==Lifecycle.State.RESUMED }
   waitFor { var focused=false;activity.scenario.onActivity { focused=it.hasWindowFocus() };focused }
   activity.scenario.recreate()
   assertEquals(Lifecycle.State.RESUMED,activity.scenario.state)
   assertTrue(OtaTransferService.running)
   instrumentation.runOnMainSync { assertTrue(runCatching{OtaTransferOwner.submit(context){error("duplicate task ran")}}.isFailure) }
   shell("cmd statusbar expand-notifications")
   waitFor { shell("uiautomator dump /sdcard/ota-validation-window.xml; cat /sdcard/ota-validation-window.xml").contains("Actualizando la placa") }
   screenshot("ota-notification.png");shell("cmd statusbar collapse")
   finishGate.complete(Unit)
   waitFor { !OtaTransferService.running };assertEquals(100,ticks.get());assertFalse(OtaTransferOwner.busy.value)
   assertFalse(shell("dumpsys power").contains("DisplayConnect::Ota"))
  } finally { finishGate.complete(Unit);context.stopService(android.content.Intent(context,OtaTransferService::class.java));shell("input keyevent 224") }
 }
 @Test fun blockedNotificationAndSyntheticFailureReleaseOwnership() {
  val context=instrumentation.targetContext;val entered=AtomicInteger()
  try {
   shell("appops set com.example.displayconnect POST_NOTIFICATION ignore")
   assertTrue(shell("appops get com.example.displayconnect POST_NOTIFICATION").contains("ignore"))
   shell("pm grant com.example.displayconnect android.permission.BLUETOOTH_CONNECT")
   activity.scenario.onActivity { OtaTransferOwner.busy.value=true;OtaTransferOwner.submit(it){entered.incrementAndGet();delay(1000);error("synthetic validation failure")} }
   waitFor { entered.get()==1 };waitFor { !OtaTransferService.running }
   assertFalse(OtaTransferOwner.busy.value);assertEquals(-1,OtaTransferOwner.progress.value);assertTrue(OtaTransferOwner.message.value.orEmpty().contains("synthetic validation failure"))
  } finally { context.stopService(android.content.Intent(context,OtaTransferService::class.java));shell("appops set com.example.displayconnect POST_NOTIFICATION allow") }
 }
}
