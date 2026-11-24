package dev.nova.tv.finder.pool

import dev.nova.tv.finder.FinderCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ServerPoolTest {

    private class MemoryStore : PoolStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private fun c(host: String, path: String = "") = FinderCandidate(
        manifestUrl = "https://$host$path/manifest.json", baseUrl = "https://$host$path", name = host,
        description = "", version = "1", types = listOf("movie"), idPrefixes = listOf("tt"),
        searchable = false, stars = 0, from = "test"
    )

    private fun many(n: Int, prefix: String = "s") = (0 until n).map { c("$prefix$it.example") }

    @Test
    fun `only servers that answered are listed as working`() {
        val pool = ServerPool(MemoryStore())
        pool.add(many(3))
        pool.noteResult("https://s1.example", hit = true)
        assertEquals(listOf("https://s1.example"), pool.working().map { it.baseUrl })
        assertEquals(1, pool.state.value.working)
        assertEquals(3, pool.state.value.size)
    }

    @Test
    fun `the same server is never added twice`() {
        val pool = ServerPool(MemoryStore())
        assertEquals(1, pool.add(listOf(c("a.example"))))
        assertEquals(0, pool.add(listOf(c("A.example"), c("a.example"))))
        assertEquals(0, pool.add(listOf(FinderCandidate("https://a.example/manifest.json", "https://a.example/", "a", "", "", emptyList(), emptyList(), false, 0, ""))))
        assertEquals(1, pool.state.value.size)
    }

    @Test
    fun `one host cannot crowd the pool`() {
        val pool = ServerPool(MemoryStore())
        pool.add((0 until 20).map { c("one.example", "/cfg$it") })
        assertEquals(ServerPool.PER_HOST, pool.state.value.size)
    }

    @Test
    fun `holds 10000 and no more`() {
        val pool = ServerPool(MemoryStore())
        val added = pool.add(many(10_500))
        assertEquals(ServerPool.CAPACITY, added)
        assertEquals(10_000, ServerPool.CAPACITY)
        assertEquals(ServerPool.CAPACITY, pool.state.value.size)
    }

    @Test
    fun `scramble retires dead servers, keeps proven ones and brings in new ones`() {
        val pool = ServerPool(MemoryStore(), Random(1))
        pool.add(many(10, "old"))
        repeat(ServerPool.RETIRE_AFTER_MISSES) { (1 until 10).forEach { i -> pool.noteResult("https://old$i.example", hit = false) } }
        pool.noteResult("https://old0.example", hit = true)
        val added = pool.scramble(many(10, "old") + many(5, "new"))
        assertEquals(5, added)
        val keys = pool.entries().map { ServerPool.key(it.baseUrl) }.toSet()
        assertTrue("old0.example" in keys)
        assertFalse("old1.example" in keys)
        assertEquals(6, keys.size)
        // A retired server is never re-added.
        assertEquals(0, pool.add(listOf(c("old1.example"))))
    }

    @Test
    fun `revert restores the previous pool exactly`() {
        val pool = ServerPool(MemoryStore(), Random(2))
        pool.add(many(8, "a"))
        val before = pool.entries().map { it.baseUrl }
        // One miss short of the retirement threshold: noteResult no longer
        // drops them, so scramble is what retires them and the history entry
        // that revert needs is created.
        repeat(ServerPool.RETIRE_AFTER_MISSES - 1) { before.forEach { pool.noteResult(it, hit = false) } }
        assertEquals(before, pool.entries().map { it.baseUrl })
        assertEquals(0, pool.state.value.retired)
        pool.scramble(many(8, "b"))
        assertTrue(pool.state.value.canRevert)
        assertTrue(pool.entries().none { it.baseUrl in before })
        assertTrue(pool.revert())
        assertEquals(before, pool.entries().map { it.baseUrl })
        assertFalse(pool.state.value.canRevert)
        assertFalse(pool.revert())
        // Reverted servers are usable again.
        assertEquals(0, pool.add(listOf(c("a0.example"))))
        assertEquals(8, pool.state.value.size)
    }

    @Test
    fun `batch skips servers already asked and puts proven ones first`() {
        val pool = ServerPool(MemoryStore())
        pool.add(many(5))
        pool.noteResult("https://s3.example", hit = true)
        val batch = pool.batch(3) { it.contains("s0.") }
        assertEquals("https://s3.example", batch.first().baseUrl)
        assertTrue(batch.none { it.baseUrl.contains("s0.") })
        assertEquals(3, batch.size)
    }

    @Test
    fun `the pool survives a restart`() {
        val store = MemoryStore()
        val pool = ServerPool(store)
        pool.add(many(4))
        pool.scramble(many(2, "n"))
        val again = ServerPool(store)
        assertEquals(pool.entries(), again.entries())
        assertTrue(again.state.value.canRevert)
    }

    @Test
    fun `keys ignore scheme, case, manifest and trailing slash`() {
        assertEquals("a.example/x", ServerPool.key("HTTPS://A.example/x/manifest.json?y=1"))
        assertEquals("a.example/x", ServerPool.key("https://a.example/x/"))
    }

    @Test
    fun `a server that never answers is dropped immediately and never re-added`() {
        val pool = ServerPool(MemoryStore())
        pool.add(many(5, "dead"))
        repeat(ServerPool.RETIRE_AFTER_MISSES) { pool.noteResult("https://dead0.example", hit = false) }
        assertEquals(4, pool.state.value.size)
        assertTrue(pool.entries().none { it.baseUrl.contains("dead0") })
        // Retired, not merely missing: discovery cannot bring it back.
        assertEquals(0, pool.add(listOf(c("dead0.example"))))
    }

    @Test
    fun `pruneDead frees slots and keeps servers that have answered`() {
        val store = MemoryStore().apply {
            text = """
                {"entries":[
                  {"manifestUrl":"https://stale.example/manifest.json","baseUrl":"https://stale.example","name":"stale","hits":0,"misses":2},
                  {"manifestUrl":"https://good.example/manifest.json","baseUrl":"https://good.example","name":"good","hits":1,"misses":0}
                ]}
            """.trimIndent()
        }
        val pool = ServerPool(store)
        assertEquals(1, pool.pruneDead())
        assertEquals(1, pool.state.value.size)
        assertTrue(pool.entries().single().baseUrl.contains("good.example"))
    }
}
