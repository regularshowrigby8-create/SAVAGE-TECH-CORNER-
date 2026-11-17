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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Thrown for anything the caller should surface to the user. */
    class HttpFailure(message: String, val status: Int? = null) : IOException(message)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            if (!isAllowedUrl(url)) throw HttpFailure("refused unsafe url: $url")
            var lastError: IOException? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    return@withContext execute(url, headers)
                } catch (error: HttpFailure) {
                    // 4xx other than 429 is not worth retrying.
                    if (error.status != null && error.status in 400..499 && error.status != 429) throw error
                    lastError = error
                } catch (error: IOException) {
                    lastError = error
                }
                delay(backoffMillis(attempt))
            }
            throw lastError ?: HttpFailure("request failed: $url")
        }

    private fun execute(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (key, value) -> builder.header(key, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure("HTTP ${response.code} for $url", response.code)
            val body = response.body ?: throw HttpFailure("empty body for $url")
            val declared = body.contentLength()
            if (declared > maxBytes) throw HttpFailure("response too large: $declared bytes")
            val text = body.string()
            if (text.length.toLong() > maxBytes) throw HttpFailure("response too large after read")
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
            val host = parsed.host?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".local")) return false
            if (host == "::1" || host == "0.0.0.0") return false
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
            }
            return true
        }
    }
}
