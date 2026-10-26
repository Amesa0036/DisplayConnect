package com.example.displayconnect.routing

/**
 * Pasa una ruta GPX preparada del ViewModel al servicio de navegacion.
 * Una RouteData completa no cabe con seguridad en los extras de un Intent.
 */
object PendingRouteHolder {
    @Volatile private var route: RouteData? = null
    @Volatile var label: String = ""
        private set

    fun put(value: RouteData, name: String) {
        route = value
        label = name
    }

    fun take(): RouteData? {
        val value = route
        route = null
        return value
    }

    fun clear() {
        route = null
        label = ""
    }
}
