package dev.nova.tv.player

/**
 * Chrome visibility as a predicate over state, not a timer that mutates a flag.
 *
 * The rule is lifted from video.js v10 `controls.ts` `computeVisible()`: controls
 * are shown when something holds them, or the media is not playing, or the user
 * is active. A paused film must never hide its own controls, or the viewer is
 * stranded with no way to resume.
 */
enum class HoldReason { LOCK, PAUSED, INTERACTING, MENU, ERROR, ENDED, CASTING, BUFFERING }

data class PlayerUiState(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val menuOpen: Boolean = false,
    val holds: Set<HoldReason> = emptySet(),
    val userActive: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val playbackRate: Float = 1f
) {
    /** video.js: any hold, or paused, or ended, or errored, or menu open wins. */
    val chromeVisible: Boolean
        get() = when {
            holds.isNotEmpty() -> true
            ended -> true
            error != null -> true
            menuOpen -> true
            !playing -> true
            buffering -> userActive
            else -> userActive
        }

    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Seek response shapes, matching what the browser player already uses. */
sealed interface SeekResult {
    data class SeekedTo(val targetMs: Long) : SeekResult
    data class Clamped(val targetMs: Long, val bound: String) : SeekResult
    data class Ignored(val reason: String) : SeekResult
}

object PlayerTimings {
    /** plyr uses 2000 ms on pointer devices and 3000 ms on touch. */
    const val IDLE_MS = 2_000L
    const val IDLE_TOUCH_MS = 3_000L
    /** video.js TAP_THRESHOLD_DELAY. */
    const val TAP_THRESHOLD_MS = 250L
    /** video.js TOUCH_SETTLE_DELAY: ignore synthetic mouse events this soon. */
    const val TOUCH_SETTLE_MS = 500L
    /** video.js CAPTURE_RELEASE_DELAY. */
    const val CAPTURE_RELEASE_MS = 100L
    const val DOUBLE_TAP_MS = 280L
    const val LONG_PRESS_MS = 1_500L
    const val SEEK_SETTLE_MS = 400L
}

object SkipSeconds {
    const val TAP = 10_000L
    const val KEY = 10_000L
    const val HOLD = 30_000L
}

fun seekBy(positionMs: Long, deltaMs: Long, durationMs: Long): SeekResult {
    if (durationMs <= 0L) return SeekResult.Ignored("duration unknown")
    val target = positionMs + deltaMs
    return when {
        target < 0L -> SeekResult.Clamped(0L, "start")
        target > durationMs -> SeekResult.Clamped(durationMs, "end")
        else -> SeekResult.SeekedTo(target)
    }
}
