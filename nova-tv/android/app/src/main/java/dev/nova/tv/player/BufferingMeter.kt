package dev.nova.tv.player

/**
 * What the buffering overlay shows: a percentage and a short reason.
 *
 * The percentage is honest about what it can know. Once playback has a
 * duration, ExoPlayer's own buffered-ahead figure is used; before that (a cold
 * start, where the container has not been parsed yet) it ramps with time, which
 * is what every other player does — an invisible spinner reads as a hang, a
 * moving number reads as progress.
 */
data class BufferingStatus(
    val percent: Int,
    val label: String = "Buffering"
)

object BufferingMeter {
    /** Buffer ExoPlayer needs before the first frame (matches the load control). */
    const val START_TARGET_MS = 1_500L

    /** How long the cold-start ramp takes to reach its ceiling. */
    const val RAMP_MS = 9_000L

    /** Never show 100%: the overlay disappears when it is true. */
    private const val CEILING = 97

    /**
     * @param bufferedAheadMs media buffered after the playhead
     * @param startTargetMs buffer needed to start
     * @param elapsedMs time since this buffering episode began
     */
    fun percent(
        bufferedAheadMs: Long,
        startTargetMs: Long = START_TARGET_MS,
        elapsedMs: Long = 0L,
        rampMs: Long = RAMP_MS
    ): Int {
        val buffered = if (startTargetMs <= 0) 0f
        else (bufferedAheadMs.toFloat() / startTargetMs).coerceIn(0f, 1f)
        val ramp = (elapsedMs.toFloat() / rampMs.coerceAtLeast(1L)).coerceIn(0f, 1f) * 0.9f
        val best = maxOf(buffered, ramp)
        return (best * 100).toInt().coerceIn(1, CEILING)
    }

    /** Keep the number moving forward inside one episode. */
    fun monotonic(previous: Int?, next: Int): Int =
        maxOf(previous ?: 0, next)
}
