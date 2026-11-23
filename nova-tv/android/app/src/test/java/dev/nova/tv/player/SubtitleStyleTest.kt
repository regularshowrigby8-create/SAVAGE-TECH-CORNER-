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
        assertEquals(SubtitleStyle().tone, SubtitleStyle.from(100, 55, 8, null).tone)
    }

    @Test
    fun `default lift is above the tv safe area`() {
        val style = SubtitleStyle()
        assertEquals(TvCaption.DEFAULT_BOTTOM_PERCENT, style.bottomPercent)
        assertTrue(style.bottomPercent > TvCaption.SAFE_BOTTOM_PERCENT)
        assertEquals(style.bottomPercent, style.effectiveBottomPercent)
    }

    @Test
    fun `a lift inside overscan is raised to the safe area`() {
        // 3%, and the 8% the app shipped before, are both below the safe line
        // and are clamped: a real TV crops what is down there.
        assertEquals(TvCaption.SAFE_BOTTOM_PERCENT, SubtitleStyle(bottomPercent = 3).effectiveBottomPercent)
        assertEquals(TvCaption.SAFE_BOTTOM_PERCENT, SubtitleStyle(bottomPercent = 8).effectiveBottomPercent)
        assertEquals(12, SubtitleStyle(bottomPercent = 12).effectiveBottomPercent)
    }

    @Test
    fun `line length is capped like a broadcast caption`() {
        assertEquals(0.80f, TvCaption.MAX_LINE_FRACTION)
        // A 1920-wide picture keeps 80%, so 192 px is inset each side.
        assertEquals(192f, 1920f * TvCaption.SIDE_INSET_FRACTION, 0.01f)
    }

    @Test
    fun `size steps down as well as up and stops at the ends`() {
        val base = SubtitleStyle(sizePercent = 100)
        assertEquals(115, base.stepSize(up = true).sizePercent)
        assertEquals(80, base.stepSize(up = false).sizePercent)
        assertEquals(
            SubtitleStyle.SIZES.first(),
            SubtitleStyle(sizePercent = SubtitleStyle.SIZES.first()).stepSize(up = false).sizePercent
        )
        assertEquals(
            SubtitleStyle.SIZES.last(),
            SubtitleStyle(sizePercent = SubtitleStyle.SIZES.last()).stepSize(up = true).sizePercent
        )
    }

    @Test
    fun `cinema keeps an outline so it survives a bright scene`() {
        // A box alone disappears over sky and snow; the outline is the fix.
        val style = SubtitleStyle(tone = CaptionTone.CINEMA).toCaptionStyle()
        assertEquals(androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
    }

    @Test
    fun `high contrast is yellow on solid black`() {
        val style = SubtitleStyle(backgroundPercent = 100, tone = CaptionTone.HIGH_CONTRAST).toCaptionStyle()
        assertEquals(android.graphics.Color.YELLOW, style.foregroundColor)
        assertEquals(android.graphics.Color.BLACK, style.backgroundColor)
    }
}
