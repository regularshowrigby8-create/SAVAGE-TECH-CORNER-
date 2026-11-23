package dev.nova.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun `size, box and height all cycle and wrap`() {
        val style = SubtitleStyle()
        assertEquals(115, style.nextSize().sizePercent)
        assertEquals(75, style.nextBackground().backgroundPercent)
        assertEquals(12, style.nextBottom().bottomPercent)

        val big = SubtitleStyle(sizePercent = SubtitleStyle.SIZES.last())
        assertEquals(SubtitleStyle.SIZES.first(), big.nextSize().sizePercent)
        val seeThrough = SubtitleStyle(backgroundPercent = SubtitleStyle.BACKGROUNDS.last())
        assertEquals(SubtitleStyle.BACKGROUNDS.first(), seeThrough.nextBackground().backgroundPercent)
    }

    @Test
    fun `tones cycle through every preset`() {
        var tone = CaptionTone.CINEMA
        val seen = mutableSetOf(tone)
        repeat(CaptionTone.entries.size - 1) {
            tone = tone.next()
            seen += tone
        }
        assertEquals(CaptionTone.entries.toSet(), seen)
        assertEquals(CaptionTone.CINEMA, tone.next())
    }

    @Test
    fun `labels say what the setting is`() {
        val style = SubtitleStyle(sizePercent = 130, backgroundPercent = 0, bottomPercent = 12)
        assertEquals("130%", style.sizeLabel)
        assertEquals("No box", style.backgroundLabel)
        assertEquals("12% up", style.bottomLabel)
    }

    @Test
    fun `unknown saved tone falls back to cinema`() {
        assertEquals(CaptionTone.CINEMA, CaptionTone.fromString("nonsense"))
        assertTrue(SubtitleStyle.from(100, 55, 8, "OUTLINE").tone == CaptionTone.OUTLINE)
        assertEquals(SubtitleStyle(), SubtitleStyle.from(100, 55, 8, null))
    }
}
