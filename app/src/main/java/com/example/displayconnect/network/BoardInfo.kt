package com.example.displayconnect.network

/** Reported by the connected board, not inferred from its advertised name.
 * Missing capability preserves legacy boards. Only an explicit false disables
 * screen/config frames. GPS commands use a separate request/reply path.
 */
data class BoardInfo(
    val id: String,
    val name: String,
    val address: String,
    val hardwareModel: String? = null,
    val navigationDisplay: Boolean = true,
    val otaEnabled: Boolean? = null
)
internal fun navigationDisplayCapability(raw: Any?): Boolean =
    raw != false
