package com.example.displayconnect.network

import com.example.displayconnect.navigation.NavigationTraceSink
import org.json.JSONObject
import kotlinx.coroutines.delay

/** Capture the bench session through the same bounded local export used by navigation. */
internal object GnssBenchTrace {
    suspend fun run(trace: NavigationTraceSink,
        command: suspend (String) -> Result<JSONObject>,
        monotonic: () -> Long,
        pause: suspend (Long) -> Unit = ::delay,
        publish: (JSONObject) -> Unit) {
        trace.record("session_start", mapOf("source" to "hecate", "purpose" to "stationary_position_bench"))
        try {
            GnssBenchSession(command = { op ->
                val result = try { command(op) } catch (e: Exception) {
                    trace.record("gnss_bench_transport_failure", mapOf("command" to op, "error_class" to e.javaClass.simpleName))
                    throw e
                }
                result.getOrNull()?.let { trace.record("gnss_reply", mapOf("command" to op, "reply" to it.toString())) }
                if (result.isFailure) trace.record("gnss_bench_transport_failure", mapOf("command" to op))
                result
            }, monotonic = monotonic, pause = pause).run(publish)
        } finally { trace.record("session_end", mapOf("purpose" to "stationary_position_bench")) }
    }
}
