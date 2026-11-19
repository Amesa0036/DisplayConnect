package com.example.displayconnect.navigation

internal object HecateFixTime {
    fun isCurrent(receiverUtcMs: Long, wallMs: Long): Boolean =
        wallMs in 5001L..(Long.MAX_VALUE - 5000L) &&
            receiverUtcMs in (wallMs - 5000L)..(wallMs + 5000L)
}
