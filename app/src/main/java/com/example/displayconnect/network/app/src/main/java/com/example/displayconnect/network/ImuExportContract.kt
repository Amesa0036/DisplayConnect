package com.example.displayconnect.network

/** Protocol semantics shared by CSV export and host tests. A fault CSV is evidence, not valid motion data. */
object ImuExportContract {
    fun exportable(protocol: Int, count: Int, capacity: Int, running: Boolean, reason: String): Boolean {
        if (running || count <= 0 || count > capacity || capacity > 12000) return false
        return when (protocol) {
            2 -> reason == "fifo_window_full" && count == capacity
            3 -> reason in setOf("user", "duration_limit", "capacity_limit", "sensor_errors", "sensor_stale")
            else -> false
        }
    }
    fun header(protocol: Int): String = when (protocol) {
        2 -> "sample_ms_nominal,sensor_tick_none,status,ax_raw,ay_raw,az_raw,read_us_unused,fifo_status_prefreeze"
        3 -> "host_read_ms,sensor_sample_counter_24bit,status,ax_raw,ay_raw,az_raw,read_us,statusint_raw"
        else -> error("Protocolo IMU no compatible")
    }
}
