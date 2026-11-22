package com.example.displayconnect.network

/** Explicit identity first. Only the archived C-only r3 is a legacy fallback. */
object HardwareUpdatePolicy {
    const val C = "waveshare_s3_175c"
    const val G = "waveshare_s3_175g"
    private val legacyC = setOf("0.24.0-shadow-r3")
    fun resolve(model: String?, version: String?): String? = when {
        model == C || model == G -> model
        model != null -> null
        version in legacyC -> C
        else -> null
    }
    fun requireLegacyOtaTarget(model: String?) {
        require(model == C) { "El canal C no admite esta placa" }
    }
    /** Reserved local candidate contract, not an assertion that a G release exists.
     * A missing G manifest must fail, never fall back to firmware.json. */
    fun manifestName(model: String?): String = when (model) {
        C -> "firmware.json"
        G -> "firmware-hecate-175g.json"
        else -> error("Modelo de placa no confirmado; actualizacion bloqueada")
    }
    fun requireOtaTarget(model: String?, enabled: Boolean?) {
        require(model == C || model == G) { "Modelo de placa no confirmado; actualizacion bloqueada" }
        require(enabled != false && (model != G || enabled == true)) {
            "Esta placa necesita primero el receptor OTA por USB; no se usara otro canal"
        }
    }
    private fun field(bin: ByteArray, offset: Int): String {
        val end = (offset until offset + 32).firstOrNull { bin[it] == 0.toByte() }
            ?: error("Campo ESP sin terminacion")
        return bin.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }
    /** Header matching is not signature verification. The board verifies the signature. */
    fun requireImage(model: String?, bin: ByteArray, expectedVersion: String? = null) {
        require(model == C || model == G) { "Modelo no confirmado" }
        require(bin.size in 112..0x400000 && (bin[0].toInt() and 255) == 0xe9) { "Imagen ESP no valida" }
        require((0..3).all { (bin[32 + it].toInt() and 255) == intArrayOf(0x32,0x54,0xcd,0xab)[it] }) { "Descriptor ESP no valido" }
        require(field(bin,80) == if (model == G) "hecate-175g" else "muse-gadget") {
            "Firmware de otra familia; actualizacion bloqueada"
        }
        val version = field(bin,48)
        if (model == G) require("-ota1-" in version) { "La imagen no conserva el receptor OTA de Hecate" }
        if (expectedVersion != null) require(version == expectedVersion) { "Version de imagen distinta del manifiesto" }
    }
    fun requireCImage(bin: ByteArray) = requireImage(C, bin)
    fun requireManifest(model: String?, hardware: String?, project: String?, generation: String?, version: String, size: Int, sha: String) {
        manifestName(model)
        require(version.isNotBlank() && size in 112..0x400000 && sha.matches(Regex("[0-9a-f]{64}"))) { "Manifiesto no valido" }
        if (model == G) require(hardware == G && project == "hecate-175g" && generation == "ota1" && "-ota1-" in version) {
            "Manifiesto Hecate incompatible; no se usara el canal C"
        }
        if (model == C && hardware != null) require(hardware == C) { "Manifiesto de otra placa" }
    }
    fun requireAck(line: String, sent: Int) {
        require(line == "OTA:ACK $sent") { "Confirmacion OTA fuera de secuencia: $line" }
    }
}
