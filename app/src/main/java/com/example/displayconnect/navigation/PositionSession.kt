package com.example.displayconnect.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One hardware session across first fix, route preparation and navigation.
 * Slow route work keeps only the newest position, without stopping the reader.
 * The owner must cancel the session on success, error or explicit stop. */
internal suspend fun <T> withPositionSession(
    source: PositionSource,
    intervalMs: Long,
    block: suspend (Flow<LocationUpdate>) -> T
): T = coroutineScope {
    val latest = Channel<LocationUpdate>(Channel.CONFLATED)
    val reader = launch {
        try {
            source.locationFlow(intervalMs).collect { latest.send(it) }
            latest.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            latest.close(e)
            throw e
        }
    }
    try {
        block(latest.receiveAsFlow())
    } finally {
        withContext(NonCancellable) { reader.cancelAndJoin() }
        latest.cancel()
    }
}
