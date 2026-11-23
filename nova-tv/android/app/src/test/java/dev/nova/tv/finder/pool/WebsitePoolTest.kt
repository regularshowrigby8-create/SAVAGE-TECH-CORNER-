package dev.nova.tv.finder.pool

import dev.nova.tv.finder.website.SavedWebsite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The website pool is a second, independent pool: one entry per site. */
class WebsitePoolTest {

    private class MemoryStore : PoolStore {
        var text: String? = null
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
    }

    private fun site(url: String, name: String = url) = SavedWebsite(url, name, listOf("/search?q={q}"))

    @Test
    fun `merges by host and ignores a second path on the same site`() {
        val pool = WebsitePool(MemoryStore())
        val added = pool.add(
            listOf(
                site("https://one.example.com/"),
                site("https://www.one.example.com/watch/2"),
                site("https://two.example.org/")
            )
        )
        assertEquals(2, added)
        assertEquals(2, pool.entries().size)
    }

    @Test
    fun `a site that answers is working and is listed first`() {
        val pool = WebsitePool(MemoryStore())
        pool.add(listOf(site("https://slow.example.com/"), site("https://good.example.com/")))
        pool.noteResult("https://good.example.com/", true)

        assertEquals(1, pool.state.value.working)
        assertEquals("https://good.example.com/", pool.working().first().url)
    }

    @Test
    fun `two misses without an answer retires a site`() {
        val pool = WebsitePool(MemoryStore())
        pool.add(listOf(site("https://dead.example.com/")))
        pool.noteResult("https://dead.example.com/", false)
        pool.noteResult("https://dead.example.com/", false)

        assertTrue(pool.entries().isEmpty())
        // And it is not added again if a later round discovers it.
        assertEquals(0, pool.add(listOf(site("https://dead.example.com/"))))
    }

    @Test
    fun `batch skips claimed sites`() {
        val pool = WebsitePool(MemoryStore())
        pool.add(listOf(site("https://a.example.com/"), site("https://b.example.com/")))
        val batch = pool.batch(10) { it.contains("a.example.com") }
        assertEquals(1, batch.size)
        assertEquals("https://b.example.com/", batch.first().url)
    }
}
