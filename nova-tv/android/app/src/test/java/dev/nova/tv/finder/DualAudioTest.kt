package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualAudioTest {

    @Test
    fun `international means the first production country is not English-speaking`() {
        assertTrue(DualAudio.isInternational(MetaPreview(country = "South Korea")))
        assertTrue(DualAudio.isInternational(MetaPreview(country = "Japan, United States")))
        assertFalse(DualAudio.isInternational(MetaPreview(country = "United States, Japan")))
        assertFalse(DualAudio.isInternational(MetaPreview(country = "UK")))
        assertTrue(DualAudio.isInternational(MetaPreview(language = "Spanish, English")))
        assertFalse(DualAudio.isInternational(MetaPreview()))
    }

    @Test
    fun `languages are read from release names`() {
        assertEquals(setOf("hi", "en"), DualAudio.languagesOf(Stream(title = "Movie 2024 1080p Hindi + English AAC")))
        assertEquals(setOf("ja", "en"), DualAudio.languagesOf(Stream(title = "Show S01E02 [ENG-JPN] 1080p")))
        assertTrue("en" in DualAudio.languagesOf(Stream(title = "Film [EN] 720p")))
        // "Golden" must not read as a language tag.
        assertTrue(DualAudio.languagesOf(Stream(title = "Golden Kamuy 1080p")).isEmpty())
    }

    @Test
    fun `dual means English plus another, or a dual release with no foreign-only tags`() {
        assertTrue(DualAudio.hasEnglishPlusOther(Stream(title = "Movie 1080p Dual Audio")))
        assertTrue(DualAudio.hasEnglishPlusOther(Stream(title = "Movie MULTI ENG FRE 1080p")))
        assertFalse(DualAudio.hasEnglishPlusOther(Stream(title = "Movie 1080p Hindi Tamil")))
        assertFalse(DualAudio.hasEnglishPlusOther(Stream(title = "Movie 1080p WEB-DL")))
    }
}
