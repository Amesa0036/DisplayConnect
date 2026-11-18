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
 * Sin repliegue silencioso: si la sesion muere se reintenta el arranque y, si
 * la placa no soporta gnss_diag, el flujo termina con error y la navegacion no
 * arranca (la eleccion de fuente es del usuario en ajustes).
 */
class HecateGnssSource(private val navClient: BleNavClient) : PositionSource {

    override fun locationFlow(intervalMs: Long): Flow<LocationUpdate> = flow {
        // El modulo emite a 1 Hz; sondear mas rapido no aporta nada y satura BLE.
        val pollMs = intervalMs.coerceIn(1000L, 5000L)
        var started = false
        // Backoff ante sesiones no productivas: un modulo bloqueado (9211,
        // i2c_error) no se reintenta a 1 Hz indefinidamente. 2s,4s,...,30s y
        // tras 5 fracasos seguidos el flujo termina con error visible.
        var failures = 0
        var backoffMs = pollMs
        try {
            while (true) {
                val reply = navClient.gnssRawCommand(if (started) "status" else "start").getOrThrow()
                if (!reply.optBoolean("ok", false)) {
                    val error = reply.optString("error", "gnss_diag rechazado")
                    // Ocupado transitorio (captura IMU u OTA, acotados en el
                    // tiempo; la OTA ademas reinicia la placa y corta BLE):
                    // espera sin contar como fallo ni hacer buscar en caliente.
                    if (error == "already_running") {
                        started = true
                        delay(pollMs)
                        continue
                    }
                    if (error == "busy") {
                        delay(pollMs)
                        continue
                    }
                    throw IllegalStateException(error)
                }
                // supported distingue placa con GNSS; "reader" distingue el
                // firmware con reader completo del antiguo reserve1 (que solo
                // hace prueba de longitud y nunca daria posicion en navegacion).
                if (!reply.optBoolean("supported", false) || !reply.optBoolean("reader", false)) {
                    throw IllegalStateException("unsupported_or_unavailable")
                }
                if (!reply.optBoolean("running", false)) {
                    // restoration_fault es terminal (la restauracion fallo y el
                    // firmware rechaza nuevas sesiones); el resto se reintenta.
                    if (reply.optString("reason") == "restoration_fault") {
                        throw IllegalStateException("restoration_fault")
                    }
                    val ended = started // la sesion corria y murio (i2c_error, invalid_length, imu_capture...)
                    started = false
                    if (ended) {
                        failures++
                        if (failures >= 5) throw IllegalStateException("gnss_session_unstable:" + reply.optString("reason"))
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                        delay(backoffMs)
                    } else {
                        delay(pollMs)
                    }
                    continue
                }
                started = true
                if (reply.optBoolean("valid_fix", false)) {
                    failures = 0; backoffMs = pollMs
                    decodeFix(reply, SystemClock.elapsedRealtime())?.let { emit(it) }
                }
                delay(pollMs)
            }
        } finally {
            if (started) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withTimeoutOrNull(1500) { navClient.gnssRawCommand("stop") }
            }
        }
    }
    companion object {
        /** HDOP x 5 m is an estimate, not a measured receiver accuracy.
         * Reject poor HDOP rather than clamping it into the filter's valid range. */
        internal fun decodeFix(reply: org.json.JSONObject, receivedMs: Long): LocationUpdate? {
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
