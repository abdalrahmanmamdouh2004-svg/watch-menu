package com.example

/**
 * Android's active-session list can be empty for a moment while a media app replaces its
 * MediaSession. Retain the old target briefly instead of presenting a false "no player" state.
 */
internal class MediaSessionGracePeriod(private val graceMs: Long = 8_000L) {
    private var missingSinceMs: Long? = null

    fun onSessionPresent() {
        missingSinceMs = null
    }

    fun shouldRetainCurrent(nowElapsedMs: Long): Boolean {
        val since = missingSinceMs ?: nowElapsedMs.also { missingSinceMs = it }
        return nowElapsedMs - since < graceMs
    }
}

/**
 * Null, NONE, BUFFERING and CONNECTING are transient reports for some players. Keep the last
 * stable playing state briefly; explicit PLAYING, PAUSED, STOPPED and ERROR still take effect.
 */
internal class MediaSessionPlaybackStability(private val transientGraceMs: Long = 2_500L) {
    private var lastStablePlaying: Boolean? = null
    private var lastStableAtMs: Long = Long.MIN_VALUE

    fun resolve(rawPlaying: Boolean?, nowElapsedMs: Long): Boolean {
        if (rawPlaying != null) {
            lastStablePlaying = rawPlaying
            lastStableAtMs = nowElapsedMs
            return rawPlaying
        }
        return lastStablePlaying == true &&
            lastStableAtMs != Long.MIN_VALUE &&
            nowElapsedMs - lastStableAtMs < transientGraceMs
    }

    fun reset() {
        lastStablePlaying = null
        lastStableAtMs = Long.MIN_VALUE
    }
}
