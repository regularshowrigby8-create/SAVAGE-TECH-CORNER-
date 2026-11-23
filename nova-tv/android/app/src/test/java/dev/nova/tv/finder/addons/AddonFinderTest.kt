package dev.nova.tv.finder.addons

import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.translated.FinderHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** addons.py logic, in Kotlin: torrent filter, manifest keys, score, store, conversion. */
class AddonFinderTest {

    private class MemoryStore : PoolStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun `cap is 6000 and seeds are github npm reddit and directories`() {
        assertEquals(6000, AddonFinder.CAP)
        assertTrue(AddonFinder.SEEDS.any { "api.github.com" in it })
        assertTrue(AddonFinder.SEEDS.any { "npms.io" in it })
        assertTrue(AddonFinder.SEEDS.any { "reddit.com" in it })
        assertTrue("https://stremio-addons.net" in AddonFinder.SEEDS)
    }

    @Test
    fun `manifest paths are tried cheapest first`() {
        assertEquals("/manifest.json", AddonFinder.MANIFEST_PATHS.first())
        assertTrue("/api/manifest.json" in AddonFinder.MANIFEST_PATHS)
    }

    @Test
    fun `probe ids are the well known movie series and anime ids`() {
        assertEquals(listOf("movie" to "tt0111161", "series" to "tt0944947", "anime" to "kitsu:1"), AddonFinder.PROBE_IDS)
    }

    @Test
    fun `torrent hints match torrentio style names`() {
        assertTrue(AddonFinder.TORRENT_HINTS.containsMatchIn("Torrentio 1080p"))
        assertTrue(AddonFinder.TORRENT_HINTS.containsMatchIn("jackettio"))
        assertFalse(AddonFinder.TORRENT_HINTS.containsMatchIn("Cinemeta"))
    }

    @Test
    fun `magnet regex matches btih magnets only`() {
        assertTrue(AddonFinder.MAGNET.containsMatchIn("magnet:?xt=urn:btih:abc123"))
        assertFalse(AddonFinder.MAGNET.containsMatchIn("https://example.com/movie.mp4"))
    }

    @Test
    fun `score_of ranks catalogue quality streams and buffer`() {
        val big = AddonFinder.scoreOf(100_000, 400, 60, 90, 100)
        val small = AddonFinder.scoreOf(100, 100, 2, 90, 100)
        assertTrue(big > small)
        assertEquals(FinderHelpers.scoreOfAddon(1000, 300, 20, 80, 120), AddonFinder.scoreOf(1000, 300, 20, 80, 120), 0.001)
    }

    @Test
    fun `quality_of_addon uses addon table`() {
        assertEquals("2160p" to 400, FinderHelpers.qualityOfAddon("4k"))
        assertEquals("720p" to 200, FinderHelpers.qualityOfAddon("720"))
        assertEquals("unknown" to 0, FinderHelpers.qualityOfAddon("nothing"))
    }

    @Test
    fun `store upserts ranks and marks dead`() {
        val store = AddonFinderStore(MemoryStore())
        store.upsert(AddonRecord(id = "a", host = "a.example", score = 10.0), "alive")
        store.upsert(AddonRecord(id = "b", host = "b.example", score = 90.0), "alive")
        assertEquals(listOf("b", "a"), store.alive().map { it.id })
        assertEquals(listOf("a"), store.weak(1).map { it.id })
        store.markDead("a")
        assertEquals(listOf("b"), store.alive().map { it.id })
    }

    @Test
    fun `record converts to finder candidate with imdb prefix for movie series`() {
        val rec = AddonRecord(
            id = "x", name = "Test", host = "t.example", baseUrl = "https://t.example",
            manifestUrl = "https://t.example/manifest.json", types = listOf("movie", "series")
        )
        val c = rec.toFinderCandidate()
        assertEquals("https://t.example/manifest.json", c.manifestUrl)
        assertEquals("https://t.example", c.baseUrl)
        assertEquals(listOf("tt"), c.idPrefixes)
        assertTrue(c.acceptsImdbIds)
    }

    @Test
    fun `write_pool caps at cap and uses addons key`() {
        val store = AddonFinderStore(MemoryStore())
        repeat(4) { store.upsert(AddonRecord(id = "a$it", host = "h$it.example", score = it.toDouble()), "alive") }
        val pool = store.writePool(2)
        assertTrue(pool.contains("\"schema\":1"))
        assertTrue(pool.contains("\"cap\":2"))
        assertTrue(pool.contains("\"count\":2"))
        assertTrue(pool.contains("\"addons\""))
    }

    @Test
    fun `queue rejects blocked and known hosts`() {
        val store = AddonFinderStore(MemoryStore())
        store.upsert(AddonRecord(id = "k", host = "known.example"), "alive")
        val http = AddonFinder.http(okhttp3.OkHttpClient())
        val harvester = AddonFinderHarvester(store, http)
        harvester.loadBlocklist(setOf("blocked.example"))
        assertFalse(harvester.queue("https://blocked.example/"))
        assertFalse(harvester.queue("https://known.example/"))
        assertTrue(harvester.queue("https://new.example/"))
    }
}
