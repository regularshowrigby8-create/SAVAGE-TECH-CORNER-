package dev.nova.tv.finder.servers

import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.translated.FinderHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** servers.py logic, in Kotlin: extractor, probe kind, catalogue, score, store. */
class ServerFinderTest {

    private class MemoryStore : PoolStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun `cap is 6000 and seeds are the 9anime mirrors`() {
        assertEquals(6000, ServerFinder.CAP)
        assertEquals(6000, FinderHelpers.CAP)
        assertTrue("https://hianime.to" in ServerFinder.SEEDS)
        assertTrue("https://9anime.pl" in ServerFinder.SEEDS)
        assertEquals(12, ServerFinder.SEEDS.size)
    }

    @Test
    fun `provider_of maps host fragments and falls back to registrable label`() {
        assertEquals("vidstream", FinderHelpers.providerOf("https://vidstream.example/embed/1"))
        assertEquals("doodstream", FinderHelpers.providerOf("https://dood.example/v/1"))
        assertEquals("example", FinderHelpers.providerOf("https://cdn.example.com/x"))
    }

    @Test
    fun `quality_of returns strongest token first`() {
        assertEquals("2160p" to 400, FinderHelpers.qualityOf("watch 4k uhd"))
        assertEquals("1080p" to 300, FinderHelpers.qualityOf("1080p WEB-DL"))
        assertEquals("unknown" to 0, FinderHelpers.qualityOf("no quality here"))
    }

    @Test
    fun `score_of ranks catalogue first then quality then buffer minus latency`() {
        val big = ServerFinder.scoreOf(100_000, 400, 90, 100)
        val small = ServerFinder.scoreOf(100, 100, 90, 100)
        assertTrue(big > small)
        // Exact Python formula: 0.55*cat*100 + 0.30*min(tier,400)/4 + 0.15*buffer - ttfb/120
        val expected = FinderHelpers.scoreOfServer(1000, 300, 80, 120)
        assertEquals(expected, ServerFinder.scoreOf(1000, 300, 80, 120), 0.001)
    }

    @Test
    fun `extractor pulls iframe js and hls urls and skips data urls`() {
        val html = """
            <iframe src="https://vidstream.example/embed/1"></iframe>
            <script>file: "https://vidcloud.example/e/2.mp4"</script>
            <script>var s = "https://cdn.example.com/movie.m3u8"</script>
            <iframe src="data:text/html,hi"></iframe>
        """.trimIndent()
        val out = ServerExtractor().extract(html, "https://hianime.to/watch/1")
        val urls = out.keys
        assertTrue(urls.any { "vidstream.example" in it })
        assertTrue(urls.any { "vidcloud.example" in it })
        assertTrue(urls.any { it.endsWith(".m3u8") })
        assertTrue(urls.none { it.startsWith("data:") })
        assertEquals("vidstream", out.values.first { "vidstream" in it.url }.provider)
    }

    @Test
    fun `probe kind detects hls dash mp4 page and none`() {
        val prober = ServerProber(
            dev.nova.tv.finder.translated.FinderHttp(
                okhttp3.OkHttpClient(), 1, null, 3, 0.0, emptyList(), false,
                dev.nova.tv.finder.translated.FinderFingerprint()
            )
        )
        assertEquals("hls", prober.kind("application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:10,".toByteArray()))
        assertEquals("dash", prober.kind("application/dash+xml", "<MPD></MPD>".toByteArray()))
        assertEquals("mp4", prober.kind("video/mp4", ByteArray(64).apply { write(byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70)) }))
        assertEquals("page", prober.kind("text/html", "<html></html>".toByteArray()))
        assertEquals("none", prober.kind("application/octet-stream", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `probe alive requires 200 buffer over 15 and not poisoned`() {
        assertTrue(ServerProbe("https://x.example/a.m3u8", status = 200, kind = "hls", bufferScore = 50).alive)
        assertFalse(ServerProbe("https://x.example/a.m3u8", status = 200, kind = "hls", bufferScore = 10).alive)
        assertFalse(ServerProbe("https://x.example/a.m3u8", status = 404, kind = "hls", bufferScore = 90).alive)
        assertFalse(ServerProbe("https://x.example/a.m3u8", status = 200, kind = "page", bufferScore = 90).alive)
        assertFalse(ServerProbe("https://x.example/a.m3u8", status = 200, kind = "hls", bufferScore = 90, poisoned = true).alive)
    }

    @Test
    fun `store upserts ranks weak first and marks dead`() {
        val store = ServerFinderStore(MemoryStore())
        store.upsert(ServerRecord(id = "a", host = "a.example", score = 10.0), "alive")
        store.upsert(ServerRecord(id = "b", host = "b.example", score = 90.0), "alive")
        store.upsert(ServerRecord(id = "c", host = "c.example", score = 50.0), "alive")
        assertEquals(listOf("b", "c", "a"), store.alive().map { it.id })
        assertEquals(listOf("a"), store.weak(1).map { it.id })
        assertTrue(store.knownHost("a.example"))
        store.markDead("a")
        assertEquals(listOf("b", "c"), store.alive().map { it.id })
        assertEquals(3, store.count())
    }

    @Test
    fun `store frontier push pop is fifo and deduped`() {
        val store = ServerFinderStore(MemoryStore())
        assertTrue(store.push("https://hianime.to"))
        assertFalse(store.push("https://hianime.to"))
        assertTrue(store.push("https://hianime.bz"))
        assertEquals(listOf("https://hianime.to", "https://hianime.bz"), store.pop(2))
        assertEquals(0, store.frontierSize())
    }

    @Test
    fun `write_pool caps at cap and uses servers key`() {
        val store = ServerFinderStore(MemoryStore())
        repeat(5) { store.upsert(ServerRecord(id = "s$it", host = "h$it.example", score = it.toDouble()), "alive") }
        val pool = store.writePool(3)
        assertTrue(pool.contains("\"schema\":1"))
        assertTrue(pool.contains("\"cap\":3"))
        assertTrue(pool.contains("\"count\":3"))
        assertTrue(pool.contains("\"servers\""))
    }

    @Test
    fun `mirrors expands www m and api`() {
        val m = FinderHelpers.mirrors("https://hianime.to")
        assertTrue("https://hianime.to/" in m)
        assertTrue("https://www.hianime.to/" in m)
        assertTrue("https://m.hianime.to/" in m)
        assertTrue("https://api.hianime.to/" in m)
    }

    // -- website-pool projection -------------------------------------------

    @Test
    fun `a record projects to the site it was embedded on, not the media host`() {
        // `base_url` is the media origin and is what the Python pool stored.
        // WebsitePool needs a *searchable site* instead, so the referer's origin
        // -- the page the player was embedded in -- is the address to return.
        val rec = finderRecord(
            baseUrl = "https://vidcloud.example",
            referer = "https://watchfree.example/movie/42"
        )
        assertEquals("https://watchfree.example", rec.toSiteUrl())
    }

    @Test
    fun `a record with no referer falls back to its media origin`() {
        val rec = finderRecord(baseUrl = "https://vidcloud.example", referer = "")
        assertEquals("https://vidcloud.example", rec.toSiteUrl())
    }

    // -- the frontier must drain, not spin ----------------------------------
    // `push()` only rejects a URL currently *in* the frontier and `pop()`
    // removes it, so `refillSeeds()` re-queues what it just consumed. With a
    // barren surface the harvest used to re-fetch the same seeds until its
    // budget expired, which is what kept the pool near empty on device.
    @Test
    fun `a consumed url can be pushed again -- the store does not remember it`() {
        val store = ServerFinderStore(MemoryStore())
        assertTrue(store.push("https://a.example"))
        assertEquals(listOf("https://a.example"), store.pop(10))
        // This is the trap: after pop the store has forgotten all about it.
        assertTrue("pop must clear the frontier entry", store.push("https://a.example"))
    }

    @Test
    fun `visited filter drops a url the frontier offers twice`() {
        val store = ServerFinderStore(MemoryStore())
        store.push("https://a.example")
        store.push("https://b.example")
        val visited = HashSet<String>()
        val first = store.pop(64).filter { visited.add(it) }
        assertEquals(2, first.size)
        // refillSeeds() would re-queue both; the filter is what stops the spin.
        store.push("https://a.example")
        store.push("https://b.example")
        val second = store.pop(32).filter { visited.add(it) }
        assertEquals("a re-queued seed must not be fetched twice", 0, second.size)
    }

    @Test
    fun `a refilling frontier terminates and never repeats a url`() {
        // The real loop: pop, and when the frontier drains, refill from the
        // seeds. Mirrors mean a refill legitimately yields *new* variants (bare,
        // www., m., api.), so "empty immediately" is not the invariant. The
        // invariant is that the visited set makes the sequence finite and that
        // no URL is ever handed out twice.
        val store = ServerFinderStore(MemoryStore())
        val harvester = ServerFinderHarvester(store, ServerSeeds.http(okhttp3.OkHttpClient()))
        val visited = HashSet<String>()
        val seenOrder = mutableListOf<String>()
        var rounds = 0
        while (rounds < 50) {
            rounds += 1
            var batch = store.pop(64).filter { visited.add(it) }
            if (batch.isEmpty()) {
                harvester.refillSeeds()
                batch = store.pop(32).filter { visited.add(it) }
                if (batch.isEmpty()) break
            }
            seenOrder += batch
        }
        assertTrue("loop must stop instead of spinning", rounds < 50)
        assertEquals("no url may be fetched twice", seenOrder.size, seenOrder.toSet().size)
    }

    private fun finderRecord(baseUrl: String, referer: String) = ServerRecord(
        id = "x",
        name = "p",
        provider = "p",
        host = FinderHelpers.hostOf(baseUrl),
        baseUrl = baseUrl,
        kind = "hls",
        sampleUrl = baseUrl + "/m.m3u8",
        referer = referer,
        quality = "1080p",
        qualityTier = 300,
        catalogue = 100,
        ttfbMs = 100,
        kbps = 900,
        stalls = 0,
        bufferScore = 90,
        signature = "none",
        score = 10.0,
        firstSeen = "",
        lastChecked = "",
        failures = 0
    )
}
