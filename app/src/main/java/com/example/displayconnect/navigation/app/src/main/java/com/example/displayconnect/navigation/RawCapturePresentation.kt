package com.example.displayconnect.navigation

/** Phone-local presentation only. Never controls the receiver or claims persistence. */
data class RawCaptureNotice(val text: String = "", val copying: Boolean = false)
object RawCapturePresentation {
    const val EXPLANATION = "Con el firmware de diagnostico, la placa conserva datos originales del GPS de la primera sesion de cada arranque. El espacio es limitado y puede guardar solo una parte. Tras parar, la app intenta copiarlos a este movil. No se envian automaticamente. La copia contiene ubicaciones."
    fun update(previous: RawCaptureNotice, event: String, fields: Map<String,Any?>): RawCaptureNotice = when(event) {
        "session_start" -> RawCaptureNotice()
        "gnss_raw_capability" -> RawCaptureNotice(when {
            fields["available"] != true -> "La placa no pudo reservar espacio para datos originales."
            fields["current"] != true -> "Esta sesion no tiene una captura nueva; se conserva la primera de este arranque."
            else -> "Guardando datos originales en la placa. Se copiaran al movil al parar."
        })
        "gnss_raw_unsupported" -> RawCaptureNotice("Este firmware no ofrece datos originales. El registro habitual sigue disponible.")
        "gnss_raw_transfer_start" -> RawCaptureNotice("GPS parado. Copiando datos al movil... puede tardar hasta 90 s. Manten la placa conectada.",true)
        "gnss_raw_complete" -> RawCaptureNotice(if(fields["capture_complete"]==true)
            "Copia recibida. Falta comprobar el archivo exportado antes de dar el registro por completo."
            else "Captura parcial: se lleno el espacio de la placa. Se copio el tramo conservado. No es un registro completo.")
        "gnss_raw_failure" -> RawCaptureNotice("No se pudo completar la copia. El registro puede estar incompleto. No se borro la captura de la placa.")
        "session_end" -> if(previous.copying) RawCaptureNotice("No se pudo completar la copia. El registro puede estar incompleto. No se borro la captura de la placa.") else previous
        else -> previous
    }
}
