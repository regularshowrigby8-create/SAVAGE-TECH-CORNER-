package dev.nova.tv.ui.screens

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.repo.CatalogOrigin
import dev.nova.tv.data.repo.CatalogPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPagingTest {
    private val origin = CatalogOrigin(
        NovaAddon("https://example.org", Manifest(id = "example", name = "Example")),
        CatalogDef(type = "movie", id = "top")
    )
    private val one = MetaPreview(id = "tt1", name = "One")
    private val two = MetaPreview(id = "tt2", name = "Two")

    @Test fun `shelf errors preserve source cursor and content for retry`() {
        val before = Shelf("top", "Top", items = listOf(one), loading = true,
            cursor = 10, origin = origin)
        val failed = before.withPage(CatalogPage(error = "offline"))
        assertEquals(listOf(one), failed.items)
        assertEquals(10, failed.cursor)
        assertEquals(origin, failed.origin)
        assertFalse(failed.exhausted)
        assertFalse(failed.loading)
        assertEquals("offline", failed.error)
        val resumed = failed.withPage(CatalogPage(items = listOf(one, two), origin = origin,
            nextSkip = 20, exhausted = false))
        assertEquals(listOf(one, two), resumed.items)
        assertEquals(20, resumed.cursor)
        assertNull(resumed.error)
        assertFalse(resumed.exhausted)
    }

    @Test fun `browse errors remain retryable and empty success means exhaustion`() {
        val before = BrowseState(items = listOf(one), loading = true, cursor = 4, origin = origin)
        val failed = before.withPage(CatalogPage(error = "timeout"))
        assertEquals(4, failed.cursor)
        assertEquals(origin, failed.origin)
        assertEquals(listOf(one), failed.items)
        assertFalse(failed.exhausted)
        assertEquals("timeout", failed.error)
        val ended = failed.withPage(CatalogPage(origin = origin, nextSkip = 4, exhausted = true))
        assertTrue(ended.exhausted)
        assertNull(ended.error)
    }
}
