package dev.nova.tv.finder.website

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The website finder is a port of ZeroPlay's extract.js; these pin its rules. */
class WebsiteRulesTest {

    @Test
    fun `adjustEpisodeInUrl rewrites the episode and keeps the rest`() {
        assertEquals("https://s.example/watch?id=9&ep=5", WebsiteRules.adjustEpisodeInUrl("https://s.example/watch?id=9&ep=1", 1, 5))
        assertEquals("https://s.example/show/episode-12/", WebsiteRules.adjustEpisodeInUrl("https://s.example/show/episode-3/", 1, 12))
        assertEquals("https://s.example/show-x-ep-7", WebsiteRules.adjustEpisodeInUrl("https://s.example/show-x-ep-1", 1, 7))
        assertEquals("https://s.example/x/season-2/episode-4", WebsiteRules.adjustEpisodeInUrl("https://s.example/x/season-1/episode-1", 2, 4))
        assertEquals("https://s.example/tv/1399/3/9", WebsiteRules.adjustEpisodeInUrl("https://s.example/tv/1399/1/1", 3, 9))
    }

    @Test
    fun `adjustEpisodeInUrl does not read the episode as a group number`() {
        // "$1" followed by "2" must stay group 1 + "2", not group 12.
        assertEquals("https://s.example/a/ep-2", WebsiteRules.adjustEpisodeInUrl("https://s.example/a/ep-1", 1, 2))
        assertEquals("https://s.example/a/ep-10", WebsiteRules.adjustEpisodeInUrl("https://s.example/a/ep-1", 1, 10))
    }

    @Test
    fun `guardUrl refuses private hosts and odd schemes`() {
        assertNull(WebsiteRules.guardUrl("http://127.0.0.1/x"))
        assertNull(WebsiteRules.guardUrl("http://192.168.1.4/x"))
        assertNull(WebsiteRules.guardUrl("file:///etc/passwd"))
        assertNull(WebsiteRules.guardUrl("javascript:alert(1)"))
        assertTrue(WebsiteRules.guardUrl("https://site.example/watch") != null)
    }

    @Test
    fun `explicit episode markers are read from titles and urls`() {
        assertEquals(WebsiteRules.EpisodeInfo(2, 5), WebsiteRules.explicitEpisodeInfo("Show.S02E05.1080p"))
        val none = WebsiteRules.explicitEpisodeInfo("A film (2021)")
        assertNull(none.season)
        assertNull(none.episode)
    }

    @Test
    fun `title score accepts the right film and rejects the wrong episode`() {
        val q = WebsiteRules.Query(titles = listOf("The Bear"), type = "series", season = 1, episode = 2)
        val right = WebsiteRules.Candidate("The Bear", "the bear", "https://s.example/the-bear/season-1/episode-2")
        val wrong = WebsiteRules.Candidate("The Bear S01E05", "the bear", "https://s.example/the-bear-s01e05")
        assertTrue(WebsiteRules.isAccepted(right, q))
        assertEquals(0, WebsiteRules.titleScore(wrong, q))
        val other = WebsiteRules.Candidate("Bear Grylls Survival", "bear grylls", "https://s.example/bear-grylls")
        assertFalse(WebsiteRules.isAccepted(other, q))
    }

    @Test
    fun `search urls encode spaces and fill slugs`() {
        assertEquals("https://s.example/search?q=the%20bear", WebsiteRules.buildSearchUrl("https://s.example/search?q={q}", "the bear"))
        assertTrue(WebsiteRules.buildSearchUrl("https://s.example/search/{slug}", "The Bear").endsWith("/search/the-bear"))
    }

    @Test
    fun `episode urls are recognised`() {
        assertTrue(WebsiteRules.isEpisodeUrl("https://s.example/x/episode-3"))
        assertFalse(WebsiteRules.isEpisodeUrl("https://s.example/movie/dune"))
    }
}
