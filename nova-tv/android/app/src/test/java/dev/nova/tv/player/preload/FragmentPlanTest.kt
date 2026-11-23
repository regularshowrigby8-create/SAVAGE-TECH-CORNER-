package dev.nova.tv.player.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentPlanTest {

    private val gb = 1024L * 1024 * 1024
    private val hour = 3_600_000L

    @Test
    fun `window starts at the fragment holding the playhead and covers the default ten minutes`() {
        val w = FragmentPlan.window(positionMs = 30 * 60_000L, durationMs = 2 * hour, totalBytes = 4 * gb)
        val first = FragmentPlan.byteAt(30 * 60_000L, 4 * gb, 2 * hour)
        assertTrue(w.first() <= first && first < w.first() + FragmentPlan.FRAGMENT_BYTES)
        val covered = FragmentPlan.aheadMs(w.size * FragmentPlan.FRAGMENT_BYTES, 4 * gb, 2 * hour)
        assertTrue("covers ~600 s, got $covered", covered in 600_000L..610_000L)
        assertEquals(0, w.first() % FragmentPlan.FRAGMENT_BYTES)
        // An explicit depth is still honoured.
        val five = FragmentPlan.window(30 * 60_000L, 2 * hour, 4 * gb, aheadMs = 300_000L)
        val fiveCovered = FragmentPlan.aheadMs(five.size * FragmentPlan.FRAGMENT_BYTES, 4 * gb, 2 * hour)
        assertTrue("covers ~300 s, got $fiveCovered", fiveCovered in 300_000L..310_000L)
    }

    @Test
    fun `window stops at the end of the file`() {
        val total = 10L * FragmentPlan.FRAGMENT_BYTES + 5
        val w = FragmentPlan.window(positionMs = 99_000, durationMs = 100_000, totalBytes = total)
        assertTrue(w.last() < total)
        assertEquals(5, FragmentPlan.fragmentLength(w.last(), total))
    }

    @Test
    fun `unknown size or duration means no plan`() {
        assertTrue(FragmentPlan.window(0, 0, gb).isEmpty())
        assertTrue(FragmentPlan.window(0, hour, 0).isEmpty())
    }

    @Test
    fun `modes cycle off, preload, multi`() {
        assertEquals(PreloadMode.ON, PreloadMode.OFF.next())
        assertEquals(PreloadMode.MULTI, PreloadMode.ON.next())
        assertEquals(PreloadMode.OFF, PreloadMode.MULTI.next())
        assertEquals(1..1, PreloadMode.ON.workers)
        assertEquals(FragmentPlan.MAX_WORKERS, PreloadMode.MULTI.workers.last)
    }

    @Test
    fun `governor adds connections while speed rises and stops when it does not`() {
        val g = WorkerGovernor(PreloadMode.MULTI.workers)
        val start = g.workers
        repeat(WorkerGovernor.SAMPLES_PER_TEST) { g.onRound(1_000_000.0) }
        assertEquals(start + 1, g.workers)
        repeat(WorkerGovernor.SAMPLES_PER_TEST) { g.onRound(2_000_000.0) }
        assertEquals(start + 2, g.workers)
        repeat(WorkerGovernor.SAMPLES_PER_TEST) { g.onRound(2_010_000.0) }
        assertEquals(start + 2, g.workers)
        assertFalse(g.testing)
    }

    @Test
    fun `governor never exceeds six and drops one on failure`() {
        val g = WorkerGovernor(PreloadMode.MULTI.workers)
        var speed = 1.0
        repeat(40) { speed *= 2; g.onRound(speed) }
        assertEquals(FragmentPlan.MAX_WORKERS, g.workers)
        assertEquals(FragmentPlan.MAX_WORKERS - 1, g.onFailure())
        assertTrue(g.testing)
    }

    @Test
    fun `single preload keeps one connection`() {
        val g = WorkerGovernor(PreloadMode.ON.workers)
        repeat(9) { g.onRound(1e6 * (it + 1)) }
        assertEquals(1, g.workers)
    }

    @Test
    fun `depth grows with spare bandwidth and fills the rest on a fast link`() {
        val total = 4 * gb
        val duration = 2 * hour
        val bitrate = FragmentPlan.bytesPerMs(total, duration) * 1000
        // No measurement yet: the base ten minutes.
        assertEquals(FragmentPlan.AHEAD_MS, FragmentPlan.depthMs(0, duration, total, 0))
        // A link that only just keeps up gets the base window.
        assertEquals(FragmentPlan.AHEAD_MS, FragmentPlan.depthMs(0, duration, total, bitrate.toLong()))
        // A little spare bandwidth doubles the depth.
        assertEquals(FragmentPlan.AHEAD_MS * 2, FragmentPlan.depthMs(0, duration, total, (bitrate * 1.6).toLong()))
        // Twice the bitrate gets three times the depth.
        assertEquals(FragmentPlan.AHEAD_MS * 3, FragmentPlan.depthMs(0, duration, total, (bitrate * 2.1).toLong()))
        // Three times the bitrate stores everything ahead.
        assertEquals(duration, FragmentPlan.depthMs(0, duration, total, (bitrate * 3.2).toLong()))
        // Never past the end of the film.
        assertEquals(600_000L, FragmentPlan.depthMs(duration - 600_000L, duration, total, (bitrate * 3.2).toLong()))
    }

    @Test
    fun `head window fills the opening fragments before duration is known`() {
        val total = 100L * FragmentPlan.FRAGMENT_BYTES
        val head = FragmentPlan.headStarts(0, total)
        assertEquals(FragmentPlan.HEAD_FRAGMENTS, head.size)
        assertEquals(0L, head.first())
        assertEquals(FragmentPlan.FRAGMENT_BYTES, head[1])
        // Advanced cursor skips what is already on disk.
        assertEquals(FragmentPlan.FRAGMENT_BYTES * 2, FragmentPlan.headStarts(FragmentPlan.FRAGMENT_BYTES * 2, total).first())
        // And it stops at the end of a short file.
        assertTrue(FragmentPlan.headStarts(0, FragmentPlan.FRAGMENT_BYTES + 10).size == 2)
    }

    @Test
    fun `fragments shrink after repeated range failures`() {
        assertEquals(2L * 1024 * 1024, FragmentPlan.fragmentBytesFor(0))
        assertEquals(1024L * 1024, FragmentPlan.fragmentBytesFor(1))
        assertEquals(512L * 1024, FragmentPlan.fragmentBytesFor(2))
        assertEquals(256L * 1024, FragmentPlan.fragmentBytesFor(99))
    }

    @Test
    fun `preload percent reflects the stored part of the target depth`() {
        assertEquals(50, FragmentPlan.percentAhead(300_000, 600_000))
        assertEquals(100, FragmentPlan.percentAhead(600_000, 600_000))
        assertEquals(0, FragmentPlan.percentAhead(0, 0))
        assertEquals(100, FragmentPlan.percentAhead(900_000, 600_000))
    }

    @Test
    fun `cache budget is a third of free space within limits`() {
        assertEquals(256L * 1024 * 1024, PreloadCache.budgetFor(100L * 1024 * 1024))
        assertEquals(1L * gb, PreloadCache.budgetFor(3 * gb))
        assertEquals(2L * gb, PreloadCache.budgetFor(60 * gb))
    }
}
