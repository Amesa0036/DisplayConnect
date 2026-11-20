package com.example.displayconnect.navigation

/** Fixed diagnostic codes only. Never expose exception text, coordinates or URLs. */
internal enum class NavigationStage { POSITION, ROUTE, CACHE, STREAM }
internal enum class NavigationFailure {
    HECATE_I2C, HECATE_UNAVAILABLE, HECATE_BUSY, HECATE_SESSION,
    POSITION, ROUTE, CACHE, STREAM
}
internal fun navigationFailure(stage: NavigationStage, error: Exception): NavigationFailure {
    val reason = error.message.orEmpty()
    return when {
        reason == "gnss_stopped:i2c_error" -> NavigationFailure.HECATE_I2C
        reason == "unsupported_or_unavailable" -> NavigationFailure.HECATE_UNAVAILABLE
        reason == "busy" || reason == "already_running" -> NavigationFailure.HECATE_BUSY
        reason.startsWith("gnss_stopped:") -> NavigationFailure.HECATE_SESSION
        else -> when(stage) {
            NavigationStage.POSITION -> NavigationFailure.POSITION
            NavigationStage.ROUTE -> NavigationFailure.ROUTE
            NavigationStage.CACHE -> NavigationFailure.CACHE
            NavigationStage.STREAM -> NavigationFailure.STREAM
        }
    }
}
internal fun navigationFailureMessage(failure: NavigationFailure, language: String): String {
    val messages = when (language) {
        "en" -> listOf(
            "Hecate GPS stopped after an I2C error. No automatic restart.",
            "Hecate GPS is not available in this firmware.",
            "Hecate GPS is busy or already running. No new session started.",
            "Hecate GPS session stopped. No automatic restart.",
            "Unable to obtain a position from the selected source.",
            "Unable to calculate the route. Check internet and the destination.",
            "Unable to save the route on this phone.",
            "Position updates stopped. Navigation has ended.")
        "es" -> listOf(
            "El GPS de Hecate se ha detenido por un error I2C. Sin reinicio automatico.",
            "El GPS de Hecate no esta disponible en este firmware.",
            "El GPS de Hecate esta ocupado o ya estaba activo. No se inicia otra sesion.",
            "La sesion GPS de Hecate se ha detenido. Sin reinicio automatico.",
            "No se pudo obtener posicion de la fuente elegida.",
            "No se pudo calcular la ruta. Comprueba internet y el destino.",
            "No se pudo guardar la ruta en este movil.",
            "Se ha interrumpido la posicion. Navegacion terminada.")
        else -> listOf(
            "O GPS Hecate parou apos um erro I2C. Sem reinicio automatico.",
            "O GPS Hecate nao esta disponivel neste firmware.",
            "O GPS Hecate esta ocupado ou ja estava ativo. Sem nova sessao.",
            "A sessao GPS Hecate parou. Sem reinicio automatico.",
            "Nao foi possivel obter posicao da fonte escolhida.",
            "Nao foi possivel calcular a rota. Confira a internet e o destino.",
            "Nao foi possivel guardar a rota neste telefone.",
            "A posicao foi interrompida. Navegacao terminada.")
    }
    return "[${failure.name}] " + messages[failure.ordinal]
}
