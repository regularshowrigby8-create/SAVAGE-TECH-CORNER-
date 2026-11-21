package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogIdentifiersTest {
    @Test fun `native colon identifiers and explicit episode ids survive`() {
        assertEquals("kitsu:7442", FinderRules.catalogueId("kitsu:7442", false))
        assertEquals("kitsu:7442:9", FinderRules.catalogueId("kitsu:7442", true, 2, 3, "kitsu:7442:9"))
        assertEquals("tt0903747:2:3", FinderRules.catalogueId("tt0903747:9:9", true, 2, 3))
        assertEquals("afhd:123", FinderRules.catalogueId("afhd:123", false))
    }

    @Test fun `only explicit episode mapping may route a Kitsu title to IMDb addons`() {
        val episode = Video(id = "kitsu:7442:9", season = 1, episode = 9,
            imdbId = "tt2560140", imdbSeason = 2, imdbEpisode = 3)
        val meta = MetaPreview(id = "kitsu:7442", type = "series", name = "Anime", videos = listOf(episode))
        val request = TitleRequest(meta, season = 1, episode = 9)
        assertEquals(listOf("kitsu:7442:9", "tt2560140:2:3"), request.streamIds)
        assertNull(TitleRequest(meta.copy(videos = listOf(episode.copy(imdbEpisode = null))), 1, 9).imdbStreamId)
        assertEquals("tt1234567", TitleRequest(MetaPreview(id = "afhd:123", name = "Movie", imdbId = "tt1234567")).imdbStreamId)
        assertNull(TitleRequest(MetaPreview(id = "afhd:123", name = "Movie")).imdbStreamId)
    }

    @Test fun `candidate prefix matching uses the requested id`() {
        val candidate = FinderCandidate("https://x.example/manifest.json", "https://x.example", "X", "", "1",
            listOf("series"), listOf("kitsu:"), false, 0, "test")
        assertTrue(candidate.acceptsId("kitsu:7442:1"))
        assertFalse(candidate.acceptsId("tt2560140:1:1"))
    }
}
