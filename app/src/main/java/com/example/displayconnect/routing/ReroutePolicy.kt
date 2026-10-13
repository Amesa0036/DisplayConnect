package com.example.displayconnect.routing

/** Sustained geometric deviation, not one noisy GPS fix. All times are monotonic. */
class ReroutePolicy {
    private var firstAwayMs: Long? = null
    private var lastSampleMs: Long? = null
    private var samples = 0
    private var nextRequestMs = 0L

    fun isAway(distanceM: Double?, accuracyM: Float): Boolean =
        distanceM != null && distanceM.isFinite() && accuracyM.isFinite() &&
            accuracyM in 0f..25f && distanceM > maxOf(30.0, accuracyM.toDouble() * 2)

    fun observe(distanceM: Double?, accuracyM: Float, sampleMs: Long, nowMs: Long): Boolean {
        if (nowMs - sampleMs !in 0..3000 || !isAway(distanceM, accuracyM)) {
            clearEvidence()
            return false
        }
        val previous = lastSampleMs
        if (previous != null && sampleMs <= previous) return false
        if (previous != null && sampleMs - previous > 3000) clearEvidence()
        if (firstAwayMs == null) firstAwayMs = sampleMs
        lastSampleMs = sampleMs
        samples++
        return samples >= 3 && sampleMs - firstAwayMs!! >= 4000 && nowMs >= nextRequestMs
    }

    fun requestStarted(nowMs: Long) { nextRequestMs = nowMs + 30_000 }
    fun requestFinished(nowMs: Long) { nextRequestMs = maxOf(nextRequestMs, nowMs + 30_000) }
    fun clearEvidence() { firstAwayMs = null; lastSampleMs = null; samples = 0 }
    fun reset() { clearEvidence(); nextRequestMs = 0 }
}
