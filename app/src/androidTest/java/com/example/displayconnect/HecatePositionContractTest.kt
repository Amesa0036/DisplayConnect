package com.example.displayconnect

import com.example.displayconnect.navigation.HecateGnssSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic coordinates only. Tests the production decoder, not a second parser. */
class HecatePositionContractTest {
    private fun fix() = JSONObject("""{"protocol":1,"source":"lc76g","ok":true,"reader":true,"supported":true,"running":true,"valid_fix":true,"quality":1,"satellites":9,"latitude":40.0,"longitude":1.0,"hdop":0.8,"fix_age_ms":100,"speed_mps":2.0,"course_deg":90.0,"altitude_msl_m":15.0}""")

    @Test fun freshReceiverFixKeepsAgeAndEstimatedAccuracy() {
        val sample = HecateGnssSource.decodeFix(fix(), 1000)!!
        assertEquals(40.0, sample.lat, 0.0)
        assertEquals(900L, sample.elapsedMs)
        assertEquals(4f, sample.accuracyM, 0f)
        assertEquals(90f, sample.bearing, 0f)
    }

    @Test fun boundsFreshnessAndProvenanceAreRequired() {
        val invalid = listOf(
            fix().put("latitude", 91), fix().put("longitude", -181),
            fix().put("fix_age_ms", 3001), fix().put("fix_age_ms", -1),
            fix().put("hdop", 9), fix().put("hdop", JSONObject.NULL),
            fix().put("quality", 0), fix().put("satellites", 0),
            fix().put("source", "phone"), fix().put("protocol", 2),
            fix().put("reader", false), fix().put("supported", false),
            fix().put("running", false), fix().put("valid_fix", false),
            fix().put("ok", false), fix().put("latitude", JSONObject.NULL)
        )
        for (reply in invalid) assertNull(reply.toString(), HecateGnssSource.decodeFix(reply, 10000))
        assertNull(HecateGnssSource.decodeFix(fix(), 50))
    }

    @Test fun unknownSpeedAndCourseRemainUnknownRatherThanZero() {
        val sample = HecateGnssSource.decodeFix(fix().put("speed_mps", -1).put("course_deg", JSONObject.NULL), 1000)!!
        assertTrue(sample.speedMps.isNaN())
        assertTrue(sample.bearing.isNaN())
    }
}
