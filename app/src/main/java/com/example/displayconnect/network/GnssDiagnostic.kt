package com.example.displayconnect.network

import org.json.JSONObject

/** Diagnostic only. Kept outside LocationProvider and navigation state. */
data class GnssDiagnostic(
    val running: Boolean,
    val valid: Boolean,
    val message: String,
    val receivedElapsedMs: Long
) {
    fun display(now: Long): String = if (now - receivedElapsedMs > 4000 || now < receivedElapsedMs)
        "Consulta caducada. Pulsa Consultar estado para una lectura actual. Esta consulta no cambia la fuente de navegación." else message

    companion object {
        fun parse(json: JSONObject, now: Long): GnssDiagnostic {
            check(json.getInt("protocol") == 1 && json.getString("source") == "lc76g") { "Protocolo GPS no compatible" }
            val running = json.getBoolean("running")
            val valid = json.getBoolean("valid_fix")
            val satellites = json.getInt("satellites").also { check(it in 0..99) }
            val quality = json.getInt("quality").also { check(it in 0..9) }
            val reason = json.getString("reason")
            check(reason in setOf("not_started", "running", "manual", "duration", "disconnected", "navigation", "imu_busy", "i2c_error", "invalid_length", "cancelled", "imu_capture", "poll_timeout", "restoration_fault", "session_limit", "initial_drain_timeout", "clock_rollback", "query_free_length_invalid", "stream_without_valid_NMEA_2048B"))
            val state = if (!json.getBoolean("supported")) "GPS propio no disponible en este firmware."
                else if (running) if (valid) "Solución GNSS pareada en esta consulta. Este panel no valida la hora absoluta ni habilita navegación." else "Buscando señal. Sin posición válida."
                else when (reason) {
                    "i2c_error", "invalid_length", "restoration_fault" -> "Lectura detenida por error de comunicación. No se reinicia el bus."
                    "initial_drain_timeout" -> "Lectura detenida: no se pudo completar el vaciado inicial."
                    "clock_rollback", "query_free_length_invalid", "stream_without_valid_NMEA_2048B" -> "Lectura detenida por una comprobación de seguridad. No se reinicia."
                    "duration", "session_limit" -> "Prueba terminada (3 minutos)."
                    "navigation" -> "Prueba detenida al iniciar navegación."
                    "imu_busy" -> "Prueba detenida: captura o datos IMU pendientes."
                    "disconnected" -> "Prueba detenida por desconexión."
                    "manual", "cancelled" -> "Lectura detenida."
                    "imu_capture" -> "Lectura detenida por captura IMU."
                    "poll_timeout" -> "Lectura detenida al caducar las consultas."
                    else -> "Sin iniciar."
                }
            val position = if (valid) {
                check(running && quality in 1..2 && satellites > 0)
                val age = json.getLong("fix_age_ms").also { check(it in 0..3000) }
                val lat = json.getDouble("latitude").also { check(it.isFinite() && it in -90.0..90.0) }
                val lon = json.getDouble("longitude").also { check(it.isFinite() && it in -180.0..180.0) }
                val hdop = if (json.isNull("hdop")) "sin lectura" else json.getDouble("hdop").also { check(it.isFinite() && it in 0.0..99.99) }.toString()
                val altitude = if (json.isNull("altitude_msl_m")) "sin lectura" else "${json.getDouble("altitude_msl_m").also { check(it.isFinite() && it in -1500.0..30000.0) }} m"
                "\nLatitud: $lat\nLongitud: $lon\nEdad: $age ms · Satélites usados: $satellites\nHDOP: $hdop (no es precisión en metros)\nAltitud GNSS MSL: $altitude"
            } else "\nSatélites usados (último GGA): $satellites"
            val counters = listOf("sentences", "gga_count", "checksum_errors", "malformed", "overlong", "i2c_errors", "bytes").associateWith {
                json.getLong(it).also { n -> check(n in 0..4294967295L) }
            }
            val link = if (json.getBoolean("responded")) "Módulo respondió por I2C." else "Sin respuesta confirmada del módulo."
            val message = "$state\n$link$position\nNMEA: ${counters["sentences"]} · GGA: ${counters["gga_count"]} · errores I2C: ${counters["i2c_errors"]} · checksum: ${counters["checksum_errors"]}\nEste panel no cambia la fuente elegida en Ajustes ni controla la ruta."
            return GnssDiagnostic(running, valid, message, now)
        }
    }
}
