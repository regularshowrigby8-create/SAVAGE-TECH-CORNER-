package dev.nova.tv.finder.translated

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session cookie store the finders depend on.
 *
 * Python's `Http` shares one `aiohttp.ClientSession`, so a cookie set by
 * `prime()` is replayed on every later request. OkHttp defaults to
 * `CookieJar.NO_COOKIES`, and the translation inherited that default -- which
 * silently dropped every `Set-Cookie` and is why the website/server finder
 * returned nothing: the wall rejected each follow-up request with 403, so no
 * probe ever saw a 200 carrying media.
 *
 * These tests pin the behaviour that fix restored.
 */
class CookieJarTest {

    private val site = "https://example.com".toHttpUrl()

    private fun sessionCookie(name: String, value: String): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain("example.com")
            .path("/")
            // A session cookie: no Max-Age/Expires. OkHttp stores it with
            // expiresAt == MAX_DATE, so it must survive the expiry sweep.
            .build()

    private fun jar(): MemoryCookieJar = MemoryCookieJar()

    @Test
    fun `a session cookie set by one response is replayed on the next request`() {
        val jar = jar()
        jar.saveFromResponse(site, listOf(sessionCookie("__cf_bm", "abc123")))
        val sent = jar.loadForRequest(site)
        assertEquals("the session cookie must be sent again", 1, sent.size)
        assertEquals("__cf_bm", sent[0].name)
        assertEquals("abc123", sent[0].value)
    }

    @Test
    fun `a session cookie is not treated as already expired`() {
        val jar = jar()
        jar.saveFromResponse(site, listOf(sessionCookie("session", "s1")))
        // Read it back twice: the expiry sweep runs on both paths and must not
        // remove a cookie that has no explicit expiry.
        jar.loadForRequest(site)
        assertEquals("session cookies must survive the expiry sweep", 1, jar.loadForRequest(site).size)
    }

    @Test
    fun `cookies are not sent to an unrelated host`() {
        val jar = jar()
        jar.saveFromResponse(site, listOf(sessionCookie("session", "s1")))
        assertTrue(
            "a host-only cookie must never leak to another site",
            jar.loadForRequest("https://elsewhere.test/catalog".toHttpUrl()).isEmpty()
        )
    }

    @Test
    fun `re-setting a cookie replaces it instead of duplicating it`() {
        val jar = jar()
        jar.saveFromResponse(site, listOf(sessionCookie("session", "first")))
        jar.saveFromResponse(site, listOf(sessionCookie("session", "second")))
        val sent = jar.loadForRequest(site)
        assertEquals("one cookie per name/domain/path", 1, sent.size)
        assertEquals("second", sent[0].value)
    }

    @Test
    fun `an explicitly expired cookie is dropped`() {
        val jar = jar()
        val dead = Cookie.Builder()
            .name("gone")
            .value("x")
            .hostOnlyDomain("example.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 60_000)
            .build()
        jar.saveFromResponse(site, listOf(dead, sessionCookie("live", "1")))
        val sent = jar.loadForRequest(site)
        assertEquals("only the live cookie is sent", 1, sent.size)
        assertEquals("live", sent[0].name)
    }
}
