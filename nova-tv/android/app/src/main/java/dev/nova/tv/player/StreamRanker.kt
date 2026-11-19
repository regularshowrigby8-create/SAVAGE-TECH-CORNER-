package dev.nova.tv.player

import dev.nova.tv.data.model.Stream
import dev.nova.tv.finder.DualAudio
import dev.nova.tv.finder.FinderRules

/**
 * Orders sources for anti-buffering, not for the biggest number.
 *
 * Pure, so every rule is a unit test (StreamRankerTest). The inputs are what
 * the app knows before pressing play: the stream itself, the probe, the host's
 * record this session, and the source that played the last episode.
 *
 * Rules, in order of weight:
 *  1. Dead, torrent, page, and anything over 5 GB never appear.
 *  2. Throughput headroom: measured bytes/s against the file's own bitrate
 *     (size x 8 / runtime). Under 1.3x will stall; it goes to the bottom.
 *  3. The source family that worked last time (bingeGroup, then addon) --
 *     WuPlay and ARVIO both continue a series on the same source.
 *  4. Quality, with 1080p preferred over 2160p: a 4K file under the 5 GB cap
 *     is either short or heavily compressed, and 1080p buffers less.
 *  5. Adaptive streams (HLS/DASH) get a small bonus: they step down instead
 *     of stalling when the network dips.
 */
object StreamRanker {

    data class Context(
        val probes: Map<String, ProbeResult> = emptyMap(),
        val runtimeSeconds: Int = 0,
        val preferredGroup: String? = null,
        val preferredSource: String? = null,
        val hostScore: (String?) -> Int = { 0 },
        /** Opened from Dual Audio: English-plus-original releases go first. */
        val preferDualAudio: Boolean = false
    )

    /** Streams that may be offered at all. */
    fun eligible(stream: Stream, probe: ProbeResult?): Boolean {
        if (!stream.isPlayable) return false
        if (probe != null && !probe.ok) return false
        if (probe != null && probe.totalBytes > FinderRules.MAX_FILE_BYTES) return false
        return true
    }

    fun rank(streams: List<Stream>, ctx: Context): List<Stream> =
        streams
            .distinctBy { it.url.orEmpty().trim() to it.requestHeaders }
            .filter { eligible(it, ctx.probes[it.url]) }
            .map { it to score(it, ctx) }
            .sortedByDescending { it.second }
            .map { it.first }

    fun score(stream: Stream, ctx: Context): Int {
        val probe = ctx.probes[stream.url]
        var score = qualityScore(stream.quality)
        val kind = (FinderRules.classifyStream(stream) as? FinderRules.Verdict.Playable)?.kind
        if (kind == FinderRules.StreamKind.HLS || kind == FinderRules.StreamKind.DASH) score += 15

        val size = maxOf(stream.sizeBytes, probe?.totalBytes ?: 0)
        val headroom = headroom(size, ctx.runtimeSeconds, probe?.bytesPerSecond ?: -1)
        score += when {
            headroom == null -> 0
            headroom >= 3.0 -> 60
            headroom >= 1.8 -> 35
            headroom >= MIN_HEADROOM -> 10
            else -> -120
        }
        if (probe != null && probe.ok) {
            score += 25
            if (probe.ttfbMs >= 0) score -= (probe.ttfbMs / 100).toInt().coerceAtMost(40)
        }
        if (size in 1..FinderRules.MAX_FILE_BYTES) score += 5

        val group = stream.behaviorHints?.bingeGroup
        if (ctx.preferredGroup != null && group == ctx.preferredGroup) score += 60
        else if (ctx.preferredSource != null && stream.sourceBase == ctx.preferredSource) score += 40

        if (ctx.preferDualAudio && DualAudio.hasEnglishPlusOther(stream)) score += 90

        score += ctx.hostScore(stream.url)
        if (stream.requestHeaders.isEmpty()) score += 3
        return score
    }

    /** throughput / bitrate, or null when either side is unknown. */
    fun headroom(sizeBytes: Long, runtimeSeconds: Int, bytesPerSecond: Long): Double? {
        if (sizeBytes <= 0 || runtimeSeconds <= 0 || bytesPerSecond <= 0) return null
        val bitrateBytes = sizeBytes.toDouble() / runtimeSeconds
        return bytesPerSecond / bitrateBytes
    }

    /** bits/s estimate to seed ExoPlayer's bandwidth meter, or 0 for "let it measure". */
    fun bandwidthEstimate(probe: ProbeResult?): Long =
        probe?.bytesPerSecond?.takeIf { it > 0 }?.times(8)?.coerceIn(500_000, 200_000_000) ?: 0

    fun qualityScore(quality: String): Int = when (FinderRules.qualityRank(quality)) {
        in 1080..1440 -> 100
        in 2000..4400 -> 75
        in 700..1079 -> 80
        in 1..699 -> 45
        else -> 60
    }

    const val MIN_HEADROOM = 1.3
}
