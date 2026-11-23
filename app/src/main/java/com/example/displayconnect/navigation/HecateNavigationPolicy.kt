package com.example.displayconnect.navigation

import org.json.JSONObject

/** A supported diagnostic reader is not a navigation-capable lifetime. */
internal object HecateNavigationPolicy {
    private val diagnosticOnly = setOf(
        "0.28.23-175g-ota1-trial-LOCAL",
        "0.28.24-175g-ota1-trial-LOCAL",
        "0.28.25-175g-ota1-fix-LOCAL"
    )
    fun supported(reply: JSONObject): Boolean = supported(reply.optString("firmware"),
        reply.opt("ok"), reply.opt("supported"), reply.opt("reader"))
    internal fun supported(firmware: String, ok: Any?, supported: Any?, reader: Any?): Boolean =
        ok == true && supported == true && reader == true && firmware !in diagnosticOnly
}
