package dev.nova.tv.player.preload

/**
 * The preloading rules, as pure functions so they are unit-tested.
 *
 * The mechanics follow FastStream (https://github.com/Andrews54757/FastStream),
 * a browser extension that stops buffering by pre-downloading the video in the
 * background. FastStream's code is "all rights reserved", so none of it is
 * copied; this is an independent Kotlin implementation of the same ideas:
 *
 *  - split the file into fixed-size byte-range fragments;
 *  - keep a window of fragments downloaded ahead of the playhead (FastStream
 *    defaults to 300 s ahead), never the whole file unless asked;
 *  - in parallel mode, start with few connections and add one at a time while
 *    the measured aggregate speed keeps rising, up to a cap (FastStream: 6),
 *    and stop adding as soon as a new connection does not help.
 */
enum class PreloadMode(val label: String) {
    OFF("Preload off"),
    ON("Preload on"),
    MULTI("Multi preload");

    /** The player button cycles Off → Preload → Multi-thread → Off. */
    fun next(): PreloadMode = entries[(ordinal + 1) % entries.size]

    val workers: IntRange
        get() = when (this) {
            OFF -> 0..0
            ON -> 1..1
            MULTI -> 2..FragmentPlan.MAX_WORKERS
        }
}

object FragmentPlan {
    const val FRAGMENT_BYTES = 2L * 1024 * 1024
    const val AHEAD_MS = 300_000L
    const val MAX_WORKERS = 6

    /** Bytes per millisecond of video, from the file size and duration. */
    fun bytesPerMs(totalBytes: Long, durationMs: Long): Double =
        if (totalBytes <= 0 || durationMs <= 0) 0.0 else totalBytes.toDouble() / durationMs

    /** Byte offset of a playback position. */
    fun byteAt(positionMs: Long, totalBytes: Long, durationMs: Long): Long =
        (positionMs.coerceAtLeast(0) * bytesPerMs(totalBytes, durationMs)).toLong().coerceIn(0, maxOf(0, totalBytes - 1))

    /**
     * Fragment start offsets from the playhead to [aheadMs] ahead, in play
     * order. The fragment containing the playhead comes first: that is the one
     * a stall would be waiting on.
     */
    fun window(positionMs: Long, durationMs: Long, totalBytes: Long, aheadMs: Long = AHEAD_MS, fragmentBytes: Long = FRAGMENT_BYTES): List<Long> {
        if (totalBytes <= 0 || durationMs <= 0 || fragmentBytes <= 0) return emptyList()
        val from = byteAt(positionMs, totalBytes, durationMs) / fragmentBytes * fragmentBytes
        val until = minOf(totalBytes, byteAt(positionMs + aheadMs, totalBytes, durationMs) + 1)
        if (from >= until) return emptyList()
        return generateSequence(from) { it + fragmentBytes }.takeWhile { it < until }.toList()
    }

    fun fragmentLength(start: Long, totalBytes: Long, fragmentBytes: Long = FRAGMENT_BYTES): Long =
        minOf(fragmentBytes, totalBytes - start).coerceAtLeast(0)

    /** How much playback time [bytesAhead] contiguous bytes cover. */
    fun aheadMs(bytesAhead: Long, totalBytes: Long, durationMs: Long): Long {
        val rate = bytesPerMs(totalBytes, durationMs)
        return if (rate <= 0) 0 else (bytesAhead / rate).toLong()
    }
}

/**
 * FastStream-style connection governor. Every completed round (each worker
 * finished one fragment) gives one aggregate speed sample; after
 * [SAMPLES_PER_TEST] samples the average is compared with the last test. Faster
 * means one more connection helps, so add one; not faster means the link (or
 * the host) is saturated, so stop testing and keep the count.
 */
class WorkerGovernor(private val range: IntRange) {
    var workers: Int = range.first.coerceAtLeast(if (range.last > 0) 1 else 0)
        private set
    var testing: Boolean = range.last > range.first
        private set
    private var lastAverage = 0.0
    private val samples = ArrayList<Double>()

    /** Record one round's aggregate bytes/second; returns the new worker count. */
    fun onRound(bytesPerSecond: Double): Int {
        if (!testing || bytesPerSecond <= 0) return workers
        samples += bytesPerSecond
        if (samples.size < SAMPLES_PER_TEST) return workers
        val average = samples.average()
        samples.clear()
        if (average > lastAverage * (1 + MIN_GAIN) && workers < range.last) {
            lastAverage = average
            workers++
        } else {
            testing = false
        }
        return workers
    }

    /** A connection failed: drop one and test again (FastStream removes the failed downloader). */
    fun onFailure(): Int {
        if (workers > maxOf(1, range.first)) workers--
        testing = range.last > range.first
        samples.clear()
        lastAverage = 0.0
        return workers
    }

    companion object {
        const val SAMPLES_PER_TEST = 3
        /** Ignore noise: a new connection must add at least 5%. */
        const val MIN_GAIN = 0.05
    }
}
