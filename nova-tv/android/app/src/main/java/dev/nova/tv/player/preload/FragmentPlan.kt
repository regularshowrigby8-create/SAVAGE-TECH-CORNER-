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
 *
 * Nova adds three things on top, all borrowed from desktop players:
 *  - mpv's `--cache-secs` idea of a *depth* that grows with spare bandwidth:
 *    [depthMs] keeps 300 s ahead on a link that only just keeps up, 30 min when
 *    it has 2x the bitrate, and the whole remaining film when it has 3x;
 *  - a "head" window ([headStarts]) so the opening fragments download before
 *    the container has told us the duration;
 *  - fragment shrinking ([fragmentBytesFor]) for CDNs that choke on 2 MiB
 *    ranges: the same fragment is retried in smaller pieces instead of failing.
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

    /** Base lookahead: ten minutes, from mpv's default network cache. */
    const val AHEAD_MS = 600_000L

    const val MAX_WORKERS = 6

    /** Fragments fetched before the duration is known (~8 MiB). */
    const val HEAD_FRAGMENTS = 4

    /** Below this much free lookahead the depth is not worth extending. */
    private const val FILL_RATIO = 3.0

    /** Fragment sizes to fall back on when a host keeps dropping ranges. */
    private val FRAGMENT_SIZES = longArrayOf(
        2L * 1024 * 1024,
        1024L * 1024,
        512L * 1024,
        256L * 1024
    )

    /** Bytes per millisecond of video, from the file size and duration. */
    fun bytesPerMs(totalBytes: Long, durationMs: Long): Double =
        if (totalBytes <= 0 || durationMs <= 0) 0.0 else totalBytes.toDouble() / durationMs

    /** Byte offset of a playback position. */
    fun byteAt(positionMs: Long, totalBytes: Long, durationMs: Long): Long =
        (positionMs.coerceAtLeast(0) * bytesPerMs(totalBytes, durationMs)).toLong().coerceIn(0, maxOf(0, totalBytes - 1))

    /** The fragment boundary at or before [byte]. */
    fun fragmentStart(byte: Long, fragmentBytes: Long = FRAGMENT_BYTES): Long =
        if (fragmentBytes <= 0) 0 else byte.coerceAtLeast(0) / fragmentBytes * fragmentBytes

    /**
     * How far ahead to keep downloaded, given what the link is actually doing.
     * A 1x link only gets the base window; spare bandwidth buys a deeper one
     * (mpv's cache-secs, scaled by headroom), and a link that is three times
     * faster than the film simply stores everything that is left.
     */
    fun depthMs(
        positionMs: Long,
        durationMs: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        baseMs: Long = AHEAD_MS
    ): Long {
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        if (remaining <= 0) return 0
        val rate = bytesPerMs(totalBytes, durationMs)
        if (bytesPerSecond <= 0 || rate <= 0) return minOf(baseMs, remaining)
        val ratio = bytesPerSecond / (rate * 1000.0)
        val scaled = when {
            ratio >= FILL_RATIO -> remaining
            ratio >= 2.0 -> baseMs * 3
            ratio >= 1.5 -> baseMs * 2
            else -> baseMs
        }
        return scaled.coerceIn(0, remaining)
    }

    /**
     * Fragment start offsets from the playhead to [aheadMs] ahead, in play
     * order. The fragment containing the playhead comes first: that is the one
     * a stall would be waiting on.
     */
    fun window(
        positionMs: Long,
        durationMs: Long,
        totalBytes: Long,
        aheadMs: Long = AHEAD_MS,
        fragmentBytes: Long = FRAGMENT_BYTES,
        /** Cap on offsets returned; the preloader re-plans from the playhead anyway. */
        limit: Int = Int.MAX_VALUE
    ): List<Long> {
        if (totalBytes <= 0 || durationMs <= 0 || fragmentBytes <= 0 || limit <= 0) return emptyList()
        val from = fragmentStart(byteAt(positionMs, totalBytes, durationMs), fragmentBytes)
        val until = minOf(totalBytes, byteAt(positionMs + aheadMs, totalBytes, durationMs) + 1)
        if (from >= until) return emptyList()
        return generateSequence(from) { it + fragmentBytes }.takeWhile { it < until }.take(limit).toList()
    }

    /**
     * The opening fragments, for the window before the duration is known.
     * Playback always starts at the beginning, so byte order is play order.
     */
    fun headStarts(
        fromByte: Long,
        totalBytes: Long,
        count: Int = HEAD_FRAGMENTS,
        fragmentBytes: Long = FRAGMENT_BYTES
    ): List<Long> {
        if (totalBytes <= 0 || count <= 0 || fragmentBytes <= 0) return emptyList()
        val from = fragmentStart(fromByte, fragmentBytes).coerceAtMost(maxOf(0, totalBytes - 1))
        return generateSequence(from) { it + fragmentBytes }
            .take(count)
            .takeWhile { it < totalBytes }
            .toList()
    }

    fun fragmentLength(start: Long, totalBytes: Long, fragmentBytes: Long = FRAGMENT_BYTES): Long =
        minOf(fragmentBytes, totalBytes - start).coerceAtLeast(0)

    /** How much playback time [bytesAhead] contiguous bytes cover. */
    fun aheadMs(bytesAhead: Long, totalBytes: Long, durationMs: Long): Long {
        val rate = bytesPerMs(totalBytes, durationMs)
        return if (rate <= 0) 0 else (bytesAhead / rate).toLong()
    }

    /** How much of the target depth is already on disk, 0..100. */
    fun percentAhead(aheadMs: Long, depthMs: Long): Int =
        if (depthMs <= 0) 0 else ((aheadMs.toDouble() / depthMs) * 100).toInt().coerceIn(0, 100)

    /** Fragment size to use after [failures] consecutive range failures. */
    fun fragmentBytesFor(failures: Int): Long =
        FRAGMENT_SIZES[failures.coerceIn(0, FRAGMENT_SIZES.lastIndex)]
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
