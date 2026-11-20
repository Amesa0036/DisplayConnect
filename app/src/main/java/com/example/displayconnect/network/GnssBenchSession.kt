package com.example.displayconnect.network
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
/** One explicit diagnostic start. Never selects GPS source or starts navigation.
 * No reconnect/restart. Caller owns lifecycle cancellation and UI exclusion. */
internal class GnssBenchSession(
    private val command: suspend (String) -> Result<JSONObject>,
    private val monotonic: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun run(publish: (JSONObject) -> Unit) {
        val begin = monotonic()
        var startSent = false
        var terminal = false
        try {
            startSent = true // even uncertain transport must never resend start
            var reply = command("start").getOrThrow()
            // A definite refusal never grants ownership of a pre-existing session.
            if (reply.has("ok") && !reply.getBoolean("ok")) {
                terminal = true
                error("gnss_bench_refused")
            }
            while (true) {
                check(reply.optBoolean("ok") && reply.optBoolean("supported") && reply.optBoolean("reader")) { "gnss_bench_refused" }
                check(reply.optInt("protocol") == 1 && reply.optString("source") == "lc76g") { "gnss_bench_protocol" }
                val running = reply.getBoolean("running")
                // Mark terminal before presentation: UI failure cannot revive it.
                terminal = !running
                val received = monotonic()
                check(received >= begin && received - begin <= 186000L) { "gnss_bench_deadline" }
                publish(reply)
                if (terminal) return
                val now = monotonic()
                check(now >= begin && now - begin <= 186000L) { "gnss_bench_deadline" }
                pause(1000)
                reply = command("status").getOrThrow()
            }
        } finally {
            if (startSent && !terminal) withContext(NonCancellable) {
                withTimeoutOrNull(1500) { try { command("stop") } catch (ignored: Exception) { /* best effort only */ } }
            }
        }
    }
}
