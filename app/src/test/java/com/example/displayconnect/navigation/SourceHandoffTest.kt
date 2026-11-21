package com.example.displayconnect.navigation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceHandoffTest {
 @Test fun waitsForCloseAndUsesLatestExplicitSelection()=runBlocking {
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  var choice="phone";var started:String?=null
  val job=async {sourceHandoff(close={entered.complete(Unit);release.await();true},latestSelection={choice},restart={started=it})}
  entered.await();assertNull(started)
  choice="hecate";release.complete(Unit)
  assertTrue(job.await());assertEquals("hecate",started)
 }
 @Test fun stopOrCopyFailureDoesNotRestartOrReadNewSelection()=runBlocking {
  for(reason in listOf("stop","copy")) {
   var reads=0;var restarts=0
   assertFalse(sourceHandoff(close={false},latestSelection={reads++;reason},restart={restarts++}))
   assertEquals(0,reads);assertEquals(0,restarts)
  }
 }
 @Test fun cancellationDuringNonCancellableClosureNeverRestarts()=runBlocking {
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var restarts=0
  val job=launch { sourceHandoff(close={withContext(NonCancellable){entered.complete(Unit);release.await()};true},latestSelection={"hecate"},restart={restarts++}) }
  entered.await();job.cancel();release.complete(Unit);job.join();assertEquals(0,restarts)
 }
 @Test fun cancellationWhileReadingSelectionNeverRestarts()=runBlocking {
  val entered=CompletableDeferred<Unit>();var restarts=0
  val job=launch {sourceHandoff(close={true},latestSelection={entered.complete(Unit);awaitCancellation();"phone"},restart={restarts++})}
  entered.await();job.cancelAndJoin();assertEquals(0,restarts)
 }
 @Test fun preflightRechecksSelectionAndDoesNotUseStaleCandidate()=runBlocking {
  var choice="phone";val prepared=mutableListOf<String>()
  val result=latestPreparedSource(latestSelection={choice},prepare={selected ->prepared.add(selected);if(selected=="phone")choice="hecate";"source:$selected"})
  assertEquals(listOf("phone","hecate"),prepared);assertEquals("hecate" to "source:hecate",result)
 }
 @Test fun unavailableChoiceNeverFallsBackToOtherSource()=runBlocking {
  val result=latestPreparedSource(latestSelection={"hecate"},prepare={null as String?})
  assertEquals("hecate",result.first);assertNull(result.second)
 }
 @Test fun preflightCancellationCannotReturnCandidate()=runBlocking {
  val entered=CompletableDeferred<Unit>();var returned=false
  val job=launch {latestPreparedSource(latestSelection={"hecate"},prepare={withContext(NonCancellable){entered.complete(Unit);delay(10)};"candidate"});returned=true}
  entered.await();job.cancelAndJoin();assertFalse(returned)
 }
 @Test fun closureExceptionDoesNotRestart()=runBlocking {
  var restarts=0
  val result=runCatching {sourceHandoff(close={error("test closure failure")},latestSelection={"phone"},restart={restarts++})}
  assertTrue(result.isFailure);assertEquals(0,restarts)
 }
 @Test fun cancellationDuringRestartPreparationCannotCreateEngine()=runBlocking {
  val preparing=CompletableDeferred<Unit>();var created=false
  val job=launch {sourceHandoff(close={true},latestSelection={"phone"},restart={preparing.complete(Unit);delay(Long.MAX_VALUE);created=true})}
  preparing.await();job.cancelAndJoin();assertFalse(created)
 }

 @Test fun effectiveSourceIsClearedWhenNavigationStops() {
  val hub=com.example.displayconnect.utils.TransmissionHub
  hub.setNavigating(true)
  hub.updateEffectiveGpsSource(com.example.displayconnect.models.GpsSource.HECATE)
  assertEquals(com.example.displayconnect.models.GpsSource.HECATE,hub.effectiveGpsSource.value)
  hub.setNavigating(false)
  assertNull(hub.effectiveGpsSource.value)
 }

}
