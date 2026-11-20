package dev.nova.tv.finder

import dev.nova.tv.data.model.ProxyHeaders
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.StreamHints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finder's rules are a port of ZeroPlay's core.js / sources.js. These pin
 * the behaviour that decides what gets installed and what reaches the player.
 */
class FinderRulesTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `torrent indexes and duplicate vod addons are refused`() {
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource("PenguPlay"))
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource("Torrentio"))
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource("Jackettio"))
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource("IPTV Xtream"))
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource(null, "https://jackett.example/x"))
        org.junit.Assert.assertTrue(FinderRules.isRedundantSource(null, "https://torrentio.strem.fun/x"))
        org.junit.Assert.assertFalse(FinderRules.isRedundantSource("Cinemeta"))
        val manifest = json("""{"id":"p","name":"PenguPlay","resources":["stream"],"types":["movie"]}""")
        assertNull(FinderRules.parseManifest(manifest))
    }

    @Test
    fun `publicHttpsUrl refuses http, credentials, ip literals and local names`() {
        assertEquals("https://a.example/manifest.json", FinderRules.publicHttpsUrl("https://a.example/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("http://a.example/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("https://user:pw@a.example/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("https://10.0.0.1/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("https://[::1]/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("https://box.local/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl("https://svc.internal/manifest.json"))
        assertEquals("", FinderRules.publicHttpsUrl(null))
    }

    @Test
    fun `parseManifest requires a stream resource and a video type`() {
        assertNull(FinderRules.parseManifest(json("""{"id":"a","resources":["catalog"],"types":["movie"]}""")))
        assertNull(FinderRules.parseManifest(json("""{"id":"a","resources":["stream"],"types":["music"]}""")))
        val info = FinderRules.parseManifest(
            json(
                """{"id":"x","name":"X","resources":[{"name":"stream","idPrefixes":["tt"]}],
                "types":["movie","tv"],"catalogs":[{"extra":[{"name":"search"}]}]}"""
            )
        )
        assertNotNull(info)
        assertEquals(listOf("movie", "series"), info!!.types)
        assertEquals(listOf("tt"), info.idPrefixes)
        assertTrue(info.searchable)
    }

    @Test
    fun `manifest base strips manifest json`() {
        assertEquals("https://a.example/cfg", FinderRules.baseFromManifestUrl("https://a.example/cfg/manifest.json"))
        assertEquals("", FinderRules.baseFromManifestUrl("http://a.example/manifest.json"))
    }

    @Test
    fun `extractManifestUrls keeps public manifest links only`() {
        val text = "Install https://a.example/manifest.json, or http://b.example/manifest.json " +
            "or https://c.example/page and https://d.example/x/manifest.json."
        assertEquals(
            listOf("https://a.example/manifest.json", "https://d.example/x/manifest.json"),
            FinderRules.extractManifestUrls(text)
        )
    }

    @Test
    fun `entryUrl prefers manifestUrl then transport`() {
        val entry = json("""{"transport":{"url":"https://t.example/manifest.json"},"url":"https://u.example"}""") as JsonObject
        assertEquals("https://t.example/manifest.json", FinderRules.entryUrl(entry))
    }

    @Test
    fun `catalogue id and stream segment keep colons`() {
        assertEquals("tt0903747", FinderRules.catalogueId("tt0903747", isSeries = false))
        assertEquals("tt0903747:2:5", FinderRules.catalogueId("tt0903747", isSeries = true, season = 2, episode = 5))
        assertEquals("tt0903747:1:1", FinderRules.catalogueId("tt0903747:9:9", isSeries = true))
        assertEquals("tt1:2:3", FinderRules.streamSegment("tt1:2:3"))
        assertEquals("a%20b", FinderRules.streamSegment("a b"))
    }

    @Test
    fun `classifyStream accepts what ExoPlayer can play`() {
        fun kind(url: String) = (FinderRules.classifyStream(Stream(url = url)) as? FinderRules.Verdict.Playable)?.kind
        assertEquals(FinderRules.StreamKind.HLS, kind("https://cdn.example/a/master.m3u8?t=1"))
        assertEquals(FinderRules.StreamKind.DASH, kind("https://cdn.example/a.mpd"))
        assertEquals(FinderRules.StreamKind.DIRECT, kind("https://cdn.example/film.mkv"))
        // Extensionless CDN links are the common case; the old rule dropped them.
        assertEquals(FinderRules.StreamKind.DIRECT, kind("https://cdn.example/resolve/abc123"))
    }

    @Test
    fun `classifyStream rejects what cannot be decoded here`() {
        fun rejected(stream: Stream) = FinderRules.classifyStream(stream) is FinderRules.Verdict.Rejected
        assertTrue(rejected(Stream(infoHash = "abc")))
        assertTrue(rejected(Stream(url = "magnet:?xt=urn:btih:abc")))
        assertTrue(rejected(Stream(url = "https://x.example/a.torrent")))
        assertTrue(rejected(Stream(url = "https://x.example/embed/123")))
        assertTrue(rejected(Stream(url = "ftp://x.example/a.mp4")))
        assertTrue(rejected(Stream(url = "https://x.example/a.mp4", behaviorHints = StreamHints(notWebReady = true))))
        assertTrue(rejected(Stream(externalUrl = "https://site.example/watch")))
    }

    @Test
    fun `proxy headers are kept on TV`() {
        val stream = Stream(
            url = "https://cdn.example/a.m3u8",
            behaviorHints = StreamHints(proxyHeaders = ProxyHeaders(mapOf("Referer" to "https://site.example")))
        )
        assertTrue(FinderRules.classifyStream(stream) is FinderRules.Verdict.Playable)
        assertEquals("https://site.example", stream.requestHeaders["Referer"])
    }

    @Test
    fun `guessQuality normalises labels`() {
        assertEquals("2160p", FinderRules.guessQuality("Film 4K HDR"))
        assertEquals("1080p", FinderRules.guessQuality("WEB-DL 1080p x265"))
        assertEquals("720p", FinderRules.guessQuality("720P"))
        assertEquals("", FinderRules.guessQuality("CAM"))
        assertTrue(FinderRules.qualityRank("2160p") > FinderRules.qualityRank("1080p"))
    }

    // -- identity: a source must answer for the title that was asked --------

    @Test
    fun `a stream naming a different film is rejected`() {
        val wrong = Stream(url = "https://cdn.example/a.mkv", title = "The Searchers 1956 1080p BluRay")
        assertFalse(FinderRules.streamMatchesTitle(wrong, "Dune", "2021", 0, 0))
    }

    @Test
    fun `a stream naming the requested film is kept`() {
        val right = Stream(url = "https://cdn.example/a.mkv", title = "Dune Part Two 2024 2160p WEB-DL")
        assertTrue(FinderRules.streamMatchesTitle(right, "Dune: Part Two", "2024", 0, 0))
    }

    @Test
    fun `a label with no title words is kept, because most addons print none`() {
        val plain = Stream(url = "https://cdn.example/a.m3u8", title = "1080p WEB-DL 2.1 GB")
        assertTrue(FinderRules.streamMatchesTitle(plain, "Severance", "2022", 1, 2))
    }

    @Test
    fun `the wrong episode of the right series is rejected`() {
        val other = Stream(url = "https://cdn.example/a.mkv", title = "Severance S01E05 1080p")
        assertFalse(FinderRules.streamMatchesTitle(other, "Severance", "2022", 1, 2))
        val same = Stream(url = "https://cdn.example/b.mkv", title = "Severance S01E02 1080p")
        assertTrue(FinderRules.streamMatchesTitle(same, "Severance", "2022", 1, 2))
    }

    @Test
    fun `the wrong season is rejected`() {
        val other = Stream(url = "https://cdn.example/a.mkv", title = "Severance Season 2 Complete")
        assertFalse(FinderRules.streamMatchesTitle(other, "Severance", "2022", 1, 2))
    }

    @Test
    fun `internet archive is blocked by name and by host`() {
        assertTrue(FinderRules.isRedundantSource("Internet Archive"))
        assertTrue(FinderRules.isRedundantSource("Public Domain", "https://archive.org/download/x"))
        assertTrue(FinderRules.isInternetArchive("Archive Org", "https://ia.archive.org"))
        assertFalse(FinderRules.isRedundantSource("Cinemeta"))
    }

    @Test
    fun `the built-in catalogue list has at least ten catalogues besides cinemeta`() {
        val catalogues = FinderRules.DEFAULT_SOURCES - FinderRules.SUBTITLE_ONLY_SOURCES
        assertTrue("expected >= 11 catalogue addons, got ${catalogues.size}", catalogues.size >= 11)
        assertTrue(FinderRules.DEFAULT_SOURCES.first() == "https://v3-cinemeta.strem.io")
        assertTrue(FinderRules.DEFAULT_SOURCES.any { "tmdb" in it })
    }

    @Test
    fun `the zeroplay finder no longer reads the stremio directories`() {
        assertFalse("stremio-addons" in FinderRules.SOURCE_IDS)
        assertFalse("catalog-graph" in FinderRules.SOURCE_IDS)
        assertTrue("web" in FinderRules.SOURCE_IDS)
    }
}
