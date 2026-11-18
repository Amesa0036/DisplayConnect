package com.example.displayconnect.models

/** Fuente de posicion para la navegacion. HECATE usa el receptor GNSS de la
 *  placa (el telefono sigue haciendo de router BLE); nunca hay repliegue
 *  silencioso de una a otra. */
enum class GpsSource(val storageKey: String) {
    PHONE("phone"),
    HECATE("hecate");

    companion object {
        fun fromStorage(value: String?): GpsSource =
            values().firstOrNull { it.storageKey == value } ?: PHONE
    }
}
