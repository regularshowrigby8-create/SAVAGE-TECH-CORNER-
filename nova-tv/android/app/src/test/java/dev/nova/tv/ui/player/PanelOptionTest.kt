package dev.nova.tv.ui.player

import dev.nova.tv.data.model.Stream
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.player.imported.SubtitleOffsetController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelOptionTest {
    private fun session(index: Int = 0, tried: Set<Int> = setOf(index)) = NovaPlayer.Session(
        sources = listOf(
            Stream(name = "Film 1080p", url = "https://one.example/movie", sourceName = "One"),
            Stream(name = "Film 720p", url = "https://two.example/movie", sourceName = "Two")
        ),
        index = index,
        tried = tried
    )

    @Test
    fun `sources mark playing and failed rows and select current source`() {
        val picked = mutableListOf<Int>()
        val rows = sourcePanelOptions(session(index = 1, tried = setOf(0, 1))) { picked += it }

        assertEquals("Failed", rows[0].detail)
        assertFalse(rows[0].selected)
        assertEquals("Playing", rows[1].detail)
        assertTrue(rows[1].selected)
        rows[0].onPick()
        assertEquals(listOf(0), picked)
    }

    @Test
    fun `quality rows select the current rung and cap state`() {
        val picked = mutableListOf<Pair<Int, Int>>()
        val rows = qualityPanelOptions(session(), qualityCapHeight = 1080) { index, cap -> picked += index to cap }

        assertFalse(rows.first { it.label == "Auto" }.selected)
        assertTrue(rows.first { it.label == "1080p" }.selected)
        assertFalse(rows.first { it.label == "720p" }.selected)
        rows.first { it.label == "720p" }.onPick()
        assertEquals(listOf(1 to 720), picked)
    }

    @Test
    fun `subtitle delay adds reset only for a non-zero offset`() {
        val steps = mutableListOf<Long>()
        var reset = false
        assertEquals(listOf("Delay later", "Delay earlier"), subtitleDelayOptions(0, steps::add) { reset = true }.map { it.label })

        val rows = subtitleDelayOptions(SubtitleOffsetController.STEP_MS, steps::add) { reset = true }
        assertEquals(listOf("Delay later", "Delay earlier", "Reset delay"), rows.map { it.label })
        assertEquals("+0.3s", rows.last().detail)
        rows[0].onPick()
        rows.last().onPick()
        assertEquals(listOf(SubtitleOffsetController.STEP_MS), steps)
        assertTrue(reset)
    }
}
