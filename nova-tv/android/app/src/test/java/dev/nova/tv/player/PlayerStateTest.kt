package dev.nova.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chrome-visibility rules and the seek maths.
 *
 * These are the invariants copied from video.js `computeVisible()` and the reason
 * a viewer cannot end up with a paused film and no controls. They are pure, so
 * they run as plain JVM tests with no emulator.
 */
class PlayerStateTest {

    @Test
    fun `paused media always shows the chrome`() {
        val state = PlayerUiState(playing = false, userActive = false)
        assertTrue("a paused film must never hide its own controls", state.chromeVisible)
    }

    @Test
    fun `playing and idle hides the chrome`() {
        val state = PlayerUiState(playing = true, userActive = false)
        assertFalse(state.chromeVisible)
    }

    @Test
    fun `playing and active shows the chrome`() {
        assertTrue(PlayerUiState(playing = true, userActive = true).chromeVisible)
    }

    @Test
    fun `any hold outranks idleness`() {
        val state = PlayerUiState(playing = true, userActive = false, holds = setOf(HoldReason.MENU))
        assertTrue(state.chromeVisible)
    }

    @Test
    fun `an error keeps the chrome so the viewer can act`() {
        assertTrue(PlayerUiState(playing = true, userActive = false, error = "boom").chromeVisible)
    }

    @Test
    fun `ended keeps the chrome for the next-episode prompt`() {
        assertTrue(PlayerUiState(playing = false, ended = true, userActive = false).chromeVisible)
    }

    @Test
    fun `buffering while idle hides, buffering while active shows`() {
        assertFalse(PlayerUiState(playing = true, buffering = true, userActive = false).chromeVisible)
        assertTrue(PlayerUiState(playing = true, buffering = true, userActive = true).chromeVisible)
    }

    @Test
    fun `progress is clamped and safe with no duration`() {
        assertEquals(0f, PlayerUiState(positionMs = 5_000, durationMs = 0).progress, 0.001f)
        assertEquals(0.5f, PlayerUiState(positionMs = 50, durationMs = 100).progress, 0.001f)
        assertEquals(1f, PlayerUiState(positionMs = 200, durationMs = 100).progress, 0.001f)
    }

    @Test
    fun `seek forward is exact in the middle`() {
        val result = seekBy(positionMs = 10_000, deltaMs = 10_000, durationMs = 60_000)
        assertEquals(SeekResult.SeekedTo(20_000), result)
    }

    @Test
    fun `seek before zero clamps to the start`() {
        val result = seekBy(positionMs = 2_000, deltaMs = -10_000, durationMs = 60_000)
        assertEquals(SeekResult.Clamped(0, "start"), result)
    }

    @Test
    fun `seek past the end stops short of the end, not on it`() {
        // Landing on duration is STATE_ENDED: the app would roll to the next
        // episode or restart this one. The guard keeps it a seek.
        val result = seekBy(positionMs = 58_000, deltaMs = 10_000, durationMs = 60_000)
        assertEquals(SeekResult.Clamped(58_500, "end"), result)
    }

    @Test
    fun `seek with unknown duration still moves relative to the current position`() {
        // Duration is unset for the first moments after prepare, and for some
        // HLS sources. Ignoring the seek there is why ±10 s did nothing.
        val result = seekBy(positionMs = 1_000, deltaMs = 10_000, durationMs = 0)
        assertEquals(SeekResult.SeekedTo(11_000), result)
    }

    @Test
    fun `seek back with unknown duration never goes negative`() {
        val result = seekBy(positionMs = 1_000, deltaMs = -10_000, durationMs = -1)
        assertEquals(SeekResult.Clamped(0, "start"), result)
    }
}
