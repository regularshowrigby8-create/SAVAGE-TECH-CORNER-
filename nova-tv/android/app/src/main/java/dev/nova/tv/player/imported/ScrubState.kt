// Imported from JustPlayer (https://github.com/moneytoo/Player, commit 7a1c9c4),
// Unlicense. Original path: app/src/main/java/com/brouken/player/CustomDefaultTimeBar.java
// and androidx/media3/ui/DefaultTimeBar's scrubbing contract.
//
// The upstream class exists because DefaultTimeBar only starts a scrub when the
// finger lands on the thumb. Nova has the same problem on a D-pad: LEFT/RIGHT
// must move a cursor along the bar and OK must commit, and none of that exists
// in DefaultTimeBar. This is the model behind that, kept separate from the
// drawing so it can be unit-tested.
package dev.nova.tv.player.imported

/**
 * Seek-bar interaction state.
 *
 * Two modes, because a remote and a finger are not the same input:
 *
 *  - [begin] / [nudge] / [commit] is the D-pad path: a cursor moves in steps,
 *    the picture follows it live (so scrubbing feels like scrubbing), and
 *    releasing commits.
 *  - [seekFraction] is the touch path: the finger's x position maps straight
 *    onto a position.
 */
class ScrubState(
    private val durationMs: () -> Long,
    private val positionMs: () -> Long
) {
    var active: Boolean = false
        private set

    /** Where the cursor sits while scrubbing. */
    var cursorMs: Long = 0L
        private set

    /** True once the cursor has moved off the live position. */
    var dirty: Boolean = false
        private set

    fun begin() {
        if (active) return
        active = true
        cursorMs = positionMs().coerceAtLeast(0L)
        dirty = false
    }

    /**
     * Move the cursor. [stepMs] is the granularity: a tap is coarse, a held
     * key is fine, which is how every TV player behaves.
     */
    fun nudge(stepMs: Long): Long {
        if (!active) begin()
        val duration = durationMs()
        val limit = if (duration > 0) (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L) else Long.MAX_VALUE
        cursorMs = (cursorMs + stepMs).coerceIn(0L, limit)
        dirty = true
        return cursorMs
    }

    fun seekFraction(fraction: Float): Long {
        val duration = durationMs()
        if (duration <= 0) return 0L
        val limit = (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)
        cursorMs = (duration * fraction.coerceIn(0f, 1f)).toLong().coerceIn(0L, limit)
        active = true
        dirty = true
        return cursorMs
    }

    /** Finish scrubbing and return where to seek, or null if nothing moved. */
    fun commit(): Long? {
        if (!active) return null
        active = false
        val target = cursorMs
        val moved = dirty
        dirty = false
        return target.takeIf { moved }
    }

    fun cancel() {
        active = false
        dirty = false
        cursorMs = positionMs().coerceAtLeast(0L)
    }

    /** 0..1 for drawing the cursor. */
    fun fraction(): Float {
        val duration = durationMs()
        return if (duration <= 0) 0f else (cursorMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    companion object {
        /** Same guard the player uses: landing on the end restarts the episode. */
        const val SEEK_END_GUARD_MS = 1_500L
    }
}
