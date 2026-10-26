package com.example.displayconnect.routing

import org.junit.Assert.*
import org.junit.Test

class RejoinCountdownTest {
    private fun q(id: Int, m: Double?, heading: Boolean = false) = RejoinCountdown.Quote(id, m, heading)
    @Test fun rowIdentityDoesNotFlipToEndWhenGeometricDistanceOrderSwaps() {
        val start = TrackRejoin.JoinCandidate(0, 0.1, 40.0, LatLon(41.0, 2.0), 10f, 30.0)
        val end = start.copy(segmentIndex = 5000, bearing = 190f, progressM = 107900.0)
        assertEquals(listOf(start, end), RejoinBranches.align(listOf(start, end), listOf(end, start)))
        assertEquals(listOf(end, start), RejoinBranches.align(listOf(end, start), listOf(start, end)))
    }
    @Test fun startAndOffTrackStartNeverTimed() {
        val c = RejoinCountdown()
        assertFalse(c.eligible(false, true, false, 0, 5f))
        assertFalse(c.eligible(false, false, true, 0, 5f))
        assertTrue(c.eligible(true, false, true, 0, 5f))
    }
    @Test fun poorOrStaleFixAndReturningTrackCancelEligibility() {
        val c = RejoinCountdown()
        assertFalse(c.eligible(true, false, false, 0, 5f))
        assertFalse(c.eligible(true, false, true, 3001, 5f))
        assertFalse(c.eligible(true, false, true, -1, 5f))
        assertFalse(c.eligible(true, false, true, 0, 26f))
        assertFalse(c.eligible(true, false, true, 0, Float.NaN))
    }
    @Test fun shortestRoadNotListOrder() {
        assertEquals(2, RejoinCountdown().winner(listOf(q(1, 1800.0), q(2, 450.0))))
    }
    @Test fun incompleteQuotesNeverAutoChoose() {
        assertNull(RejoinCountdown().winner(listOf(q(1, 300.0), q(2, null))))
    }
    @Test fun invalidQuotesNeverAutoChoose() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0))
            assertNull(RejoinCountdown().winner(listOf(q(1, 300.0), q(2, bad))))
    }
    @Test fun coincidentBranchesNeedHeadingNotProgressOrOrder() {
        val c = RejoinCountdown()
        assertNull(c.winner(listOf(q(1, 300.0), q(999, 300.0))))
        assertNull(c.winner(listOf(q(999, 300.0), q(1, 300.0))))
        assertEquals(1, c.winner(listOf(q(999, 300.0), q(1, 300.0, true))))
        assertNull(c.winner(listOf(q(999, 300.0, true), q(1, 300.0, true))))
    }
    @Test fun nearTiesDoNotInventPrecision() {
        assertNull(RejoinCountdown().winner(listOf(q(1, 300.0), q(2, 309.0))))
        assertEquals(1, RejoinCountdown().winner(listOf(q(1, 300.0), q(2, 311.0))))
    }
    @Test fun elapsedClockSurvivesMissingUiTicks() {
        val c = RejoinCountdown(); c.start(9, 100_000)
        assertEquals(10_000L, c.tick(100_000)!!.remainingMs)
        assertEquals(0.5f, c.tick(105_000)!!.fraction)
        assertTrue(c.tick(110_001)!!.due)
        assertEquals(9, c.tick(120_000)!!.id)
    }
    @Test fun cancelAndNewEpisodeDoNotReuseDeadline() {
        val c = RejoinCountdown(); c.start(1, 10); c.cancel(); assertNull(c.tick(20_000))
        c.start(2, 20_000); assertFalse(c.tick(20_001)!!.due)
        assertEquals(2, c.tick(20_001)!!.id)
    }
}
