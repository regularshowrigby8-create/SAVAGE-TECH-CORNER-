package dev.nova.tv.player

import dev.nova.tv.data.model.Stream
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.player.engine.safePlaybackHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What one short read of a stream told us.
 *
 * [bytesPerSecond] is measured over the first [StreamProbe.SAMPLE_BYTES] after
 * the first byte, which is the number that predicts buffering: a host that
 * answers fast but trickles data stalls ten minutes in.
 */
data class ProbeResult(
    val ok: Boolean,
    val status: Int = 0,
    val ttfbMs: Long = -1,
    val bytesPerSecond: Long = -1,
    /** From Content-Range / Content-Length; 0 when the server did not say. */
    val totalBytes: Long = 0
)

/**
 * Anti-buffering, step one: before anything plays, read the first 256 kB of
 * the best candidates in parallel and keep only the ones that are alive, under
 * the size cap and fast enough for their own bitrate.
 *
 * ARVIO warms connections (`warmHttpConnection`) and reorders by host health
 * after the fact; probing first means the stream that starts is the one that
 * will keep playing.
 */
class StreamProbe(baseClient: OkHttpClient) {

    private val client = baseClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, ProbeResult>>()

    suspend fun probe(stream: Stream): ProbeResult {
        val url = stream.url ?: return ProbeResult(ok = false)
        cache[url]?.let { (at, result) -> if (System.currentTimeMillis() - at < CACHE_MS) return result }
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS + 1_500) { read(stream, url) } ?: ProbeResult(ok = false)
        cache[url] = System.currentTimeMillis() to result
        HostHealth.noteProbe(url, result)
        return result
    }

    /** Probe the first [limit] streams, [CONCURRENCY] at a time. */
    suspend fun probeAll(streams: List<Stream>, limit: Int = DEFAULT_LIMIT): Map<String, ProbeResult> = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        streams.take(limit).mapNotNull { s -> s.url?.let { it to s } }.distinctBy { it.first }
            .map { (url, s) -> async { url to gate.withPermit { probe(s) } } }
            .awaitAll().toMap()
    }

    /**
     * ARVIO warmHttpConnection: a one-byte range request so the TLS session
     * and connection are pooled before a failover needs them.
     */
    suspend fun prewarm(stream: Stream) {
        val url = stream.url ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(stream, url, "bytes=0-1")).execute().close()
            }
        }
    }

    private suspend fun read(stream: Stream, url: String): ProbeResult = withContext(Dispatchers.IO) {
        val kind = (FinderRules.classifyStream(stream) as? FinderRules.Verdict.Playable)?.kind
        val adaptive = kind == FinderRules.StreamKind.HLS || kind == FinderRules.StreamKind.DASH
        val started = System.nanoTime()
        runCatching {
            client.newCall(request(stream, url, if (adaptive) null else "bytes=0-${SAMPLE_BYTES - 1}")).execute().use { response ->
                val status = response.code
                if (status !in 200..299) return@use ProbeResult(ok = false, status = status)
                val body = response.body ?: return@use ProbeResult(ok = false, status = status)
                val source = body.source()
                // First byte.
                if (!source.request(1)) return@use ProbeResult(ok = false, status = status)
                val firstByteAt = System.nanoTime()
                val ttfb = (firstByteAt - started) / 1_000_000
                if (adaptive) {
                    // A playlist is small and says nothing about segment speed;
                    // being alive and quick to answer is what matters.
                    return@use ProbeResult(ok = true, status = status, ttfbMs = ttfb)
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/html")) return@use ProbeResult(ok = false, status = status, ttfbMs = ttfb)
                val wanted = if (status == 206) SAMPLE_BYTES.toLong() else minOf(SAMPLE_BYTES.toLong(), body.contentLength().takeIf { it > 0 } ?: SAMPLE_BYTES.toLong())
                source.request(wanted)
                val read = minOf(source.buffer.size, wanted)
                val elapsedNs = (System.nanoTime() - firstByteAt).coerceAtLeast(1_000_000)
                val total = totalFrom(response.header("Content-Range"), if (status == 200) body.contentLength() else -1)
                ProbeResult(
                    ok = true,
                    status = status,
                    ttfbMs = ttfb,
                    bytesPerSecond = if (read >= MIN_SAMPLE_BYTES) read * 1_000_000_000 / elapsedNs else -1,
                    totalBytes = total
                )
            }
        }.getOrElse { ProbeResult(ok = false) }
    }

    private fun request(stream: Stream, url: String, range: String?): Request =
        Request.Builder().url(url).apply {
            stream.requestHeaders.safePlaybackHeaders().forEach { (k, v) -> header(k, v) }
            if (range != null) header("Range", range)
        }.build()

    companion object {
        const val SAMPLE_BYTES = 256 * 1024
        private const val MIN_SAMPLE_BYTES = 32 * 1024L
        const val PROBE_TIMEOUT_MS = 4_000L
        const val CONCURRENCY = 6
        const val DEFAULT_LIMIT = 10
        private const val CACHE_MS = 5 * 60_000L

        /** "bytes 0-262143/1234567" -> 1234567; falls back to Content-Length on a 200. */
        fun totalFrom(contentRange: String?, contentLength: Long): Long {
            val total = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
            return total ?: contentLength.coerceAtLeast(0)
        }
    }
}

/**
 * Per-host track record for this session, after ARVIO's
 * notePlaybackHostSuccess / notePlaybackHostFailure. A host that stalled or
 * failed is tried later; one that started cleanly is tried first.
 */
object HostHealth {
    private data class Record(var ok: Int = 0, var failed: Int = 0, var stalled: Int = 0)

    private val hosts = ConcurrentHashMap<String, Record>()

    fun host(url: String?): String =
        runCatching { java.net.URI(url ?: "").host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()

    private fun record(url: String?) = hosts.getOrPut(host(url)) { Record() }

    fun noteSuccess(url: String?) = synchronized(this) { record(url).ok++ }
    fun noteFailure(url: String?) = synchronized(this) { record(url).failed++ }
    fun noteStall(url: String?) = synchronized(this) { record(url).stalled++ }

    fun noteProbe(url: String, result: ProbeResult) {
        if (!result.ok) noteFailure(url)
    }

    /** Positive is good. Bounded, so one host cannot dominate the ranking forever. */
    fun score(url: String?): Int = synchronized(this) {
        val r = hosts[host(url)] ?: return 0
        (r.ok * 10 - r.failed * 25 - r.stalled * 15).coerceIn(-60, 30)
    }

    fun reset() = hosts.clear()
}
