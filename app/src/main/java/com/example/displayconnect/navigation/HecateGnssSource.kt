package com.example.displayconnect.navigation

import android.os.SystemClock
import com.example.displayconnect.network.BleNavClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fuente de posicion: receptor GNSS propio de la placa Hecate (LC76G por I2C),
 * expuesto por el firmware via gnss_diag (start/status/stop). El telefono
 * sigue haciendo de router BLE; la placa calcula la solucion de navegacion.
 *
 * Sin repliegue silencioso: si la sesion muere no se reinicia y, si
 * la placa no soporta gnss_diag, el flujo termina con error y la navegacion no
 * arranca (la eleccion de fuente es del usuario en ajustes).
 */
class HecateGnssSource(private val navClient: BleNavClient, private val trace: NavigationTraceSink = NavigationTraceSink.NONE) : PositionSource {

    /** False after this owner attempts I/O, until explicit STOP and any raw copy finish.
     * An uncollected source has touched no receiver and is safe to discard. */
    @Volatile var closureConfirmed: Boolean = true
        private set

    override fun locationFlow(intervalMs: Long): Flow<LocationUpdate> = flow {
        // El modulo emite a 1 Hz; sondear mas rapido no aporta nada y satura BLE.
        // Cross-instance lock includes STOP and raw retrieval. A second start must not
        // open/stop another receiver session while the first is closing.
        check(sessionOwner.tryLock()) { "gnss_session_closing_or_active" }
        closureConfirmed = false
        val pollMs = intervalMs.coerceIn(1000L, 5000L)
        var started = false
        var rawCaptureId: String? = null
        // One explicit owner start is one hardware session. Never auto-restart
        // after a transport failure, even if navigation is still requested.
        try {
            while (true) {
                val reply = navClient.gnssRawCommand(if (started) "status" else "start").getOrThrow()
                if(!started && reply.optInt("raw_diag",0)==1) {
                    if(reply.optBoolean("raw_available") && reply.optBoolean("raw_capture_current"))
                        rawCaptureId=reply.optString("raw_capture_id").takeIf { it.matches(Regex("[a-f0-9]{16,32}")) }
                    trace.record("gnss_raw_capability",mapOf("available" to reply.optBoolean("raw_available"),
                        "current" to reply.optBoolean("raw_capture_current"),"capture_id" to rawCaptureId,
                        "note" to "First hardware session per boot only; RAM lost on reboot; no automatic clear"))
                }
                if(!started && reply.optInt("raw_diag",0)!=1)
                    trace.record("gnss_raw_unsupported",emptyMap())
                trace.record("gnss_reply", mapOf("command" to if(started) "status" else "start", "reply" to reply.toString()))
                if (!reply.optBoolean("ok", false)) {
                    val error = reply.optString("error", "gnss_diag rechazado")
                    throw IllegalStateException(error)
                }
                // supported distingue placa con GNSS; "reader" distingue el
                // firmware con reader completo del antiguo reserve1 (que solo
                // hace prueba de longitud y nunca daria posicion en navegacion).
                if (!reply.optBoolean("supported", false) || !reply.optBoolean("reader", false)) {
                    throw IllegalStateException("unsupported_or_unavailable")
                }
                if (!reply.optBoolean("running", false)) {
                    throw IllegalStateException("gnss_stopped:" + reply.optString("reason", "unknown"))
                }
                started = true
                if (reply.optBoolean("valid_fix", false)) {
                    val decoded = decodeFix(reply, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    trace.record("gnss_decode", mapOf("accepted" to (decoded != null)))
                    decoded?.let { emit(it) }
                }
                delay(pollMs)
            }
        } finally {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // Original STOP remains first. Export starts only after its explicit success.
                // Also stop when a diagnostic start returned stopped before `started` was set.
                val stop = if(started || rawCaptureId!=null)
                    kotlinx.coroutines.withTimeoutOrNull(1500) { navClient.gnssRawCommand("stop") } else null
                if(started || rawCaptureId!=null)
                    trace.record("gnss_stop", mapOf("completed" to (stop != null), "success" to stop?.isSuccess))
                val stopConfirmed=stop?.getOrNull()?.let { it.optBoolean("ok") && !it.optBoolean("running",true) }==true
                var copyConfirmed=true
                rawCaptureId?.let { captureId ->
                    if(stopConfirmed) {
                        trace.record("gnss_raw_transfer_start",mapOf("capture_id" to captureId))
                        val completed=kotlinx.coroutines.withTimeoutOrNull(90_000) {
                            try {
                                HecateRawCapture(trace).collect(captureId) { op,id,offset -> navClient.gnssRawCommand(op,id,offset) }
                                true
                            } catch(e:kotlinx.coroutines.CancellationException) { throw e
                            } catch(e:Exception) {
                                trace.record("gnss_raw_failure",mapOf("capture_id" to captureId,"error_class" to e.javaClass.simpleName))
                                false
                            }
                        }
                        copyConfirmed=completed==true
                        if(completed==null)trace.record("gnss_raw_failure",mapOf("capture_id" to captureId,"error_class" to "transfer_deadline_90s"))
                    }else trace.record("gnss_raw_failure",mapOf("capture_id" to captureId,"error_class" to "stop_not_confirmed_no_download"))
                }
                closureConfirmed=stopConfirmed && copyConfirmed
            } } finally { sessionOwner.unlock() }
        }
    }
    companion object {
        private val sessionOwner = kotlinx.coroutines.sync.Mutex()
        /** HDOP x 5 m is an estimate, not a measured receiver accuracy.
         * Reject poor HDOP rather than clamping it into the filter's valid range. */
        internal fun decodeFix(reply: org.json.JSONObject, receivedMs: Long, wallMs: Long = System.currentTimeMillis()): LocationUpdate? {
            if (reply.optInt("protocol", -1) != 1 || reply.optString("source") != "lc76g" ||
                !reply.optBoolean("ok") || !reply.optBoolean("reader") || !reply.optBoolean("supported") ||
                !reply.optBoolean("running") || !reply.optBoolean("valid_fix") ||
                reply.optInt("quality", 0) !in 1..2 || reply.optInt("satellites", 0) !in 1..99) return null
            val lat = reply.optDouble("latitude", Double.NaN)
            val lon = reply.optDouble("longitude", Double.NaN)
            val hdop = reply.optDouble("hdop", Double.NaN)
            val age = reply.optLong("fix_age_ms", Long.MAX_VALUE)
            if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0 ||
                !hdop.isFinite() || hdop !in 0.0..8.0 || age !in 0..3000 || age > receivedMs) return null
            // MIN3 used the PC clock. In navigation the phone owns the
            // current wall clock, so reject buffered dates/time explicitly.
            // A missing field from old firmware fails closed, never silently
            // falls back to the phone GPS. Its clock must be correct.
            val receiverUtc = reply.optLong("utc_unix_ms", 0L)
            if (!HecateFixTime.isCurrent(receiverUtc, wallMs)) return null
            val speed = reply.optDouble("speed_mps", Double.NaN).takeIf { it.isFinite() && it >= 0 }
            val course = reply.optDouble("course_deg", Double.NaN).takeIf { it.isFinite() && it in 0.0..360.0 }
            return LocationUpdate(lat = lat, lon = lon,
                bearing = course?.toFloat() ?: Float.NaN,
                speedMps = speed?.toFloat() ?: Float.NaN,
                accuracyM = (hdop * 5).toFloat().coerceAtLeast(1f), elapsedMs = receivedMs - age,
                altitudeMslM = reply.optDouble("altitude_msl_m", Double.NaN).takeIf { it.isFinite() })
        }
    }

}
