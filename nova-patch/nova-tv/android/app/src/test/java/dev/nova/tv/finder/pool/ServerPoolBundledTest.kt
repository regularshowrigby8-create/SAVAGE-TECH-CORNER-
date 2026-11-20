package dev.nova.tv.finder.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crawler's JSON must become real on-device candidates, not just a CI artifact. */
class ServerPoolBundledTest {
    private class MemoryStore(var text: String? = null) : PoolStore {
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
    }

    private fun document(vararg hosts: String): String = """
        {
          "by_source": {"test": 2},
          "entries": [${hosts.joinToString(",") {
              """{"manifestUrl":"https://$it/manifest.json","baseUrl":"https://$it","name":"$it"}"""
          }}]
        }
    """.trimIndent()

    @Test
    fun `bundled entries merge once and preserve existing hit counts`() {
        val store = MemoryStore()
        val pool = ServerPool(store)
        assertEquals(1, pool.importBundled(document("one.example")))
        pool.noteResult("https://one.example", true)
        assertEquals(1, pool.importBundled(document("one.example", "two.example")))
        assertEquals(2, pool.state.value.size)
        assertEquals(1, pool.entries().first { it.baseUrl.contains("one.example") }.hits)
        assertEquals(0, pool.importBundled(document("one.example", "two.example")))
        assertEquals(2, ServerPool(store).state.value.size)
    }

    @Test
    fun `invalid or missing bundled data is reported rather than counted as empty`() {
        val pool = ServerPool(MemoryStore())
        assertEquals(0, pool.importBundled("{\"error\":\"no entries\"}"))
        assertTrue(pool.state.value.lastError.contains("entries"))
        pool.noteBundledPoolMissing("FileNotFoundException")
        assertTrue(pool.state.value.lastError.contains("FileNotFoundException"))
    }

    @Test
    fun `bundled import respects the independent server capacity`() {
        val pool = ServerPool(MemoryStore())
        val hosts = (0..ServerPool.CAPACITY).map { "s$it.example" }.toTypedArray()
        assertEquals(ServerPool.CAPACITY, pool.importBundled(document(*hosts)))
        assertEquals(ServerPool.CAPACITY, pool.state.value.size)
        assertTrue(pool.state.value.full)
    }

    @Test
    fun `write failures are visible`() {
        val pool = ServerPool(object : PoolStore {
            override fun read(): String? = null
            override fun write(text: String) { throw java.io.IOException("storage unavailable") }
        })
        pool.importBundled(document("one.example"))
        assertTrue(pool.state.value.lastError.contains("storage unavailable"))
    }
}
