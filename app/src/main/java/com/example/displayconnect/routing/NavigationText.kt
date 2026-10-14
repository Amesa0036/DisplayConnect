package com.example.displayconnect.routing

object NavigationText {
    fun maneuver(step: RouteStep?, language: String): String {
        if (step == null) return instruction("Continue",language)
        // Text and icon share one canonical decision, never independent mappings.
        val canonical = when (ManeuverSelector.code(step)) {
            "arrive" -> "Arrive at destination"
            "uturn" -> "U-turn"
            "roundabout" -> "Roundabout"
            "left" -> "Turn left"
            "right" -> "Turn right"
            "slight_left" -> "Slight left"
            "slight_right" -> "Slight right"
            "sharp_left" -> "Sharp left"
            "sharp_right" -> "Sharp right"
            "straight" -> if (step.maneuverType == "depart") "Start route" else "Continue straight"
            else -> if (step.maneuverType in listOf("exit roundabout", "exit rotary")) "Exit roundabout" else "Follow route"
        }
        val translated=instruction(canonical,language)
        return if (language == "es" && step.maneuverType in listOf("roundabout","rotary") && (step.maneuverExit ?: 0)>0)
            "Rotonda: salida ${step.maneuverExit}" else translated
    }
    fun instruction(english: String, language: String): String {
        if (language == "en") return english
        if (language == "es") return when (english) {
            "Arrive at destination" -> "Has llegado al destino"
            "Start route" -> "Inicia la ruta"
            "Turn right" -> "Gira a la derecha"
            "Turn left" -> "Gira a la izquierda"
            "Slight right" -> "Mantente a la derecha"
            "Slight left" -> "Mantente a la izquierda"
            "Sharp right" -> "Giro cerrado a la derecha"
            "Sharp left" -> "Giro cerrado a la izquierda"
            "Continue straight", "Continue" -> "Sigue recto"
            "Uturn", "U-turn" -> "Cambia de sentido"
            "Roundabout", "Rotary", "Roundabout turn" -> "Entra en la rotonda"
            "Exit roundabout", "Exit rotary" -> "Sal de la rotonda"
            "Merge" -> "Incorpórate"
            "Fork" -> "Sigue en la bifurcación"
            "On ramp" -> "Toma el acceso"
            "Off ramp" -> "Toma la salida"
            "End of road" -> "Fin de la vía"
            "New name", "Notification" -> "Continúa por la vía"
            else -> "Sigue la ruta" // Unknown OSRM type is not an invented direction.
        }
        return when (english) {
            "Arrive at destination" -> "Você chegou ao destino"
            "Start route" -> "Inicie o percurso"
            "Turn right" -> "Vire à direita"
            "Turn left" -> "Vire à esquerda"
            "Slight right" -> "Mantenha-se à direita"
            "Slight left" -> "Mantenha-se à esquerda"
            "Sharp right" -> "Curva fechada à direita"
            "Sharp left" -> "Curva fechada à esquerda"
            "Continue straight", "Continue" -> "Siga em frente"
            "Uturn", "U-turn" -> "Faça o retorno"
            "Roundabout", "Rotary", "Roundabout turn" -> "Entre na rotatória"
            "Exit roundabout", "Exit rotary" -> "Saia da rotatória"
            "Merge" -> "Entre na via"
            "Fork" -> "Siga na bifurcação"
            "On ramp" -> "Entre no acesso"
            "Off ramp" -> "Saia pelo acesso"
            "End of road" -> "Fim da via"
            "New name", "Notification" -> "Continue na via"
            else -> english
        }
    }
}
