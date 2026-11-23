package dev.nova.tv.data

import dev.nova.tv.data.remote.HttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addon feeds are untrusted input. If a feed can point the app at the user's own
 * network, it can read back private services through the UI, so the guard is
 * tested rather than assumed.
 */
class UrlGuardTest {

    @Test
    fun `public https is allowed`() {
        assertTrue(HttpClient.isAllowedUrl("https://v3-cinemeta.strem.io/manifest.json"))
        assertTrue(HttpClient.isAllowedUrl("http://example.com/a.json"))
    }

    @Test
    fun `loopback is refused`() {
        assertFalse(HttpClient.isAllowedUrl("http://127.0.0.1:8080/x"))
        assertFalse(HttpClient.isAllowedUrl("http://localhost/x"))
        assertFalse(HttpClient.isAllowedUrl("http://0.0.0.0/x"))
    }

    @Test
    fun `private ranges are refused`() {
        assertFalse(HttpClient.isAllowedUrl("http://10.0.0.5/x"))
        assertFalse(HttpClient.isAllowedUrl("http://192.168.1.1/x"))
        assertFalse(HttpClient.isAllowedUrl("http://172.16.0.1/x"))
    }

    @Test
    fun `cloud metadata address is refused`() {
        assertFalse(HttpClient.isAllowedUrl("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `non-http schemes are refused`() {
        assertFalse(HttpClient.isAllowedUrl("file:///etc/passwd"))
        assertFalse(HttpClient.isAllowedUrl("ftp://example.com/x"))
        assertFalse(HttpClient.isAllowedUrl("javascript:alert(1)"))
        assertFalse(HttpClient.isAllowedUrl(""))
    }

    @Test
    fun `mDNS names are refused`() {
        assertFalse(HttpClient.isAllowedUrl("http://router.local/x"))
    }
}
