package com.example.displayconnect.navigation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No source is started if STOP/copy failed, or if the owner cancelled meanwhile.
 * Read selection only AFTER closure, so changes made during the copy win. */
internal suspend fun <T> sourceHandoff(
    close: suspend () -> Boolean,
    latestSelection: suspend () -> T,
    restart: suspend (T) -> Unit
): Boolean {
    val confirmed=close()
    currentCoroutineContext().ensureActive()
    if(!confirmed) return false
    val latest=latestSelection()
    currentCoroutineContext().ensureActive()
    restart(latest)
    return true
}

/** Preflight may suspend for BLE capability checks. If selection changed while it
 * ran, discard the unused source and prepare the newest explicit choice instead.
 * prepare never starts GPS, so discarded candidates own no hardware session. */
internal suspend fun <T,R> latestPreparedSource(
    latestSelection: suspend () -> T,
    prepare: suspend (T) -> R
): Pair<T,R> {
    var selected=latestSelection()
    while(true) {
        currentCoroutineContext().ensureActive()
        val candidate=prepare(selected)
        val latest=latestSelection()
        currentCoroutineContext().ensureActive()
        if(latest==selected) return selected to candidate
        selected=latest
    }
}
