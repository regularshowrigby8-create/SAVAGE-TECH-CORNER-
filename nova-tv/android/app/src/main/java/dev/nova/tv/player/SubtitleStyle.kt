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
 * Two things make a caption look like a broadcast/TV caption rather than a
 * phone notification pinned to the screen, and both are handled here:
 *
 *  1. **it is not full-width.** A caption line on a TV is limited to roughly
 *     80% of the picture and centred, so the eye never has to travel to the
 *     screen edge (and on a 16:9 TV, an edge-to-edge line is ~90 characters,
 *     which is far past the ~42 the BBC/subtitling guidelines cap at);
 *  2. **it sits in the safe area.** TVs overscan. A caption 3% off the bottom
 *     edge is cropped by real hardware, which is why the presets lift it.
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
 * Every preset draws in the bold sans-serif from [TvCaption.TYPEFACE], which
 * is what makes the text hold up at sofa distance. [TvCaption] documents the
 * safe-area numbers that were applied. Size, box opacity and how far the
 * captions sit from the bottom edge are all adjustable, and embedded styles
 * are ignored so the settings always win.
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

/**
 * The numbers that make a caption look like television.
 *
 * Kept as constants rather than buried in [toCaptionStyle] so the unit test can
 * assert them and so a change is a visible decision:
 *  - [MAX_LINE_FRACTION] 0.80: the caption view is inset, capping a line at
 *    80% of picture width. On a 1920-wide TV at 100% text size that is ~57
 *    characters, inside the 42+-per-line range the guidelines allow for two
 *    lines. Before this the view was edge-to-edge.
 *  - [SAFE_BOTTOM_PERCENT] 5: the lowest the caption may sit. Below ~5% of
 *    picture height, TV overscan clips it.
 *  - [DEFAULT_BOTTOM_PERCENT] 9: the default lift; comfortably above overscan
 *    and above the seek bar, and roughly where broadcast captions sit.
 */
object TvCaption {
    const val MAX_LINE_FRACTION = 0.80f
    const val SAFE_BOTTOM_PERCENT = 5
    const val DEFAULT_BOTTOM_PERCENT = 9

    /** Horizontal inset, as a fraction of width, that caps the line length. */
    val SIDE_INSET_FRACTION: Float get() = (1f - MAX_LINE_FRACTION) / 2f

    /** Caption weight: TV captions are bolder than the phone default. */
    val TYPEFACE: Typeface get() = Typeface.create("sans-serif", Typeface.BOLD)
}

data class SubtitleStyle(
    /** 80..150, 100 = player default. */
    val sizePercent: Int = 100,
    /** 0..100 opacity of the caption box. */
    val backgroundPercent: Int = 55,
    /** How far from the bottom edge the captions sit, in percent of height. */
    val bottomPercent: Int = TvCaption.DEFAULT_BOTTOM_PERCENT,
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

    /**
     * The lift actually applied. Anything below the TV safe area is raised to
     * it rather than obeyed, so an old saved style (8% was the previous
     * default) or a hand-edited preference cannot put captions under a TV's
     * overscan.
     */
    val effectiveBottomPercent: Int get() = bottomPercent.coerceAtLeast(TvCaption.SAFE_BOTTOM_PERCENT)

    companion object {
        val SIZES = listOf(80, 100, 115, 130, 150)
        val BACKGROUNDS = listOf(0, 35, 55, 75, 100)

        /** Lifts offered. The first two are below safe area and are clamped up. */
        val BOTTOMS = listOf(3, 5, 9, 12, 16)

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
        // Cinema used to be EDGE_TYPE_NONE and relied on the box alone, which
        // disappears over a bright sky. A thin outline underneath the box is
        // what makes broadcast captions legible in every scene.
        CaptionTone.CINEMA -> CaptionStyleCompat.EDGE_TYPE_OUTLINE to Color.BLACK
        CaptionTone.HIGH_CONTRAST -> CaptionStyleCompat.EDGE_TYPE_NONE to Color.TRANSPARENT
    }
    val typeface = if (tone == CaptionTone.HIGH_CONTRAST) Typeface.DEFAULT_BOLD else TvCaption.TYPEFACE
    return CaptionStyleCompat(foreground, background, Color.TRANSPARENT, edgeType, edgeColor, typeface)
}

/**
 * Apply to a PlayerView's subtitle view. Embedded styles are disabled on
 * purpose: a track that ships its own font and colour would otherwise ignore
 * every setting here.
 *
 * The horizontal inset and the bottom-padding clamp are the TV-look changes:
 * without them the caption view spans the full width and can sit in overscan,
 * which is what makes a caption read as a phone overlay rather than as
 * subtitling.
 */
fun PlayerView.applySubtitleStyle(style: SubtitleStyle) {
    val view = subtitleView ?: return
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setStyle(style.toCaptionStyle())
    view.setFractionalTextSize(SubtitleStyle.BASE_TEXT_FRACTION * (style.sizePercent / 100f))
    view.setBottomPaddingFraction((style.effectiveBottomPercent / 100f).coerceIn(0f, 0.5f))
    // Cap the line at 80% of the picture so it reads like a broadcast caption
    // instead of a full-width ribbon. The inset is taken from the display
    // metrics, not from `view.width`: this runs from a LaunchedEffect before
    // the first measure, when `width` is still 0 and the padding would be
    // silently dropped.
    val screenWidth = view.resources.displayMetrics.widthPixels
    val inset = (screenWidth * TvCaption.SIDE_INSET_FRACTION).toInt()
    view.setPaddingRelative(inset, view.paddingTop, inset, view.paddingBottom)
}

/**
 * The d-pad/remote step for caption size, in the order a viewer expects:
 * smaller first, then larger. The player used to cycle one direction only, so
 * overshooting a size meant cycling through all five to get back.
 */
fun SubtitleStyle.stepSize(up: Boolean): SubtitleStyle {
    val i = SubtitleStyle.SIZES.indexOf(sizePercent).takeIf { it >= 0 } ?: 1
    val next = if (up) i + 1 else i - 1
    return copy(sizePercent = SubtitleStyle.SIZES[next.coerceIn(0, SubtitleStyle.SIZES.lastIndex)])
}
