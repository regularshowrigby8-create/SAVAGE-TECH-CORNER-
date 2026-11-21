package dev.nova.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRatesTest {

    @Test
    fun `the cycle starts at normal speed and returns to it`() {
        assertEquals(1.25f, nextPlaybackRate(1f), 0.001f)
        assertEquals(1f, nextPlaybackRate(0.75f), 0.001f)
    }

    @Test
    fun `an unknown rate restarts the cycle at normal speed`() {
        assertEquals(1f, nextPlaybackRate(1.1f), 0.001f)
    }

    @Test
    fun `the label drops a pointless decimal`() {
        assertEquals("1x", formatPlaybackRate(1f))
        assertEquals("1.5x", formatPlaybackRate(1.5f))
    }
}
