package dev.nova.tv.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the `harvest#3945996 SKIP (already running)` flood.
 *
 * The pool-fill loop used to pause only when the pool grew, and to exit only
 * when the finder reported `more = false`. A skip reported `more = true` (the
 * pool was under cap) and added nothing, so it matched neither branch and the
 * loop spun with no delay at all. Measured in a JVM reproduction of the old
 * shape: **11.3 million iterations in 300 ms**, each one bumping the round
 * counter -- which is exactly why the device trace showed six-digit round
 * numbers all stamped with the same second.
 *
 * The invariant these tests enforce: every step that does not finish the loop
 * must return a positive pause.
 */
class HarvestPacingTest {

    @Test
    fun `a skip is classified as a skip and must pause`() {
        val step = HarvestPacing.step(busy = true, harvestNull = false, more = true, before = 0, after = 0)
        assertEquals(HarvestStep.SKIP, step)
        assertTrue("a skip must not spin", HarvestPacing.pauseMs(step) > 0)
    }

    @Test
    fun `a skip wins over every other signal`() {
        // Even if the pool looks like it grew, a busy result crawled nothing.
        assertEquals(
            HarvestStep.SKIP,
            HarvestPacing.step(busy = true, harvestNull = false, more = true, before = 0, after = 99)
        )
    }

    @Test
    fun `a barren round that still reports more must pause`() {
        // This is the second no-delay path: nothing added, but `more = true`.
        val step = HarvestPacing.step(busy = false, harvestNull = false, more = true, before = 5, after = 5)
        assertEquals(HarvestStep.BARREN, step)
        assertTrue("a barren round must not spin", HarvestPacing.pauseMs(step) > 0)
    }

    @Test
    fun `a thrown or timed out harvest retries with growing backoff`() {
        val step = HarvestPacing.step(busy = false, harvestNull = true, more = false, before = 0, after = 0)
        assertEquals(HarvestStep.RETRY, step)
        val first = HarvestPacing.pauseMs(step, failures = 1)
        val third = HarvestPacing.pauseMs(step, failures = 3)
        assertTrue(first > 0)
        assertTrue("backoff must grow", third > first)
    }

    @Test
    fun `a growing pool is progress and still pauses`() {
        val step = HarvestPacing.step(busy = false, harvestNull = false, more = true, before = 5, after = 9)
        assertEquals(HarvestStep.PROGRESS, step)
        assertTrue(HarvestPacing.pauseMs(step) > 0)
    }

    @Test
    fun `the loop only finishes when nothing was added and the finder has no more`() {
        val step = HarvestPacing.step(busy = false, harvestNull = false, more = false, before = 5, after = 5)
        assertEquals(HarvestStep.DONE, step)
        assertEquals(0L, HarvestPacing.pauseMs(step))
    }

    @Test
    fun `no non terminal step ever returns a zero pause`() {
        for (step in HarvestStep.entries) {
            if (step == HarvestStep.DONE) continue
            for (failures in 0..6) {
                assertTrue(
                    "$step with failures=$failures must pause, or the loop spins",
                    HarvestPacing.pauseMs(step, failures) > 0
                )
            }
        }
    }
}
