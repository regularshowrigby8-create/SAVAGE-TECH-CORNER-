package dev.nova.tv.finder.website

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The site pool is the second half of the finder: sites the crawler verified,
 * imported and deduped, and never counted against the 3000 addon manifests.
 *
 * Every test here is a JVM test. The crawler is Python and is tested separately;
 * what is tested here is the part that decides what the *app* will do with its
 * output, including the cases that would otherwise produce a dead entry.
 */
class SitePoolTest {

    private class MemoryStore(var text: String? = null) : SitePoolStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private class Recorder : SiteSink {
        val added = mutableListOf<SavedWebsite>()
        override fun add(site: SavedWebsite) { added += site }
    }

    private fun pool(store: MemoryStore = MemoryStore(), sink: SiteSink = Recorder()) =
        SitePool(store, sink)

    /** The exact shape tools/site_crawler.py writes. */
    private fun document(vararg entries: Map<String, Any?>, rejected: Map<String, Int> = emptyMap()): String {
        val root = buildJsonObject {
            put("version", 1)
            put("target", SitePool.CAPACITY)
            put("generated", "2026-01-01T00:00:00Z")
            putJsonArray("entries") {
                entries.forEach { entry ->
                    addJsonObject(
                        buildJsonObject {
                            put("url", entry["url"] as String)
                            put("name", entry["name"] as String? ?: "")
                            @Suppress("UNCHECKED_CAST")
                            putJsonArray("templates") {
                                (entry["templates"] as? List<String>).orEmpty().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                            @Suppress("UNCHECKED_CAST")
                            putJsonArray("categories") {
                                (entry["categories"] as? List<String>).orEmpty().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                            put("score", (entry["score"] as? Int) ?: 0)
                            put("resolved", (entry["resolved"] as? Int) ?: 0)
                            put("from", entry["from"] as String? ?: "test")
                        }
                    )
                }
            }
            putJsonObject("rejected") { rejected.forEach { (k, v) -> put(k, v) } }
        }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    private fun site(url: String, name: String = "", template: String? = null) = mapOf(
        "url" to url,
        "name" to name,
        "templates" to listOf(template ?: "https://${url.substringAfter("://")}/search?q={q}")
    )

    // -- import ------------------------------------------------------------

    @Test
    fun `a verified site is imported and added to the saved site list`() {
        val sink = Recorder()
        val pool = pool(sink = sink)
        val added = pool.import(document(site("https://a.example", "Site A")))
        assertEquals(1, added)
        assertEquals(1, pool.state.value.size)
        assertEquals(1, sink.added.size)
        assertEquals("Site A", sink.added.first().name)
        assertTrue(sink.added.first().templates.first().contains("{q}"))
    }

    @Test
    fun `a site with no query placeholder is refused, because it cannot be searched`() {
        val sink = Recorder()
        val pool = pool(sink = sink)
        val added = pool.import(document(site("https://dead.example", "Dead", template = "https://dead.example/browse")))
        assertEquals(0, added)
        assertEquals(0, pool.state.value.size)
        assertTrue("nothing should reach the site list", sink.added.isEmpty())
    }

    @Test
    fun `the same host is never imported twice`() {
        val pool = pool()
        val first = pool.import(document(site("https://a.example")))
        val second = pool.import(document(site("https://A.EXAMPLE/other"), site("https://www.a.example")))
        assertEquals(1, first)
        assertEquals("a.example and its variants are one host", 0, second)
        assertEquals(1, pool.state.value.size)
    }

    @Test
    fun `importing does not duplicate sites already added by the viewer`() {
        val sink = Recorder()
        val pool = pool(sink = sink)
        pool.import(document(site("https://a.example")))
        pool.import(document(site("https://b.example")))
        assertEquals(2, pool.state.value.size)
        assertEquals(2, sink.added.map { it.url }.distinct().size)
    }

    @Test
    fun `a malformed document is reported rather than looking like an empty crawl`() {
        val pool = pool()
        assertEquals(0, pool.import("{ this is not json"))
        assertTrue(pool.state.value.lastError.isNotBlank())
    }

    @Test
    fun `the rejected counts from the crawler are kept and shown`() {
        val pool = pool()
        pool.import(document(site("https://a.example"), rejected = mapOf("no homepage" to 7, "search returned nothing usable" to 12)))
        assertEquals(19, pool.state.value.rejectedTotal)
    }

    // -- the two pools are separate ----------------------------------------

    @Test
    fun `the site pool has its own 3000 ceiling`() {
        // 3000 sites, then one more: the site pool fills on its own and does not
        // borrow the manifest pool's capacity, which is the whole point of
        // keeping two pools instead of one 6000-entry list of unlike things.
        val entries = (0 until SitePool.CAPACITY).map { site("https://s$it.example") }
        val pool = pool()
        assertEquals(SitePool.CAPACITY, pool.import(document(*entries.toTypedArray())))
        assertEquals(3000, pool.state.value.size)
        assertTrue(pool.state.value.full)
        assertEquals(0, pool.import(document(site("https://overflow.example"))))
        assertEquals(3000, pool.state.value.size)
    }

    // -- what the resolver is offered --------------------------------------

    @Test
    fun `only a site that produced a stream counts as working`() {
        val pool = pool()
        pool.import(document(site("https://a.example"), site("https://b.example")))
        pool.noteResult("a.example", resolved = 2, miss = false)
        pool.noteResult("b.example", resolved = 0, miss = true)
        assertEquals(1, pool.state.value.working)
        assertEquals("a.example", pool.working().single().host)
    }

    @Test
    fun `batch puts proven sites first and unproven ones after`() {
        val pool = pool()
        pool.import(document(site("https://proven.example"), site("https://untried.example")))
        pool.noteResult("proven.example", resolved = 1, miss = false)
        val batch = pool.batch(2)
        assertEquals("proven.example", batch.first().url.substringAfter("://"))
    }

    @Test
    fun `state is published so the Sources screen sees the pool grow`() = runTest {
        val pool = pool()
        pool.import(document(site("https://a.example")))
        val state = withTimeout(1_000) { pool.state.first { it.size == 1 } }
        assertEquals(SitePool.CAPACITY, state.capacity)
        assertFalse(state.full)
    }

    @Test
    fun `an unreadable bundled pool is reported instead of showing an empty pool`() {
        // NovaApplication reads assets/site_pool.json at startup. The asset is
        // committed so the read should always work -- but if it does not, a zero
        // on the Sources screen is indistinguishable from "the crawl found
        // nothing", which is the false green this app is not allowed to show.
        val pool = pool()
        pool.noteBundledPoolMissing("FileNotFoundException")
        val state = pool.state.value
        assertEquals(0, state.size)
        assertTrue(
            "the reason should be visible, was: \"${state.lastError}\"",
            state.lastError.contains("FileNotFoundException")
        )
    }

    @Test
    fun `a pool file with extra crawler fields still loads`() {
        // The crawler writes by_source into the pool file so a total can be
        // explained. If the app's Json were not configured with
        // ignoreUnknownKeys, that one extra key would make the whole pool
        // unreadable on device -- a silent empty pool that looks like a failed
        // crawl. This test exists so that cannot regress unnoticed.
        val text = """
            {
              "version": 1,
              "target": 3000,
              "generated": "2026-11-20T18:41:53Z",
              "by_source": {"github": 40, "list": 43},
              "rejected": {"no homepage": 1290},
              "entries": [
                {"url": "https://example.org", "name": "Example",
                 "templates": ["https://example.org/s?q={q}"],
                 "score": 7, "resolved": 2, "misses": 0}
              ]
            }
        """.trimIndent()
        val store = MemoryStore(text)
        val pool = pool(store)
        assertEquals("the entry should load despite the extra key", 1, pool.state.value.size)
        assertEquals("no homepage" to 1290, pool.state.value.rejected.entries.first().toPair())
    }

    @Test
    fun `an imported pool survives a restart`() {
        val store = MemoryStore()
        pool(store).import(document(site("https://a.example"), site("https://b.example")))
        val again = pool(store)
        assertEquals(2, again.state.value.size)
    }
}
