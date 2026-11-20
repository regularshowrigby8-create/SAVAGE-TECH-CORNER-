// Imported from JustPlayer (https://github.com/moneytoo/Player, commit 7a1c9c4),
// Unlicense. Original path: app/src/main/java/com/brouken/player/PlayerActivity.java
// (the `updateSubtitleStyle` / `setSubtitleTextSize` / track-selection block)
// plus androidx/media3 `SubtitleView` cue handling.
//
// The re-timing idea is the one every desktop player uses for "subtitle delay":
// keep the cue intervals the renderer emits and display them against a shifted
// clock instead of the player's clock. JustPlayer does this by re-preparing the
// media item; Nova does it without a re-prepare, so the picture never blinks.
package dev.nova.tv.player.imported

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup

/**
 * Subtitle delay, in milliseconds. Positive = captions appear later.
 *
 * The renderer emits a cue group when captions become visible and an empty
 * group when they stop, so the interval between two emissions is exactly how
 * long a caption was on screen. Storing those intervals lets the delay be
 * changed while playing, in both directions, without touching the media item.
 */
class SubtitleOffsetController(private val player: Player) {

    private data class Span(val fromMs: Long, val toMs: Long, val cues: List<Cue>)

    private val spans = ArrayList<Span>(32)
    private var openAtMs = -1L
    private var openCues: List<Cue> = emptyList()

    /** Positive shows captions later. */
    var offsetMs: Long = 0L
        set(value) {
            val clamped = value.coerceIn(MIN_MS, MAX_MS)
            if (clamped == field) return
            field = clamped
            // A negative delay needs captions that have not been emitted yet, so
            // nudge the player back by the delta: the text renderer re-emits
            // every cue from there and the buffer fills again.
            if (clamped < 0) {
                val target = (player.currentPosition + clamped).coerceAtLeast(0L)
                player.seekTo(target)
            }
            listener?.invoke(clamped)
        }

    /** Called when the offset changes, so the UI can redraw its label. */
    var listener: ((Long) -> Unit)? = null

    fun onCues(cueGroup: CueGroup) {
        val now = cueGroup.presentationTimeUs.takeIf { it != C.TIME_UNSET }?.div(1_000) ?: player.currentPosition
        closeSpan(now)
        if (cueGroup.cues.isNotEmpty()) {
            openAtMs = now
            openCues = cueGroup.cues
        }
    }

    private fun closeSpan(atMs: Long) {
        if (openAtMs >= 0 && openCues.isNotEmpty()) {
            spans += Span(openAtMs, atMs, openCues)
            if (spans.size > MAX_SPANS) spans.removeAt(0)
        }
        openAtMs = -1
        openCues = emptyList()
    }

    /** The cues that should be on screen at the player's current position. */
    fun cuesAt(positionMs: Long): List<Cue> {
        val shifted = positionMs - offsetMs
        if (openAtMs >= 0 && openCues.isNotEmpty() && shifted >= openAtMs) return openCues
        for (i in spans.indices.reversed()) {
            val span = spans[i]
            if (shifted >= span.fromMs && shifted < span.toMs) return span.cues
        }
        return emptyList()
    }

    fun reset() {
        spans.clear()
        openAtMs = -1
        openCues = emptyList()
    }

    fun label(): String = when {
        offsetMs == 0L -> "Off"
        offsetMs > 0 -> "+${"%.1f".format(offsetMs / 1000f)}s"
        else -> "${"%.1f".format(offsetMs / 1000f)}s"
    }

    fun step(deltaMs: Long) {
        offsetMs += deltaMs
    }

    companion object {
        const val STEP_MS = 250L
        const val MIN_MS = -30_000L
        const val MAX_MS = 30_000L
        private const val MAX_SPANS = 64
    }
}

/**
 * Convenience: keep one controller per player and feed it every cue group.
 * Returns the listener so the caller can remove it on release.
 */
fun Player.attachSubtitleOffset(controller: SubtitleOffsetController): Player.Listener {
    val listener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) = controller.onCues(cueGroup)
    }
    addListener(listener)
    return listener
}
