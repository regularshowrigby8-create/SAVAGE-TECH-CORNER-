package dev.nova.tv.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Small HTTP layer over OkHttp.
 *
 * Addon feeds are attacker-controlled input, so this class is the place the
 * safety rules live: scheme and host are checked before every request, bodies are
 * size-capped, and transient failures are retried with backoff.
 */
class HttpClient(
    private val userAgent: String = "NovaTV/1.0",
    private val maxBytes: Long = 8L * 1024 * 1024
) {
    /**
     * Shared with the player's data source so playback and addon calls reuse
     * one connection pool.
     *
     * Redirects used to be followed blindly, which made [isAllowedUrl] a
     * check on the first hop only: a public addon could 302 to
     * http://192.168.1.1/ and the response came back through the UI. The
     * network interceptor re-applies the guard on every hop.
     */
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // ARVIO: a larger pool keeps video hosts' connections warm between
        // seeks and source switches; DNS is cached and IPv4-first (NovaDns).
        .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
        .dns(NovaDns)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url.toString()
            if (!isAllowedUrl(url)) throw HttpFailure("refused unsafe redirect: $url")
            chain.proceed(chain.request())
        }
        .build()

    private val client get() = okHttp

    val agent: String get() = userAgent

    /** Thrown for anything the caller should surface to the user. */
    class HttpFailure(message: String, val status: Int? = null) : IOException(message)

    /**
     * GET a text body.
     *
     * [attempts] and [limitBytes] exist for the source finder, which probes
     * dozens of unknown hosts: one try each, and a manifest larger than
     * 160 kB is not a manifest.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        attempts: Int = MAX_ATTEMPTS,
        limitBytes: Long = maxBytes
    ): String =
        withContext(Dispatchers.IO) {
            if (!isAllowedUrl(url)) throw HttpFailure("refused unsafe url: $url")
            var lastError: IOException? = null
            repeat(attempts.coerceIn(1, MAX_ATTEMPTS)) { attempt ->
                try {
                    return@withContext execute(url, headers, limitBytes)
                } catch (error: HttpFailure) {
                    // 4xx other than 429 is not worth retrying.
                    if (error.status != null && error.status in 400..499 && error.status != 429) throw error
                    lastError = error
                } catch (error: IOException) {
                    lastError = error
                }
                if (attempt < attempts - 1) delay(backoffMillis(attempt))
            }
            throw lastError ?: HttpFailure("request failed: $url")
        }

    private fun execute(url: String, headers: Map<String, String>, limitBytes: Long): String {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure("HTTP ${response.code} for $url", response.code)
            val body = response.body ?: throw HttpFailure("empty body for $url")
            val declared = body.contentLength()
            if (declared > limitBytes) throw HttpFailure("response too large: $declared bytes")
            // Read at most limit+1 bytes, so a server that lies about (or
            // omits) Content-Length cannot make the app buffer gigabytes.
            val source = body.source()
            source.request(limitBytes + 1)
            if (source.buffer.size > limitBytes) throw HttpFailure("response too large after read")
            val text = source.buffer.readUtf8()
            return text
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = (400L shl attempt).coerceAtMost(8_000L)
        return (base * (0.6 + Random.nextDouble() * 0.8)).toLong()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /**
         * Only http/https, and never a loopback or private address. Without this
         * an addon could point the app at the user's own router or a metadata
         * service and read the response back through the UI.
         */
        fun isAllowedUrl(url: String): Boolean {
            val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            // A trailing dot is the same host ("localhost." resolves like
            // "localhost"), and a bare integer is still an IPv4 address
            // ("http://2130706433/" is 127.0.0.1), so neither may slip past.
            val host = parsed.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: return false
            if (host.isEmpty()) return false
            if (host.all { it.isDigit() }) return false
            if (host.startsWith("0x") && host.length > 2 && host.drop(2).all { it in '0'..'9' || it in 'a'..'f' }) return false
            if (host == "localhost" || host.endsWith(".localhost") ||
                host.endsWith(".local") || host.endsWith(".internal")
            ) return false
            if (host == "0.0.0.0") return false
            // IPv6 literals: loopback, unspecified, unique-local (fc00::/7),
            // link-local (fe80::/10) and IPv4-mapped private ranges.
            if (host.contains(':')) {
                if (host == "::1" || host == "::") return false
                if (host.startsWith("fc") || host.startsWith("fd")) return false
                if (host.startsWith("fe8") || host.startsWith("fe9") ||
                    host.startsWith("fea") || host.startsWith("feb")
                ) return false
                if (host.startsWith("::ffff:")) return isAllowedUrl("http://" + host.removePrefix("::ffff:") + "/")
            }
            val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
            val match = ipv4.find(host)
            if (match != null) {
                val octets = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
                if (octets.size != 4) return false
                val (a, b) = octets
                if (a == 10 || a == 127) return false
                if (a == 192 && b == 168) return false
                if (a == 172 && b in 16..31) return false
                if (a == 169 && b == 254) return false
                if (a == 0) return false
                if (a == 100 && b in 64..127) return false // carrier-grade NAT
                if (octets.any { it > 255 }) return false
            }
            return true
        }
    }
}
