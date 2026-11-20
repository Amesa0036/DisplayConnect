package com.example.displayconnect.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** A cold Hecate fix can take longer than the phone's fused position.
 * This bounds acquisition only. It never ends an active navigation session,
 * changes source, weakens fix validation or starts a retry.
 */
internal fun firstPositionTimeoutMs(boardGnss: Boolean): Long =
    if (boardGnss) 180_000L else 45_000L

internal suspend fun awaitFirstPosition(
    positions: Flow<LocationUpdate>,
    boardGnss: Boolean,
    accept: (LocationUpdate) -> Boolean
): LocationUpdate = withTimeout(firstPositionTimeoutMs(boardGnss)) {
    positions.first { accept(it) }
}
