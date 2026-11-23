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
 *  4. Quality, with 720p as the standard: it buffers less than 1080p on a
 *     phone or a TV stick. 1080p is the ceiling and is still offered, ranked
 *     below the preferred quality; 2160p/4K is filtered out entirely.
 *  5. Adaptive streams (HLS/DASH) get a small bonus: they step down instead
 *     of stalling when the network dips.
 */
object StreamRanker {

    data class Context(
        val probes: Map<String, ProbeResult> = emptyMap(),
        /** DNS resolution latency per stream URL, from [NovaDns.pingMs]. */
        val pings: Map<String, Long> = emptyMap(),
        val runtimeSeconds: Int = 0,
        val preferredGroup: String? = null,
        val preferredSource: String? = null,
        val hostScore: (String?) -> Int = { 0 },
        /** Opened from Dual Audio: English-plus-original releases go first. */
        val preferDualAudio: Boolean = false,
        /** Standard play target: 720p unless changed in Settings. */
        val preferredQuality: String = DEFAULT_QUALITY
    )

    /** Streams that may be offered at all. */
    fun eligible(stream: Stream, probe: ProbeResult?): Boolean {
        if (!stream.isPlayable) return false
        if (probe != null && !probe.ok) return false
        if (probe != null && probe.totalBytes > FinderRules.MAX_FILE_BYTES) return false
        // 1080p is the ceiling, not a preference. A 4K rendition costs four
        // times the bandwidth for no visible gain on a phone or a TV stick,
        // and on a metered link it is the difference between a film and a
        // buffering ring. Unknown quality (rank 0) is still allowed through.
        if (FinderRules.qualityRank(stream.quality) > MAX_QUALITY_RANK) return false
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
        if (ctx.preferredQuality.isNotBlank() && !ctx.preferredQuality.equals("auto", ignoreCase = true)) {
            score += qualityPreference(stream.quality, ctx.preferredQuality)
        }
        val kind = (FinderRules.classifyStream(stream) as? FinderRules.Verdict.Playable)?.kind
        if (kind == FinderRules.StreamKind.HLS || kind == FinderRules.StreamKind.DASH) score += 15

        // A host that answers DNS quickly is the one whose first frame arrives
        // quickly. Measured before playback, so it ranks even unprobed sources.
        score += pingScore(ctx.pings[stream.url] ?: -1)

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

    /**
     * DNS latency weight. Small enough that a source which cannot sustain the
     * bitrate still loses, large enough to order otherwise-equal sources.
     */
    fun pingScore(dnsMs: Long): Int = when {
        dnsMs < 0 -> 0
        dnsMs <= 50 -> 45
        dnsMs <= 120 -> 30
        dnsMs <= 250 -> 12
        dnsMs <= 500 -> -10
        else -> -35
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

    /**
     * Bonus for matching the standard-play quality. Large enough that the
     * preferred rendition beats a healthier-looking 1080p/4K source, while an
     * otherwise much better source can still win.
     */
    fun qualityPreference(quality: String, preferred: String): Int {
        val want = FinderRules.qualityRank(preferred)
        if (want <= 0) return 0
        val got = FinderRules.qualityRank(quality)
        if (got <= 0) return -10
        return when {
            got == want -> 70
            got > 1440 -> -45 // 4K costs bandwidth for no visible gain on a phone
            got > want -> -20 // 1080p when 720p is the target
            else -> -35 // 480p and below
        }
    }

    const val DEFAULT_QUALITY = "720p"
    /** Absolute ceiling. 2160p/4K is never offered, whatever the setting says. */
    const val MAX_QUALITY = "1080p"
    const val MAX_QUALITY_RANK = 1080
    const val MIN_HEADROOM = 1.3
}
