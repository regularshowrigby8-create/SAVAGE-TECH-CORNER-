package dev.nova.tv.finder.translated

import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

/**
 * One HTTP response. Python: `Resp` in both scripts.
 *
 * [body] holds at most the `read` bytes the caller asked for, exactly like
 * `await r.content.read(read)` in aiohttp.
 */
class Resp(
    val status: Int,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val text: String get() = body.decodeToString(Charsets.UTF_8, ignoreMalformed = true)

    /** Parsed body or null; never throws. Python: `Resp.json()`. */
    fun json(): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
}

/**
 * Shared session: per-host fairness, circuit breaker, 403/429 ladder.
 *
 * Translation of `Http` in both Python scripts:
 *  - global semaphore `concurrency`, per-host semaphore (3 for servers.py,
 *    4 for addons.py, passed as [hostPermits]),
 *  - circuit breaker: a hostile host is parked for 60 s ([trip]),
 *  - `backoff`: honour Retry-After on 429 (capped at 30 s), otherwise rotate
 *    the fingerprint and wait `(2 ** attempt) * 0.8 + random`,
 *  - `get`: retries 3 times, returns the last response even on failure,
 *    trips the host after the last attempt,
 *  - `prime`: warm cookies and return the final origin,
 *  - `auth`: rotate GitHub tokens across calls (addons.py only; empty by
 *    default on Android, where there is no environment to read them from).
 *
 * Platform adaptation, not a logic change: every URL is checked with
 * [HttpClient.isAllowedUrl] before it is requested, and OkHttp's network
 * interceptor in the shared client re-checks every redirect hop. The Python
 * scripts used `ssl=False` and no SSRF guard; on a phone that guard is the
 * difference between a finder and a port scanner, so it stays.
 */
class FinderHttp(
    baseClient: OkHttpClient,
    concurrency: Int,
    private val browser: FinderBrowser? = null,
    private val hostPermits: Int = 3,
    var delaySeconds: Double = 0.35,
    private val ghTokens: List<String> = emptyList(),
    private val cors: Boolean = false,
    private val fingerprint: FinderFingerprint = FinderFingerprint()
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    val okHttp: OkHttpClient get() = client
    val fp: FinderFingerprint get() = fingerprint

    private val sem = Semaphore(concurrency)
    private val hostSem = HashMap<String, Semaphore>()
    private val breaker = HashMap<String, Long>()
    private val lock = Any()
    val stats = HashMap<String, Int>()
    private var ghIndex = 0

    private fun hs(host: String): Semaphore = synchronized(lock) {
        hostSem.getOrPut(host) { Semaphore(hostPermits) }
    }

    fun tripped(host: String): Boolean = synchronized(lock) {
        (breaker[host] ?: 0L) > System.nanoTime()
    }

    /** Park a hostile host for 60 s instead of hammering it. */
    fun trip(host: String) {
        synchronized(lock) { breaker[host] = System.nanoTime() + 60_000_000_000L }
    }

    private suspend fun backoff(attempt: Int, status: Int, headers: Map<String, String>) {
        if (status == 429) {
            val ra = headers["Retry-After"].orEmpty()
            if (ra.all { it.isDigit() } && ra.isNotEmpty()) {
                delay(minOf(ra.toLong(), 30L) * 1000L)
                return
            }
        }
        fingerprint.rotate()
        delay(((2.0.pow(attempt)) * 0.8 + Random.nextDouble()) * 1000L)
    }

    /** Mint cookies for a wall through a real browser. No-op on Android. */
    private suspend fun solve(url: String, referer: String?) {
        if (browser == null) return
        runCatching { browser.mint(url, referer) }
    }

    /** Rotate GitHub tokens across calls; anonymous when none is configured. */
    fun auth(url: String): Map<String, String> {
        if ("api.github.com" in url && ghTokens.isNotEmpty()) {
            val tok = ghTokens[ghIndex % ghTokens.size]
            ghIndex += 1
            return mapOf(
                "Authorization" to "Bearer $tok",
                "X-GitHub-Api-Version" to "2022-11-28"
            )
        }
        return emptyMap()
    }

    fun headers(url: String, referer: String?): Map<String, String> =
        if (cors) fingerprint.headersCors(url, referer) else fingerprint.headersDocument(url, referer)

    /**
     * GET with retries. Returns the last response even on failure.
     * Python: `Http.get(url, referer=None, tries=3, timeout=20, read=65536)`.
     */
    suspend fun get(
        url: String,
        referer: String? = null,
        tries: Int = 3,
        timeoutMs: Long = 20_000L,
        read: Int = 65_536,
        allowRedirects: Boolean = true
    ): Resp? {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || tripped(host) || !HttpClient.isAllowedUrl(url)) return null
        var last: Resp? = null
        sem.withPermit {
            hs(host).withPermit {
                delay((delaySeconds * Random.nextDouble(0.4, 1.6) * 1000).toLong())
                for (attempt in 0 until tries) {
                    try {
                        val resp = withContext(Dispatchers.IO) { execute(url, referer, read, timeoutMs) }
                        synchronized(lock) { stats[resp.status.toString()] = (stats[resp.status.toString()] ?: 0) + 1 }
                        if (resp.status in listOf(403, 429, 503)) {
                            last = resp
                            if (resp.status == 403 && attempt == 1) solve(url, referer)
                            backoff(attempt, resp.status, resp.headers)
                            continue
                        }
                        return resp
                    } catch (e: IOException) {
                        last = Resp(0, url, emptyMap(), ByteArray(0))
                        delay(((2.0.pow(attempt)) + Random.nextDouble()) * 1000L)
                    }
                }
                trip(host)
                return last
            }
        }
    }

    private fun execute(url: String, referer: String?, read: Int, timeoutMs: Long): Resp {
        val builder = Request.Builder().url(url)
        headers(url, referer).forEach { (k, v) -> builder.header(k, v) }
        auth(url).forEach { (k, v) -> builder.header(k, v) }
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(builder.build())
        call.execute().use { response ->
            val body = response.body
            val bytes = if (body == null) ByteArray(0) else readUpTo(body.source(), read)
            val hdrs = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
            return Resp(response.code, response.request.url.toString(), hdrs, bytes)
        }
    }

    private fun readUpTo(source: okio.BufferedSource, max: Int): ByteArray {
        val buf = Buffer()
        while (buf.size < max && !source.exhausted()) {
            val n = source.read(buf, minOf(max - buf.size, 8192L))
            if (n == -1L) break
        }
        return buf.readByteArray()
    }

    /** Warm cookies for a site and return its final origin (redirect-safe). */
    suspend fun prime(base: String): String? {
        val r = get(base, tries = 2)
        return if (r != null && r.status < 400) FinderHelpers.originOf(r.url) else null
    }

    /**
     * Open a streaming response for the byte-level probers.
     * Returns (status, headers, finalUrl, source) or null when the URL is
     * blocked / the host is tripped. The caller reads [source] chunk by
     * chunk, exactly like `async with session.get(...) as r: r.content.read`.
     */
    suspend fun openStream(
        url: String,
        referer: String?,
        timeoutMs: Long = 25_000L
    ): StreamHandle? {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || tripped(host) || !HttpClient.isAllowedUrl(url)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                headers(url, referer).forEach { (k, v) -> builder.header(k, v) }
                val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(builder.build())
                val response = call.execute()
                val hdrs = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                StreamHandle(response.code, response.request.url.toString(), hdrs, response.body?.source(), response)
            }.getOrNull()
        }
    }

    class StreamHandle(
        val status: Int,
        val url: String,
        val headers: Map<String, String>,
        val source: okio.BufferedSource?,
        private val response: okhttp3.Response
    ) {
        fun close() = runCatching { response.close() }
    }
}
