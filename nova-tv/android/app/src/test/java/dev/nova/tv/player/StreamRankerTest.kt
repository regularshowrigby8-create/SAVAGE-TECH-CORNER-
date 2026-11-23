package dev.nova.tv.player

import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.StreamHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anti-buffering: rank by whether the host can keep up, not just by resolution. */
class StreamRankerTest {

    private fun s(url: String, title: String, group: String? = null) =
        Stream(url = url, title = title, behaviorHints = group?.let { StreamHints(bingeGroup = it) })

    @Test
    fun `a host that cannot keep up drops below a slower-looking one that can`() {
        val fast = s("https://a.example/720.mp4", "720p 1.0 GB")
        val slow = s("https://b.example/1080.mp4", "1080p 4.0 GB")
        val probes = mapOf(
            fast.url!! to ProbeResult(ok = true, ttfbMs = 200, bytesPerSecond = 2_000_000),
            // 4 GB over 2 h needs ~555 KB/s; this host gives 400 KB/s.
            slow.url!! to ProbeResult(ok = true, ttfbMs = 200, bytesPerSecond = 400_000)
        )
        val ranked = StreamRanker.rank(listOf(slow, fast), StreamRanker.Context(probes = probes, runtimeSeconds = 7200))
        assertEquals(fast.url, ranked.first().url)
    }

    @Test
    fun `failed probes and oversize files are removed`() {
        val dead = s("https://a.example/x.mp4", "1080p")
        val huge = s("https://b.example/y.mp4", "1080p")
        val ok = s("https://c.example/z.mp4", "1080p")
        val probes = mapOf(
            dead.url!! to ProbeResult(ok = false, status = 404),
            huge.url!! to ProbeResult(ok = true, totalBytes = 7L * 1024 * 1024 * 1024)
        )
        val ranked = StreamRanker.rank(listOf(dead, huge, ok), StreamRanker.Context(probes = probes))
        assertEquals(listOf(ok.url), ranked.map { it.url })
    }

    @Test
    fun `the last source's binge group is preferred for the next episode`() {
        val a = s("https://a.example/1.mp4", "1080p", group = "addon-a|1080p")
        val b = s("https://b.example/1.mp4", "1080p", group = "addon-b|1080p")
        val ranked = StreamRanker.rank(listOf(a, b), StreamRanker.Context(preferredGroup = "addon-b|1080p"))
        assertEquals(b.url, ranked.first().url)
    }

    @Test
    fun `a faster DNS answer ranks an otherwise equal source first`() {
        val slow = s("https://slow.example/1080.mp4", "1080p")
        val fast = s("https://fast.example/1080.mp4", "1080p")
        val ranked = StreamRanker.rank(
            listOf(slow, fast),
            StreamRanker.Context(pings = mapOf(slow.url!! to 420L, fast.url!! to 25L))
        )
        assertEquals(fast.url, ranked.first().url)
        assertEquals(45, StreamRanker.pingScore(25))
        assertEquals(-35, StreamRanker.pingScore(900))
        // Unknown latency neither helps nor hurts.
        assertEquals(0, StreamRanker.pingScore(-1))
    }

    @Test
    fun `headroom and bandwidth seed`() {
        assertNull(StreamRanker.headroom(0, 100, 100))
        assertEquals(2.0, StreamRanker.headroom(1_000, 10, 200)!!, 0.001)
        assertEquals(16_000_000L, StreamRanker.bandwidthEstimate(ProbeResult(ok = true, bytesPerSecond = 2_000_000)))
        assertEquals(0L, StreamRanker.bandwidthEstimate(null))
    }

    @Test
    fun `standard play prefers 720p over 1080p`() {
        val hd = s("https://a.example/1080.mp4", "1080p")
        val standard = s("https://b.example/720.mp4", "720p")
        val ranked = StreamRanker.rank(listOf(hd, standard), StreamRanker.Context())
        assertEquals(standard.url, ranked.first().url)
        assertEquals(70, StreamRanker.qualityPreference("720p", "720p"))
        // An explicit 1080p setting flips the order back.
        val hdPreferred = StreamRanker.rank(
            listOf(hd, standard),
            StreamRanker.Context(preferredQuality = "1080p")
        )
        assertEquals(hd.url, hdPreferred.first().url)
    }

    @Test
    fun `1080p is the sweet spot`() {
        assertTrue(StreamRanker.qualityScore("1080p") > StreamRanker.qualityScore("2160p"))
        assertTrue(StreamRanker.qualityScore("2160p") > StreamRanker.qualityScore("480p"))
    }

    @Test
    fun `4K is never offered even when 1080p is requested`() {
        val uhd = s("https://a.example/4k.mp4", "2160p")
        val hd = s("https://b.example/1080.mp4", "1080p")
        val ranked = StreamRanker.rank(
            listOf(uhd, hd),
            StreamRanker.Context(preferredQuality = "1080p")
        )
        assertEquals(listOf(hd.url), ranked.map { it.url })
    }

    @Test
    fun `content range gives the full size`() {
        assertEquals(123_456_789L, StreamProbe.totalFrom("bytes 0-262143/123456789", 262_144))
        assertEquals(500L, StreamProbe.totalFrom(null, 500))
        assertEquals(0L, StreamProbe.totalFrom("bytes 0-1/*", -1))
    }
}
