package com.example.displayconnect.network

object OtaMessages {
    const val CHECKING = "Buscando actualizaciones..."
    const val TRANSFERRING = "Actualizando la placa... no la apagues"
    fun downloading(version: String) = "Descargando firmware $version..."
    fun accepted(version: String) = "Firmware $version transferido y aceptado. Reconecta para comprobar el arranque; aun puede revertirse."
    fun invalidDownload() = "La descarga no es valida. Intentalo de nuevo."
    fun failed(reason: String?) = "Error actualizando: $reason. Comprueba la version al reconectar."
}
