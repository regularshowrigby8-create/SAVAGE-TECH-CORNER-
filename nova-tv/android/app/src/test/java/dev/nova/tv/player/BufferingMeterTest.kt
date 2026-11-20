package dev.nova.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferingMeterTest {

    @Test
    fun `a full start buffer reads as (almost) complete`() {
        val percent = BufferingMeter.percent(
            bufferedAheadMs = BufferingMeter.START_TARGET_MS,
            elapsedMs = 0
        )
        assertEquals(97, percent)
    }

    @Test
    fun `half a start buffer reads as half`() {
        val percent = BufferingMeter.percent(
            bufferedAheadMs = BufferingMeter.START_TARGET_MS / 2,
            elapsedMs = 0
        )
        assertTrue("got $percent", percent in 45..52)
    }

    @Test
    fun `a cold start still moves while nothing is buffered`() {
        val early = BufferingMeter.percent(bufferedAheadMs = 0, elapsedMs = 1_000L)
        val later = BufferingMeter.percent(bufferedAheadMs = 0, elapsedMs = 8_000L)
        assertTrue("early $early", early in 5..15)
        assertTrue("later $later", later > early)
        assertTrue("never reaches 100 while visible", later <= 97)
    }

    @Test
    fun `the number never goes backwards inside one episode`() {
        assertEquals(40, BufferingMeter.monotonic(40, 12))
        assertEquals(41, BufferingMeter.monotonic(40, 41))
        assertEquals(12, BufferingMeter.monotonic(null, 12))
    }
}
