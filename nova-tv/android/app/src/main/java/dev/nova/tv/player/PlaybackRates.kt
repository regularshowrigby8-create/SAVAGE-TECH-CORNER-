package dev.nova.tv.player

/**
 * Speeds the player cycles through, normal first so "Speed" means 1x until the
 * viewer changes it.
 */
val PlaybackRates = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** The speed after [current]; an unknown rate restarts the cycle. */
fun nextPlaybackRate(current: Float): Float {
    val index = PlaybackRates.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return PlaybackRates[(if (index < 0) 0 else index + 1) % PlaybackRates.size]
}

/** "1x", "1.25x" — no pointless decimal on a whole number. */
fun formatPlaybackRate(rate: Float): String =
    if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x"
