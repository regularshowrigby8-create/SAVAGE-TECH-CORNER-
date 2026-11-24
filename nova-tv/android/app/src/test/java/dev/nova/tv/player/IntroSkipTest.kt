package dev.nova.tv.player

import dev.nova.tv.data.remote.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intro window parsing and the rules for offering a skip.
 *
 * The two databases return different shapes for the same idea, and both were
 * confirmed live: TheIntroDB returns an *array* of windows, IntroDB.app returns
 * a single object with a confidence. Getting this wrong shows a skip button in
 * the wrong place, which is worse than showing none, so the shapes are pinned
 * here against the real payloads.
 */
class IntroSkipTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    // HttpClient is only needed to construct the lookup; nothing here hits the
    // network, so an instance with a default client is enough.
    private fun lookup() = IntroLookup(HttpClient())

    @Test
    fun `parses TheIntroDB array shape`() {
        // Verified response for tt0903747 S1E1.
        val doc = obj(
            """{"tvdb_id":81189,"type":"tv","season":1,"episode":1,
               "intro":[{"start_ms":228665,"end_ms":246498}],
               "credits":[{"start_ms":3432884,"end_ms":null}]}"""
        )
        val segment = lookup().parseIntro(doc)
        assertEquals(228665L, segment?.startMs)
        assertEquals(246498L, segment?.endMs)
    }

    @Test
    fun `parses IntroDB app object shape with confidence`() {
        // Verified response shape for IntroDB.app.
        val doc = obj(
            """{"imdb_id":"tt0903747","media_type":"tv","season":1,"episode":1,
               "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,
                        "confidence":0.92,"submission_count":14}}"""
        )
        val segment = lookup().parseIntro(doc)
        assertEquals(2500L, segment?.startMs)
        assertEquals(58000L, segment?.endMs)
        assertEquals(0.92, segment!!.confidence, 0.0001)
    }

    @Test
    fun `a null intro is not an error`() {
        // The real "known but not timed" answer: every segment is null.
        val doc = obj(
            """{"imdb_id":"tt0903570","media_type":"tv","season":1,"episode":1,
               "intro":null,"recap":null,"outro":null,"post_credits":null,
               "no_intro":false,"no_recap":false,"no_outro":false}"""
        )
        assertNull(lookup().parseIntro(doc))
    }

    @Test
    fun `an empty window is rejected`() {
        val segment = IntroSegment(startMs = 5000, endMs = 5000)
        assertFalse("a zero-length window is not skippable", segment.usable)
    }

    @Test
    fun `a very short window is rejected`() {
        val segment = IntroSegment(startMs = 5000, endMs = 8000)
        assertFalse("under five seconds is not an intro", segment.usable)
    }

    @Test
    fun `a low-confidence window is rejected`() {
        val segment = IntroSegment(startMs = 5000, endMs = 65000, confidence = 0.2)
        assertFalse("a guessed timing must not drive a skip button", segment.usable)
    }

    @Test
    fun `a normal window is usable`() {
        val segment = IntroSegment(startMs = 2500, endMs = 58000, confidence = 0.9)
        assertTrue(segment.usable)
        assertEquals(55500L, segment.durationMs)
    }
}
