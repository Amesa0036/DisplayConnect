package com.example.displayconnect
import com.example.displayconnect.network.GnssBenchSession
import org.junit.Test
import kotlinx.coroutines.*
import org.json.JSONObject
class GnssBenchContractTest {
@Test fun oneStartAndNoAutomaticRestart()=runBlocking {
 var cases=0
 for(mode in listOf("normal","i2c","timeout","busy","malformed","cancel")){
  val ops=mutableListOf<String>();var now=0L;var updates=0
  val session=GnssBenchSession(command={op->
   ops.add(op)
   if(mode=="timeout"&&op=="start")Result.failure(IllegalStateException("timeout"))
   else Result.success(JSONObject().put("ok",mode!="busy").put("supported",true).put("reader",true).put("protocol",if(mode=="malformed")2 else 1).put("source","lc76g").put("running",op=="start").put("reason",if(mode=="i2c")"i2c_error" else "session_limit"))
  },monotonic={now},pause={now+=it;if(mode=="cancel")throw CancellationException("cancel")})
  val result=runCatching{session.run{updates++}}
  check(ops.count{it=="start"}==1)
  check(ops.count{it=="stop"}==(if(mode in listOf("normal","i2c","busy"))0 else 1))
  check(result.isSuccess==(mode in listOf("normal","i2c")));cases++
 }
 println("PASS $cases actual mobile bench-controller cases: one start, maintained status, terminal/no restart, I2C, uncertain start cleanup, busy/protocol rejection and cancellation; no GATT hardware")
}

}
