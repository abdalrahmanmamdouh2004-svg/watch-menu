package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionStabilityTest {
    @Test
    fun activeSessionIsRetainedForShortHandoverButExpiresAfterGrace() {
        val grace = MediaSessionGracePeriod(1_000L)
        assertTrue(grace.shouldRetainCurrent(100L))
        assertTrue(grace.shouldRetainCurrent(1_099L))
        assertFalse(grace.shouldRetainCurrent(1_100L))
    }

    @Test
    fun returningSessionResetsTheMissingWindow() {
        val grace = MediaSessionGracePeriod(1_000L)
        assertTrue(grace.shouldRetainCurrent(100L))
        grace.onSessionPresent()
        assertTrue(grace.shouldRetainCurrent(5_000L))
        assertTrue(grace.shouldRetainCurrent(5_999L))
        assertFalse(grace.shouldRetainCurrent(6_000L))
    }

    @Test
    fun transientPlaybackReportsDoNotFlashButExplicitPauseIsImmediate() {
        val playback = MediaSessionPlaybackStability(2_500L)
        assertTrue(playback.resolve(true, 100L))
        assertTrue(playback.resolve(null, 500L))
        assertFalse(playback.resolve(null, 2_600L))
        assertTrue(playback.resolve(true, 3_000L))
        assertFalse(playback.resolve(false, 3_100L))
        assertFalse(playback.resolve(null, 3_200L))
    }
}
