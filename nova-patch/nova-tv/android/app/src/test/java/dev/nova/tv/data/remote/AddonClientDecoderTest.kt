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

    @Test fun `explicit provider episode mapping survives decoding`() {
        // Same shape as Kitsu's second-season metadata: its local S1E1 is
        // IMDb S2E1. Never infer that relationship from the local season alone.
        val page = client.decodeCatalog("""
            {"metas":[{"id":"kitsu:12268","type":"series","name":"Anime",
              "videos":[{"id":"kitsu:12268:1","season":1,"episode":1,
                "imdb_id":"tt5626028","imdbSeason":2,"imdbEpisode":1}]}]}
        """.trimIndent(), "series")
        val request = dev.nova.tv.finder.TitleRequest(page.items.single(), season = 1, episode = 1)
        assertEquals(listOf("kitsu:12268:1", "tt5626028:2:1"), request.streamIds)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing metas is an error rather than a successful empty feed`() {
        client.decodeCatalog("{}", "movie")
    }
}
