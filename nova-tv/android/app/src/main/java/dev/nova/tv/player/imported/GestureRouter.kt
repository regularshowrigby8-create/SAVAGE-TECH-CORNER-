// Imported from DKVideoPlayer (https://github.com/Doikki/DKVideoPlayer,
// commit 4d1f3a2), Apache License 2.0.
// Original paths:
//   dkplayer-ui/src/main/java/xyz/doikki/videocontroller/component/GestureView.java
//   dkplayer-java/src/main/java/xyz/doikki/videoplayer/controller/GestureVideoController.java
//
// Upstream's GestureVideoController maps a vertical drag on the left half to
// brightness, the right half to volume, and a horizontal drag to a seek. Nova
// keeps the mapping and the thresholds, expressed as pure functions so the
// touch layer only has to feed it deltas.
package dev.nova.tv.player.imported

enum class GestureZone { NONE, BRIGHTNESS, VOLUME, SEEK }

/**
 * Turns a drag into an intent.
 *
 * The decision is made once, on the first movement past [SLOP_PX], and then
 * locked for the rest of the gesture. Upstream does the same: without the lock,
 * a slightly diagonal drag flickers between volume and seek and neither works.
 */
class GestureRouter(
    private val widthPx: Int,
    private val heightPx: Int
) {
    private var decided: GestureZone = GestureZone.NONE
    private var startX = 0f
    private var startY = 0f

    var zone: GestureZone = GestureZone.NONE
        private set

    /** Total movement since [down], in the zone's own axis. */
    var travel: Float = 0f
        private set

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        decided = GestureZone.NONE
        zone = GestureZone.NONE
        travel = 0f
    }

    fun move(x: Float, y: Float): GestureZone {
        val dx = x - startX
        val dy = y - startY
        if (decided == GestureZone.NONE) {
            if (kotlin.math.abs(dx) < SLOP_PX && kotlin.math.abs(dy) < SLOP_PX) return GestureZone.NONE
            decided = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                GestureZone.SEEK
            } else if (startX < widthPx / 2f) {
                GestureZone.BRIGHTNESS
            } else {
                GestureZone.VOLUME
            }
            zone = decided
        }
        travel = when (decided) {
            GestureZone.SEEK -> dx
            else -> -dy // up is more
        }
        return decided
    }

    fun up(): GestureZone {
        val result = decided
        decided = GestureZone.NONE
        return result
    }

    /**
     * How far a full-height drag moves volume/brightness. Upstream uses 1.0 per
     * screen height, which is too fast on a phone in landscape; a full height
     * for the full range is the same thing, so this stays as it is.
     */
    fun fraction(): Float = if (heightPx <= 0) 0f else (travel / heightPx).coerceIn(-1f, 1f)

    /** Seek delta for a horizontal drag: a full width is [SEEK_FULL_WIDTH_MS]. */
    fun seekDeltaMs(): Long = (travel / widthPx.coerceAtLeast(1) * SEEK_FULL_WIDTH_MS).toLong()

    companion object {
        const val SLOP_PX = 24f
        const val SEEK_FULL_WIDTH_MS = 180_000L
    }
}
