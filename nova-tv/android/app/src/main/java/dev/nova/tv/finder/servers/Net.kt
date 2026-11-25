package dev.nova.tv.finder.servers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Ua {
    private val pool = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64; rv:129.0) Gecko/20100101 Firefox/129.0"
    )
    private val cursor = AtomicInteger()
    fun next(): String = pool[Math.floorMod(cursor.getAndIncrement(), pool.size)]
    fun desktop(): String = pool[1]
}

/** [location] is the raw Location header; redirects are walked by hand. */
data class Fetch(
    val status: Int,
    val type: String,
    val body: String,
    val finalUrl: String,
    val location: String? = null
)

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .connectionPool(ConnectionPool(24, 4, TimeUnit.MINUTES))
        .build()

    /** Manual redirects: the verifier needs the Location to detect a domain move. */
    suspend fun get(
        url: String,
        cap: Int = 200_000,
        mobile: Boolean = true,
        referer: String? = null
    ): Fetch? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", if (mobile) Ua.next() else Ua.desktop())
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        try {
            client.newCall(req).execute().use { r ->
                Fetch(
                    r.code,
                    r.header("Content-Type").orEmpty().lowercase(),
                    if (r.body == null) "" else r.peekBody(cap.toLong()).string(),
                    r.request.url.toString(),
                    r.header("Location")
                )
            }
        } catch (e: Exception) { null }
    }

    suspend fun head(url: String): Fetch? = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", Ua.next()).head().build()
            ).execute().use {
                Fetch(
                    it.code,
                    it.header("Content-Type").orEmpty(),
                    "",
                    url,
                    it.header("Location")
                )
            }
        } catch (e: Exception) { null }
    }
}

suspend fun tcpAlive(host: String, ms: Long = 2_500): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, 443), ms.toInt()) }
        true
    } catch (e: Exception) { false }
}

fun hostOf(url: String): String? = try {
    URI(if ("://" in url) url else "https://$url").host?.lowercase()?.removePrefix("www.")
} catch (e: Exception) { null }

fun registrable(host: String?): String? {
    if (host.isNullOrBlank()) return null
    val p = host.split('.')
    return if (p.size >= 2) p.takeLast(2).joinToString(".") else host
}

fun now(): Long = System.currentTimeMillis()
