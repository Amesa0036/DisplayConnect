package com.example.displayconnect.network

/** Exact reviewed transitions only. Unsigned nav candidate remains blocked. */
internal object LocalFirmwareTrialPolicy {
    const val CLOSE_VERSION = "0.28.24-175g-ota1-trial-LOCAL"
    const val POSITION_VERSION = "0.28.25-175g-ota1-fix-LOCAL"
    const val NAV_VERSION = "0.28.26-175g-ota1-nav-LOCAL"
    private const val NAV_PAYLOAD_SHA256 = "4551117f35b6bd9b68abc75b08d7615199e6c64ccee06790fc13da72abea3831"
    private val navSignedSha256: String? = "0c791c9fc95ca224296cf6872147dd4a672ca20d9389388718a91495d9550581" // Exact full signed image approved for artifact preparation; installation is separate.
    private const val CLOSE_SHA256 = "bc2abb3a30142fc0e22a156b50aef18d4fa5b46aca8d365b2584ce9a28bde88a"
    private const val POSITION_PAYLOAD_SHA256 = "7fe67d62305b5b00e42e597ccec9882744186926562b768dbe31722dc8c2f8a4"
    // Signed locally after explicit approval; independently verified. Distribution remains separate.
    private val positionSignedSha256: String? = "d02adb72889eab600d70b3822dfbc38a60a2b534cc9bf442a2e504e82ba7b482"

    data class Trial(val purpose: String, val target: String, val requiredBefore: String,
        val recoveryBefore: String? = null)
    fun requireCandidate(image: LocalFirmwareImage): Trial = when(image.version) {
        CLOSE_VERSION -> {
            check(image.sha256 == CLOSE_SHA256) { "Imagen .24 distinta de la revisada" }
            Trial("close_checkpoint", CLOSE_VERSION, CLOSE_VERSION)
        }
        POSITION_VERSION -> {
            check(image.size == 659456 && LocalFirmwareFile.sha(image.bytes.copyOfRange(0,655360)) == POSITION_PAYLOAD_SHA256) { "Contenido .25 distinto del revisado" }
            val approved = positionSignedSha256 ?: error("La imagen .25 firmada todavia no esta fijada en esta app; envio bloqueado")
            check(image.sha256 == approved) { "Imagen .25 firmada distinta de la revisada" }
            Trial("stationary_position_once", POSITION_VERSION, CLOSE_VERSION, NAV_VERSION)
        }
        NAV_VERSION -> {
            check(image.size == 659456 && LocalFirmwareFile.sha(image.bytes.copyOfRange(0,655360)) == NAV_PAYLOAD_SHA256) { "Contenido .26 distinto del revisado" }
            val approved = navSignedSha256 ?: error("La imagen .26 aun no esta firmada y fijada; envio bloqueado")
            check(image.sha256 == approved) { "Imagen .26 firmada distinta de la revisada" }
            Trial("mobile_navigation_once", NAV_VERSION, POSITION_VERSION)
        }
        else -> error("Version fuera de las transiciones locales revisadas")
    }
    fun requireBefore(trial: Trial, before: OtaBootIdentity) {
        check(before.version == trial.requiredBefore || before.version == trial.recoveryBefore) { "Version instalada inesperada; no se enviara" }
    }
}
