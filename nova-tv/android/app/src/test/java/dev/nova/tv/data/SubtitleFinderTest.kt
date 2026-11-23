package dev.nova.tv.data

import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.SubtitleFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitle language matching and the legacy REST search path. */
class SubtitleFinderTest {

    @Test
    fun `language codes match across en eng and English`() {
        val ref = SubtitleRef(lang = "eng")
        assertTrue(ref.matchesLanguage("en"))
        assertTrue(ref.matchesLanguage("eng"))
        assertTrue(ref.matchesLanguage("English"))
        assertTrue(SubtitleRef(lang = "en").matchesLanguage("eng"))
    }

    @Test
    fun `different languages do not match`() {
        assertTrue(!SubtitleRef(lang = "spa").matchesLanguage("eng"))
        assertTrue(!SubtitleRef(lang = "").matchesLanguage("eng"))
    }

    @Test
    fun `rest url uses the imdb id without leading zeros`() {
        val finder = SubtitleFinder(http = dev.nova.tv.data.remote.HttpClient())
        assertEquals(
            "https://rest.opensubtitles.org/search/imdbid-111161/sublanguageid-eng",
            finder.restUrl("movie", "tt0111161")
        )
    }

    @Test
    fun `rest url carries season and episode for series`() {
        val finder = SubtitleFinder(http = dev.nova.tv.data.remote.HttpClient())
        assertEquals(
            "https://rest.opensubtitles.org/search/imdbid-944947/season-2/episode-5/sublanguageid-eng",
            finder.restUrl("series", "tt0944947:2:5")
        )
        assertNull(finder.restUrl("movie", "kitsu:123"))
    }

    @Test
    fun `detail names the source and release`() {
        val ref = SubtitleRef(lang = "eng", source = "OpenSubtitles REST", release = "Movie.2024.ENG.srt")
        assertTrue(ref.detail.startsWith("OpenSubtitles REST"))
        assertEquals("ENG", ref.label)
    }
}
