package com.example.displayconnect.network

import org.json.JSONObject
import com.example.displayconnect.navigation.HecateGnssSource

/** Use the existing navigation position gate even for a standalone status query. */
internal object GnssPositionPresentation {
    fun parse(reply: JSONObject, received: Long, wall: Long = System.currentTimeMillis()): GnssDiagnostic {
        val presented = JSONObject(reply.toString())
        if (HecateGnssSource.decodeFix(reply, received, wall) == null) presented.put("valid_fix", false)
        return GnssDiagnostic.parse(presented, received)
    }
}
