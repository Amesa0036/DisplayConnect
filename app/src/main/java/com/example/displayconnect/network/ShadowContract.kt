package com.example.displayconnect.network

import org.json.JSONObject
import kotlin.math.ceil

/** Independent measurement snapshot, never built from last serialized NAV or displayed speed. */
data class ShadowFix(val measurementMs: Long, val accepted: Boolean, val speedMps: Float,
    val accuracyM: Float)

class ShadowGpsSource {
    @Volatile private var latest: ShadowFix? = null
    fun update(fix: ShadowFix) { latest = fix }
    fun clear() { latest = null }
    fun response(request: JSONObject, nowMs: Long): JSONObject {
        val epoch = strictLong(request, "epoch", 1, 4294967295L)
        val challenge = strictLong(request, "challenge", 1, 9007199254740991L)
        val fix = latest
        val age = fix?.let { nowMs - it.measurementMs }
        val valid = fix != null && fix.accepted && fix.measurementMs > 0 &&
            age != null && age in 0..3000 && fix.accuracyM.isFinite() && fix.accuracyM in 0f..25f &&
            fix.speedMps.isFinite() && fix.speedMps in 0f..100f
        return JSONObject().put("type", "shadow_gps").put("epoch", epoch).put("challenge", challenge)
            .put("fix_id", if (valid) fix!!.measurementMs else 0)
            // Measurement clock is truncated to ms by LocationTracker. Add one ms to bound truncation.
            .put("age_ms", if (valid) age!! + 1 else 3001)
            .put("accepted", valid).put("has_speed", valid)
            .put("accuracy_mm", if (valid) ceil(fix!!.accuracyM.toDouble() * 1000).toLong() else 25001)
            .put("speed_mmps", if (valid) ceil(fix!!.speedMps.toDouble() * 1000).toLong() else 0)
    }
}

internal fun strictLong(o: JSONObject, key: String, min: Long, max: Long): Long {
    val v = o.get(key)
    require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble() && v.toLong() in min..max) { "Campo no valido: $key" }
    return v.toLong()
}

object ShadowExportContract {
    const val HEADER = "elapsed_ms,lateness_ms,skipped_intervals,reason,possible_rest_only,window_samples,imu_metrics_valid,span_g,rms_g,baseline_change_g,gps_age_valid,gps_age_bound_ms"
    fun rows(page: JSONObject, session: Long, offset: Int, count: Int): Pair<String, Int> {
        require(strictLong(page,"protocol",1,1) == 1L && strictLong(page,"session",1,4294967295L) == session &&
            page.get("running") is Boolean && !page.getBoolean("running") && strictLong(page,"offset",0,182).toInt() == offset && strictLong(page,"count",1,182).toInt() == count)
        val rows = page.getJSONArray("rows")
        require(rows.length() in 1..8 && offset + rows.length() <= count)
        val text = StringBuilder()
        repeat(rows.length()) { i ->
            val r = rows.getJSONArray(i); require(r.length() == 12)
            for (j in 0..11) {
                val v = r.get(j)
                if (j in listOf(4,6,10)) require(v is Boolean)
                else {
                    require(v is Number && v.toDouble().isFinite() && v.toDouble() >= 0)
                    if (j !in 7..9) require(v.toDouble() == v.toLong().toDouble() && v.toLong() <= 4294967295L)
                }
            }
            require(r.getInt(3) in 0..12 && r.getBoolean(4) == (r.getInt(3) == 12))
            require(!r.getBoolean(4) || (r.getBoolean(6) && r.getBoolean(10)))
            require(!r.getBoolean(6) || r.getInt(5) in 55..80)
            require(!r.getBoolean(10) || r.getLong(11) <= 3000)
            text.append((0..11).joinToString(",") { j -> r.get(j).toString() }).append('\n')
        }
        return text.toString() to rows.length()
    }
}
