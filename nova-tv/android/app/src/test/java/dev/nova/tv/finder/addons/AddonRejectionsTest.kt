package dev.nova.tv.finder.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rejection tally that explains a low addon yield.
 *
 * The finder passed roughly one candidate in a hundred, and nothing told the
 * viewer why: `AddonVerdict.reason` was computed and discarded, so the screen
 * could only say "nothing new". A tally is only useful if it aggregates by
 * *cause* and survives the "(12/40)" formatting the torrent gate uses.
 */
class AddonRejectionsTest {

    @Test
    fun `an empty tally reports nothing`() {
        val r = AddonRejections()
        assertEquals(0, r.total)
        assertEquals("", r.summary)
        assertEquals(0, r.of("torrent"))
    }

    @Test
    fun `torrent failures aggregate by cause, not by count`() {
        // `verify` reports "torrent(12/40)" per candidate; the tally must group
        // those under one cause or the summary is unreadable.
        val counts = mapOf("torrent" to 12, "no-manifest" to 30, "no-streams" to 3)
        val r = AddonRejections(counts)
        assertEquals(45, r.total)
        assertEquals(12, r.of("torrent"))
    }

    @Test
    fun `the summary leads with the most common cause`() {
        val r = AddonRejections(mapOf("torrent" to 2, "no-manifest" to 30, "no-streams" to 3))
        assertEquals("no-manifest=30, no-streams=3, torrent=2", r.summary)
    }

    @Test
    fun `a summary is short enough to show as a toast`() {
        val r = AddonRejections(mapOf("no-manifest" to 30, "torrent" to 12, "no-streams" to 3))
        assertTrue("toast text should stay readable", r.summary.length < 120)
    }
}
