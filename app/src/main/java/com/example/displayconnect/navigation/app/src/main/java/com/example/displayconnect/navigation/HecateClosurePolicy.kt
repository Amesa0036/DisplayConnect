package com.example.displayconnect.navigation

import org.json.JSONObject

/** New close-aware firmware must prove clean closure. Preserve legacy replies
 * only when they never advertised the close-aware contract. */
internal object HecateClosurePolicy {
    private val closeAwareVersions = setOf(
        "0.28.24-175g-ota1-trial-LOCAL",
        "0.28.25-175g-ota1-fix-LOCAL",
        "0.28.26-175g-ota1-nav-LOCAL"
    )
    fun confirmed(reply: JSONObject): Boolean = confirmed(
        reply.optString("firmware"), reply.opt("ok"), reply.opt("running"),
        reply.opt("closing"), reply.opt("close_clean"), reply.opt("receiver_uncertain"),
        listOf("closing", "close_clean", "receiver_uncertain").any { reply.has(it) }
    )
    internal fun confirmed(firmware: String, ok: Any?, running: Any?, closing: Any?,
        clean: Any?, uncertain: Any?, hasCloseFields: Boolean): Boolean {
        if (ok != true || running != false) return false
        if (firmware !in closeAwareVersions && !hasCloseFields) return true
        return closing == false && clean == true && uncertain == false
    }
}
