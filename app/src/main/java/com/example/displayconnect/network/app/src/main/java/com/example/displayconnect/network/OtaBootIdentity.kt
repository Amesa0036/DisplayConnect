package com.example.displayconnect.network

/** Read-only reply from the existing Hecate receiver. Never infers a slot from version. */
data class OtaBootIdentity(val version: String, val slot: String) {
    companion object {
        fun parse(line: String): OtaBootIdentity {
            val m = Regex("^OTA:VERSION ([A-Za-z0-9._-]{1,31}) (ota_[01])$").matchEntire(line)
                ?: error("Respuesta de version/slot no valida")
            return OtaBootIdentity(m.groupValues[1], m.groupValues[2])
        }
    }
    fun requireReplacement(before: OtaBootIdentity, expected: String) {
        check(version == expected && slot != before.slot) { "Arranque no confirmado: version o slot inesperado" }
    }
}
