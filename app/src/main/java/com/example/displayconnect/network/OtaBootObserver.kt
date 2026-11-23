package com.example.displayconnect.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Only observes existing reconnect behavior. Never starts a transfer or retries one. */
suspend fun observeOtaBoot(
    before: OtaBootIdentity, expected: String, oldGeneration: Long,
    generation: () -> Long, sameBoard: () -> Boolean,
    identity: suspend () -> OtaBootIdentity, boardInfo: suspend () -> Unit,
    observed: (OtaBootIdentity) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) }
): OtaBootIdentity {
    val boot=withTimeout(90_000) {
        while(generation()==oldGeneration) pause(100)
        while(!sameBoard()) pause(250)
        identity()
    }
    boot.requireReplacement(before,expected)
    val bootGeneration=generation()
    boardInfo()
    observed(boot)
    pause(125_000)
    check(sameBoard() && generation()==bootGeneration) { "Enlace interrumpido durante comprobacion de arranque" }
    val stable=identity()
    check(stable==boot) { "Version o slot han cambiado" }
    boardInfo()
    return stable
}
