package com.example.displayconnect.routing

/** Pure monotonic countdown. A screen never owns this state. */
class RejoinCountdown(private val durationMs: Long = 10_000) {
    data class Quote(val id: Int, val distanceM: Double?, val headingCompatible: Boolean)
    data class Tick(val id: Int, val remainingMs: Long, val fraction: Float, val due: Boolean)
    private var selected: Int? = null
    private var deadline = 0L

    /** All published candidates must have valid road quotes; ties do not pick a branch by list order. */
    fun winner(quotes: List<Quote>): Int? {
        if (quotes.isEmpty() || quotes.any { it.distanceM == null || !it.distanceM.isFinite() || it.distanceM < 0 }) return null
        val best = quotes.minOf { it.distanceM!! }
        // Routing snaps/rounding are not evidence that two coincident branches differ.
        val tied = quotes.filter { it.distanceM!! <= best + 10.0 }
        return tied.singleOrNull()?.id ?: tied.filter { it.headingCompatible }.singleOrNull()?.id
    }

    fun eligible(hasJoinedTrack: Boolean, initialJoinPending: Boolean, away: Boolean,
        fixAgeMs: Long, accuracyM: Float): Boolean = hasJoinedTrack && !initialJoinPending && away &&
            fixAgeMs in 0..3000 && accuracyM.isFinite() && accuracyM in 0f..25f

    fun start(id: Int, nowMs: Long) { selected = id; deadline = nowMs + durationMs }
    fun cancel() { selected = null; deadline = 0 }
    fun tick(nowMs: Long): Tick? = selected?.let {
        val left = (deadline - nowMs).coerceIn(0, durationMs)
        Tick(it, left, 1f - left.toFloat() / durationMs, left == 0L)
    }
}

/** Preserve row identity when the nearest-by-GPS ordering swaps between overlapping branches. */
object RejoinBranches {
    fun align(previous: List<TrackRejoin.JoinCandidate>, current: List<TrackRejoin.JoinCandidate>): List<TrackRejoin.JoinCandidate> {
        val available = current.toMutableList()
        val ordered = previous.mapNotNull { old ->
            available.minByOrNull { kotlin.math.abs(it.progressM - old.progressM) }?.also { available.remove(it) }
        }
        return if (ordered.size == current.size && available.isEmpty()) ordered else current
    }
}
