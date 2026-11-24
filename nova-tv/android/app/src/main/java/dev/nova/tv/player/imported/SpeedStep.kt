// Imported from MagicalExoPlayer (https://github.com/HamidrezaAmz/MagicalExoPlayer,
// commit 9c2f0b1), MIT License.
// Original paths:
//   library/src/main/java/com/potyvideo/library/globalEnums/EnumPlaybackSpeed.kt
//   library/src/main/java/com/potyvideo/library/globalEnums/EnumAspectRatio.kt
//
// Upstream models speed and aspect ratio as enums with a string key so a
// controller can be configured from XML. Nova keeps the enum-with-label shape
// and the cycle behaviour, and drops the XML lookup.
package dev.nova.tv.player.imported

import dev.nova.tv.player.engine.ResizeMode

/** Playback speeds, slowest to fastest, with the label a remote shows. */
enum class SpeedStep(val value: Float, val label: String) {
    QUARTER(0.25f, "0.25×"),
    HALF(0.5f, "0.5×"),
    THREE_QUARTER(0.75f, "0.75×"),
    NORMAL(1f, "Normal"),
    ONE_AND_QUARTER(1.25f, "1.25×"),
    ONE_AND_HALF(1.5f, "1.5×"),
    DOUBLE(2f, "2×"),
    TRIPLE(3f, "3×");

    fun next(): SpeedStep = entries[(ordinal + 1) % entries.size]

    companion object {
        fun nearest(rate: Float): SpeedStep =
            entries.minByOrNull { kotlin.math.abs(it.value - rate) } ?: NORMAL
    }
}

/**
 * Aspect handling.
 *
 * Upstream has fixed ratios (1:1, 16:9, 4:3, match); Media3's
 * `AspectRatioFrameLayout` only has fit/zoom/stretch/fill. Nova exposes the
 * Media3 set, because forcing a 4:3 box around a 16:9 stream is a black border,
 * not an aspect ratio, and the player already has the four modes that matter.
 */
object AspectSteps {
    val ORDER = listOf(ResizeMode.AUTO, ResizeMode.FIT, ResizeMode.CROP, ResizeMode.STRETCH)

    fun next(mode: ResizeMode): ResizeMode {
        val i = ORDER.indexOf(mode).takeIf { it >= 0 } ?: 0
        return ORDER[(i + 1) % ORDER.size]
    }

    fun label(mode: ResizeMode): String = when (mode) {
        ResizeMode.AUTO -> "Auto"
        ResizeMode.FIT -> "Fit"
        ResizeMode.CROP -> "Crop"
        ResizeMode.STRETCH -> "Stretch"
    }
}
