package com.example.displayconnect.network
import org.json.JSONObject

/** Only the diagnostic trial has an approved public distribution endpoint.
 * These are release-metadata URLs, not redirects to a private Drive file.
 * Stable and trial must remain separate, even when hosted on the same service. */
object FirmwareChannelPolicy {
    const val ORIGINAL = "https://api.github.com/repos/Amesa0036/DisplayConnect/releases/latest"
    val hecateUseSource: String? = null
    val hecateTrialSource: String? = "https://api.github.com/repos/Amesa0036/DisplayConnect/releases/454784775"
    fun source(model: String?, trial: Boolean): String? = when(model) {
        HardwareUpdatePolicy.C -> { require(!trial) { "Las pruebas Hecate no admiten la Original" }; ORIGINAL }
        HardwareUpdatePolicy.G -> if(trial) hecateTrialSource else hecateUseSource
        else -> error("Modelo no confirmado")
    }
    fun requireChannel(model:String?, trial:Boolean, m:JSONObject) {
        if(model==HardwareUpdatePolicy.C) { require(!trial); return }
        require(model==HardwareUpdatePolicy.G)
        require(m.optString("channel")==if(trial) "trial" else "use") { "Canal distinto del solicitado" }
        require(m.optBoolean("diagnostic",false)==trial && m.has("diagnostic")) { "Tipo de firmware no confirmado" }
        if(trial) require(m.optString("trial_kind")=="gnss-close-once-v1") { "Tipo de prueba no admitido por esta app" }
        val v=m.getString("version")
        if(trial) require(v=="0.28.23-175g-ota1-trial-LOCAL") { "Version de prueba no revisada por esta app" }
        if(!trial) require(listOf("trial","close","LOCAL","rawdiag").none{v.contains(it,true)}) { "Diagnostico bloqueado en canal de uso" }
        require(m.getString("bin_url").startsWith("https://")) { "Descarga no segura" }
    }
}
