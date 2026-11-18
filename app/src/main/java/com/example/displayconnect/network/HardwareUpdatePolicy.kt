package com.example.displayconnect.network

/** Explicit identity first. Only the archived C-only r3 is a legacy fallback. */
object HardwareUpdatePolicy {
    const val C = "waveshare_s3_175c"
    const val G = "waveshare_s3_175g"
    private val legacyC = setOf("0.24.0-shadow-r3")
    fun resolve(model: String?, version: String?): String? = when {
        model == C || model == G -> model
        model != null -> null // Unknown explicit identities never downgrade to legacy.
        version in legacyC -> C
        else -> null
    }
    fun requireLegacyOtaTarget(model: String?) {
        require(model == C) {
            if (model == G) "Hecate aun no tiene una actualizacion publicada; no se usara el canal C"
            else "Modelo de placa no confirmado; actualizacion bloqueada"
        }
    }
    /** ESP-IDF image app descriptor, fixed at offset 32. Signed bytes include this field. */
    fun requireCImage(bin: ByteArray) {
        require(bin.size >= 112 && (bin[0].toInt() and 255) == 0xe9) { "Imagen ESP no valida" }
        require((0..3).all { (bin[32 + it].toInt() and 255) == intArrayOf(0x32,0x54,0xcd,0xab)[it] }) { "Descriptor ESP no valido" }
        val field = bin.copyOfRange(80,112)
        val end = field.indexOf(0).let { if (it < 0) field.size else it }
        require(field.copyOfRange(0,end).toString(Charsets.US_ASCII) == "muse-gadget") { "Firmware de otra familia; actualizacion bloqueada" }
    }
}
