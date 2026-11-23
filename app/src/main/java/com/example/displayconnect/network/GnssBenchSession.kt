package com.example.displayconnect.network
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.example.displayconnect.navigation.HecateClosurePolicy

/** One explicit diagnostic start. Its own deadline also bounds continuous
 * navigation firmware. No reconnect/restart. Navigation has a separate owner. */
internal class GnssBenchSession(
    private val command: suspend (String) -> Result<JSONObject>,
    private val monotonic: () -> Long,
    private val pause: suspend (Long) -> Unit = ::delay
) {
    suspend fun run(publish: (JSONObject) -> Unit) {
        val begin = monotonic()
        var startSent = false
        var terminal = false
        var stopAttempted = false
        suspend fun finishAtLimit() {
            stopAttempted = true
            val stopped = withContext(NonCancellable) {
                withTimeoutOrNull(1500) { command("stop").getOrThrow() }
            } ?: error("gnss_bench_stop_timeout")
            check(HecateClosurePolicy.confirmed(stopped)) { "gnss_bench_close_unconfirmed" }
            terminal = true
            publish(stopped)
        }
        try {
            startSent = true
            var reply = command("start").getOrThrow()
            if (reply.has("ok") && !reply.getBoolean("ok")) {
                terminal = true
                error("gnss_bench_refused")
            }
            while (true) {
                check(reply.optBoolean("ok") && reply.optBoolean("supported") && reply.optBoolean("reader")) { "gnss_bench_refused" }
                check(reply.optInt("protocol") == 1 && reply.optString("source") == "lc76g") { "gnss_bench_protocol" }
                val received = monotonic()
                check(received >= begin && received - begin <= 186000L) { "gnss_bench_deadline" }
                if (!reply.getBoolean("running")) {
                    check(HecateClosurePolicy.confirmed(reply)) { "gnss_bench_close_unconfirmed" }
                    terminal = true
                    publish(reply)
                    return
                }
                if (received - begin >= 180000L) { finishAtLimit(); return }
                publish(reply)
                val now = monotonic()
                check(now >= begin && now - begin <= 186000L) { "gnss_bench_deadline" }
                if (now - begin >= 180000L) { finishAtLimit(); return }
                pause(minOf(1000L, 180000L - (now - begin)))
                val afterPause = monotonic()
                check(afterPause >= begin && afterPause - begin <= 186000L) { "gnss_bench_deadline" }
                if (afterPause - begin >= 180000L) { finishAtLimit(); return }
                reply = command("status").getOrThrow()
            }
        } finally {
            if (startSent && !terminal && !stopAttempted) withContext(NonCancellable) {
                withTimeoutOrNull(1500) { try { command("stop") } catch (ignored: Exception) { } }
            }
        }
    }
}
