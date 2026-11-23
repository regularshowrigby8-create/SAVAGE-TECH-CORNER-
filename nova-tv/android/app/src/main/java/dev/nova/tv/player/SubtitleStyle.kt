@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

/**
 * Caption styling that blends captions into the picture instead of sitting on
 * top of it in the player's factory default.
 *
 * The presets are the ones subtitle users actually ask for:
 *  - Cinema: white text in a soft translucent box, slightly above the bottom
 *    edge, so it reads over both dark and bright scenes without covering the
 *    picture the way a solid box does;
 *  - Outline: no box at all, a black outline instead — the BBC/iPlayer
 *    approach, which never hides the image;
 *  - Minimal: white with a drop shadow, for people who want captions to be
 *    barely there;
 *  - High contrast: yellow on solid black, for low-vision viewing.
 *
 * The size, box opacity and how far the captions sit from the bottom edge are
 * all adjustable, and embedded styles are ignored so the settings always win.
 */
enum class CaptionTone(val label: String) {
    CINEMA("Cinema"),
    OUTLINE("Outline"),
    MINIMAL("Minimal"),
    HIGH_CONTRAST("High contrast");

    fun next(): CaptionTone = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromString(name: String?): CaptionTone =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CINEMA
    }
}

data class SubtitleStyle(
    /** 80..150, 100 = player default. */
    val sizePercent: Int = 100,
    /** 0..100 opacity of the caption box. */
    val backgroundPercent: Int = 55,
    /** How far from the bottom edge the captions sit, in percent of height. */
    val bottomPercent: Int = 8,
    val tone: CaptionTone = CaptionTone.CINEMA
) {
    fun nextSize(): SubtitleStyle {
        val i = SIZES.indexOf(sizePercent).takeIf { it >= 0 } ?: 1
        return copy(sizePercent = SIZES[(i + 1) % SIZES.size])
    }

    fun nextBackground(): SubtitleStyle {
        val i = BACKGROUNDS.indexOf(backgroundPercent).takeIf { it >= 0 } ?: 2
        return copy(backgroundPercent = BACKGROUNDS[(i + 1) % BACKGROUNDS.size])
    }

    fun nextBottom(): SubtitleStyle {
        val i = BOTTOMS.indexOf(bottomPercent).takeIf { it >= 0 } ?: 2
        return copy(bottomPercent = BOTTOMS[(i + 1) % BOTTOMS.size])
    }

    fun nextTone(): SubtitleStyle = copy(tone = tone.next())

    val sizeLabel: String get() = "$sizePercent%"
    val backgroundLabel: String get() = if (backgroundPercent <= 0) "No box" else "$backgroundPercent%"
    val bottomLabel: String get() = "$bottomPercent% up"

    companion object {
        val SIZES = listOf(80, 100, 115, 130, 150)
        val BACKGROUNDS = listOf(0, 35, 55, 75, 100)
        val BOTTOMS = listOf(3, 5, 8, 12, 16)

        /** media3's DEFAULT_TEXT_SIZE_FRACTION: the 100% baseline. */
        const val BASE_TEXT_FRACTION = 0.0533f

        fun from(sizePercent: Int, backgroundPercent: Int, bottomPercent: Int, tone: String?): SubtitleStyle =
            SubtitleStyle(sizePercent, backgroundPercent, bottomPercent, CaptionTone.fromString(tone))
    }
}

fun SubtitleStyle.toCaptionStyle(): CaptionStyleCompat {
    val foreground = when (tone) {
        CaptionTone.HIGH_CONTRAST -> Color.YELLOW
        else -> Color.WHITE
    }
    val background = when {
        tone == CaptionTone.OUTLINE || tone == CaptionTone.MINIMAL || backgroundPercent <= 0 -> Color.TRANSPARENT
        else -> Color.argb(backgroundPercent * 255 / 100, 0, 0, 0)
    }
    val (edgeType, edgeColor) = when (tone) {
        CaptionTone.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE to Color.BLACK
        CaptionTone.MINIMAL -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW to Color.BLACK
        else -> CaptionStyleCompat.EDGE_TYPE_NONE to Color.TRANSPARENT
    }
    val typeface = if (tone == CaptionTone.HIGH_CONTRAST) Typeface.DEFAULT_BOLD else null
    return CaptionStyleCompat(foreground, background, Color.TRANSPARENT, edgeType, edgeColor, typeface)
}

/**
 * Apply to a PlayerView's subtitle view. Embedded styles are disabled on
 * purpose: a track that ships its own font and colour would otherwise ignore
 * every setting here.
 */
fun PlayerView.applySubtitleStyle(style: SubtitleStyle) {
    val view = subtitleView ?: return
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(style.toCaptionStyle())
    view.setFractionalTextSize(SubtitleStyle.BASE_TEXT_FRACTION * (style.sizePercent / 100f))
    view.setBottomPaddingFraction((style.bottomPercent / 100f).coerceIn(0f, 0.5f))
}
