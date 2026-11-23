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
import org.junit.After
import android.app.NotificationManager
import android.app.AppOpsManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OtaValidationTest {
 @get:Rule val activity=ActivityScenarioRule(ValidationActivity::class.java)
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val evidenceDir get()=requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
 private fun record(text:String) { File(evidenceDir,"ota-diagnostics.txt").appendText("${android.os.SystemClock.elapsedRealtime()} $text\n") }
 private fun shell(command:String):String {
  // Runtime.exec does not parse shell quoting: feed a fixed script to sh stdin.
  val fds=instrumentation.uiAutomation.executeShellCommandRwe("sh")
  android.os.ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).bufferedWriter().use {
   it.write(command+"\nrc=\$?\nprintf '\\n__HECATE_EXIT__%s\\n' \"\$rc\"\nexit \$rc\n")
  }
  val executor=Executors.newFixedThreadPool(2)
  try {
   val stdout=executor.submit<String> { android.os.ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes().toString(Charsets.UTF_8) } }
   val stderr=executor.submit<String> { android.os.ParcelFileDescriptor.AutoCloseInputStream(fds[2]).use { it.readBytes().toString(Charsets.UTF_8) } }
   val out=stdout.get(10,TimeUnit.SECONDS);val err=stderr.get(10,TimeUnit.SECONDS)
   record("command=$command\nstdout=$out\nstderr=$err")
   val exit=Regex("__HECATE_EXIT__(\\d+)").findAll(out).lastOrNull()?.groupValues?.get(1)?.toInt()
   check(exit==0) { "Shell failed or missing exit status: $command (exit=$exit)" }
   return out.substringBeforeLast("__HECATE_EXIT__")
  } catch (failure:Throwable) { record("commandFailure=$command error=$failure");throw failure
  } finally { fds.forEach { runCatching { it.close() } };executor.shutdownNow() }
 }
 private fun notificationState(label:String):Boolean {
  val context=instrumentation.targetContext
  val enabled=context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
  val op=context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow("android:post_notification",context.applicationInfo.uid,context.packageName)
  record("$label notificationsEnabled=$enabled appOpMode=$op running=${OtaTransferService.running} busy=${OtaTransferOwner.busy.value}")
  return enabled
 }
private fun otaLockActive(dump:String):Boolean {
 val lines=dump.lines()
 val starts=lines.indices.filter { Regex("Wake Locks: size=\\d+").matches(lines[it]) }
 val ends=lines.indices.filter { Regex("Suspend Blockers: size=\\d+").matches(lines[it]) }
 check(starts.size==1&&ends.size==1&&starts.single()<ends.single()) { "Missing/ambiguous current wake-lock section" }
 val rows=lines.subList(starts.single()+1,ends.single()).filter { it.isNotBlank() }
 check(rows.size==lines[starts.single()].substringAfter("=").toInt()) { "Wake-lock count does not match rows" }
 val tags=rows.map { row -> requireNotNull(Regex("  [A-Z_]+ +'(.*)'(?: .*)?").matchEntire(row)) { "Unknown current wake-lock row" }.groupValues[1] }
 return "DisplayConnect::Ota" in tags
}
 private fun notificationVisible():Boolean {
  val automation=instrumentation.uiAutomation
  val info=automation.serviceInfo
  info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
  automation.serviceInfo=info
  return automation.windows.any { treeHasText(it.root,"Actualizando la placa") } || treeHasText(automation.rootInActiveWindow,"Actualizando la placa")
 }
 private fun failureEvidence(label:String) {
  runCatching { screenshot("ota-$label-failure.png") };runCatching { notificationState("$label-failure") };runCatching { shell("dumpsys power") }
 }
 private fun treeHasText(node:android.view.accessibility.AccessibilityNodeInfo?,text:String):Boolean {
  if(node==null)return false
  if(node.text?.contains(text)==true)return true
  for(index in 0 until node.childCount)if(treeHasText(node.getChild(index),text))return true
  return false
 }
 @After fun diagnostics() {
  runCatching { record("afterTest lifecycle=${activity.scenario.state}");notificationState("afterTest");shell("dumpsys power");screenshot("ota-after-test.png") }.onFailure { record("diagnosticFailure=$it") }
 }
 private fun waitFor(limit:Long=15000,condition:()->Boolean) { val end=android.os.SystemClock.elapsedRealtime()+limit;while(!condition()&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertTrue(condition()) }
 private fun screenshot(name:String) { File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use { check(requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG,100,it)) } }
 @Test fun syntheticTaskSurvivesScreenOffAndActivityRecreation() {
  val ticks=AtomicInteger();val context=instrumentation.targetContext
  val finishGate=CompletableDeferred<Unit>()
  var taskId=-1
  try {
   shell("pm grant com.example.displayconnect android.permission.POST_NOTIFICATIONS")
   waitFor { notificationState("success-before") };assertTrue("Notifications not enabled before success test",notificationState("success-verified"))
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
   waitFor { notificationVisible() }
   screenshot("ota-notification.png");shell("cmd statusbar collapse")
   finishGate.complete(Unit)
   waitFor { !OtaTransferService.running };assertEquals(100,ticks.get());assertFalse(OtaTransferOwner.busy.value)
   assertFalse("OTA wake lock still active",otaLockActive(shell("dumpsys power")))
   record("success-complete ticks=${ticks.get()} busy=${OtaTransferOwner.busy.value}")
  } catch (failure:Throwable) { failureEvidence("success");throw failure
  } finally { runCatching { shell("cmd statusbar collapse") };finishGate.complete(Unit);context.stopService(android.content.Intent(context,OtaTransferService::class.java));shell("input keyevent 224") }
 }
 @Test fun blockedNotificationAndSyntheticFailureReleaseOwnership() {
  val context=instrumentation.targetContext;val entered=AtomicInteger()
  try {
   shell("appops get com.example.displayconnect POST_NOTIFICATION")
   assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
   waitFor { !notificationState("blocked-before") }
   assertFalse("Notifications not blocked before error test",notificationState("blocked-verified"))
   shell("pm grant com.example.displayconnect android.permission.BLUETOOTH_CONNECT")
   activity.scenario.onActivity { OtaTransferOwner.busy.value=true;OtaTransferOwner.submit(it){entered.incrementAndGet();delay(1000);error("synthetic validation failure")} }
   waitFor { entered.get()==1 };waitFor { !OtaTransferService.running }
   assertFalse(OtaTransferOwner.busy.value);assertEquals(-1,OtaTransferOwner.progress.value);assertTrue(OtaTransferOwner.message.value.orEmpty().contains("synthetic validation failure"))
   assertFalse("OTA wake lock still active",otaLockActive(shell("dumpsys power")))
   assertFalse("Notifications unexpectedly enabled after blocked task",notificationState("blocked-after"))
   screenshot("ota-blocked-complete.png");record("blocked-error-complete")
  } catch (failure:Throwable) { failureEvidence("blocked");throw failure
  } finally { context.stopService(android.content.Intent(context,OtaTransferService::class.java)) }
 }
}
