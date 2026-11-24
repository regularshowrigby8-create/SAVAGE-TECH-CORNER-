package dev.nova.tv.finder.translated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the bug that made every discovery surface return zero.
 *
 * `Accept-Encoding: gzip, deflate, br` used to be set by hand. OkHttp only
 * decompresses a response when *it* added the header, so setting it disabled
 * the transparent gzip path, and OkHttp 4.12 has no brotli decoder at all.
 * Every engine that compressed its answer therefore handed the finder raw
 * compressed bytes, `String(bytes)` held no `href=` and no JSON, and the device
 * trace read `search -> 0 host(s) of 0 link(s)`, `ct -> 0`, `rapiddns -> 0`,
 * `wayback -> 0` while the servers were answering 200 with real data.
 *
 * Measured on Bing: 0 hrefs with the header set, 93 without it. These tests
 * fail loudly if anyone re-adds it.
 */
class FinderFingerprintTest {

    @Test
    fun `document headers never pin accept encoding`() {
        val f = FinderFingerprint()
        val h = f.headersDocument("https://aniwave.to/watch/1", "https://www.bing.com/")
        assertTrue("a document request needs a UA", h.containsKey("User-Agent"))
        assertFalse(
            "Accept-Encoding must be left to OkHttp, which is the only thing that decompresses it",
            h.keys.any { it.equals("Accept-Encoding", ignoreCase = true) }
        )
        assertFalse(h.values.any { "br" in it && "gzip" in it })
    }

    @Test
    fun `cors headers never pin accept encoding`() {
        val f = FinderFingerprint()
        val h = f.headersCors("https://api.certspotter.com/v1/issuances?domain=x", null)
        assertTrue(h.containsKey("Accept"))
        assertFalse(
            "the JSON surfaces gzip too; a pinned header makes them unreadable",
            h.keys.any { it.equals("Accept-Encoding", ignoreCase = true) }
        )
    }

    @Test
    fun `rotate produces a plausible chrome version or none`() {
        val f = FinderFingerprint()
        repeat(20) {
            f.rotate()
            assertTrue(f.ua.isNotEmpty())
            assertTrue(f.lang.isNotEmpty())
            assertTrue(f.tz.isNotEmpty())
            f.chrome?.let { assertEquals(true, it.all { c -> c.isDigit() }) }
        }
    }
}
