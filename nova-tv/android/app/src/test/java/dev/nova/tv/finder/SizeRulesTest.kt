package dev.nova.tv.finder

import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.StreamHints
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No torrents, nothing over 5 GB. */
class SizeRulesTest {

    @Test
    fun `sizes are parsed from the text addons print`() {
        assertEquals((2.5 * 1024 * 1024 * 1024).toLong(), FinderRules.parseSizeBytes("Movie 1080p 💾 2.5 GB"))
        assertEquals(800L * 1024 * 1024, FinderRules.parseSizeBytes("800 MB"))
        assertEquals((5.71 * 1024 * 1024 * 1024).toLong(), FinderRules.parseSizeBytes("5,71 GiB"))
        assertEquals(0L, FinderRules.parseSizeBytes("no size here"))
        assertEquals(0L, FinderRules.parseSizeBytes(null))
    }

    @Test
    fun `files over 5 GB are rejected, smaller ones pass`() {
        val big = Stream(url = "https://cdn.example/a.mkv", title = "Film 2160p 18.2 GB")
        val small = Stream(url = "https://cdn.example/b.mp4", title = "Film 1080p 2.1 GB")
        val hinted = Stream(url = "https://cdn.example/c.mp4", behaviorHints = StreamHints(videoSize = 6L * 1024 * 1024 * 1024))
        assertEquals(FinderRules.Verdict.Rejected("oversize"), FinderRules.classifyStream(big))
        assertTrue(FinderRules.classifyStream(small) is FinderRules.Verdict.Playable)
        assertEquals(FinderRules.Verdict.Rejected("oversize"), FinderRules.classifyStream(hinted))
    }

    @Test
    fun `torrents never play`() {
        val torrent = Stream(infoHash = "abcdef0123456789abcdef0123456789abcdef01", title = "Film 1.2 GB")
        assertTrue(FinderRules.classifyStream(torrent) !is FinderRules.Verdict.Playable)
        val magnet = Stream(url = "magnet:?xt=urn:btih:abc", title = "Film")
        assertTrue(FinderRules.classifyStream(magnet) !is FinderRules.Verdict.Playable)
    }

    @Test
    fun `p2p manifests are not candidates`() {
        val p2p = Json.parseToJsonElement(
            """{"id":"t","name":"T","resources":["stream"],"types":["movie"],"behaviorHints":{"p2p":true}}"""
        )
        assertNull(FinderRules.parseManifest(p2p))
    }

    @Test
    fun `sizes format for the source list`() {
        assertEquals("2.1 GB", FinderRules.formatSize(2_254_857_830))
        assertEquals("700 MB", FinderRules.formatSize(700L * 1024 * 1024))
        assertEquals("", FinderRules.formatSize(0))
    }
}
