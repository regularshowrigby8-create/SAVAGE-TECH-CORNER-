package dev.nova.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallTrackerTest {

    @Test
    fun `three stalls inside a minute abandon the source`() {
        val t = StallTracker()
        assertFalse(t.note(0))
        assertFalse(t.note(10_000))
        assertTrue(t.note(20_000))
    }

    @Test
    fun `stalls spread over time are tolerated`() {
        val t = StallTracker()
        assertFalse(t.note(0))
        assertFalse(t.note(40_000))
        assertFalse(t.note(110_000))
        t.reset()
        assertFalse(t.note(111_000))
    }
}
