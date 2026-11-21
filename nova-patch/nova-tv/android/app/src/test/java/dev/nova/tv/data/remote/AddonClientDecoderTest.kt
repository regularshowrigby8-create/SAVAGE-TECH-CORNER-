package dev.nova.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonClientDecoderTest {
    private val client = AddonClient(HttpClient())

    @Test fun `mixed manifest resource forms retain object details`() {
        val manifest = client.decodeManifest("""
            {"id":"fixture","name":"Fixture","resources":["catalog",{"name":"meta","types":["series"],"idPrefixes":["kitsu:"]}],
             "types":["movie","series"],"catalogs":[{"type":"movie","id":"top"}]}
        """.trimIndent())
        assertEquals(listOf("catalog", "meta"), manifest.resources.map { it.name })
        assertEquals(listOf("series"), manifest.resources[1].types)
        assertEquals(listOf("kitsu:"), manifest.resources[1].idPrefixes)
        assertEquals("top", manifest.catalogs.first().id)
    }

    @Test fun `one malformed title does not empty catalogue and raw cursor is preserved`() {
        val page = client.decodeCatalog("""
            {"metas":[
                {"id":"tt1","name":"One","year":2024,"imdbRating":7.5},
                {"id":"tt2","name":"Two","genres":"not a list"},
                {"imdb_id":"tt3","name":"Three"},
                null,
                {"id":"","name":""}
            ]}
        """.trimIndent(), "series")
        assertEquals(5, page.consumed)
        assertEquals(listOf("tt1", "tt2", "tt3"), page.items.map { it.id })
        assertEquals("2024", page.items.first().year)
        assertEquals("7.5", page.items.first().imdbRating)
        assertTrue(page.items.all { it.type == "series" })
        assertEquals("tt3", page.items.last().imdbId)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing metas is an error rather than a successful empty feed`() {
        client.decodeCatalog("{}", "movie")
    }
}
