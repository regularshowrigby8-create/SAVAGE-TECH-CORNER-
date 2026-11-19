@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.player.preload

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Collections

/**
 * One disk cache for the whole process. The player reads through it always;
 * the preloader fills it ahead of the playhead when switched on. Least
 * recently used fragments go first, so watched parts make room for new ones.
 */
object PreloadCache {
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: run {
            val dir = File(context.cacheDir, "nova-preload").apply { mkdirs() }
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(budgetBytes(dir)), StandaloneDatabaseProvider(context))
                .also { instance = it }
        }
    }

    /** 2 GiB, but never more than a third of the free space; at least 256 MiB. */
    internal fun budgetBytes(dir: File): Long {
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
        return budgetFor(free)
    }

    internal fun budgetFor(freeBytes: Long): Long =
        (freeBytes / 3).coerceIn(MIN_BYTES, MAX_BYTES)

    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val MIN_BYTES = 256L * 1024 * 1024
}

/**
 * Pre-downloads the next [FragmentPlan.AHEAD_MS] of a direct file into
 * [PreloadCache], one connection ([PreloadMode.ON]) or several
 * ([PreloadMode.MULTI], governed by [WorkerGovernor]). The player reads the
 * same cache, so a network dip is absorbed by minutes of video on disk
 * instead of ExoPlayer's in-memory buffer.
 *
 * HLS and DASH are left to ExoPlayer: their segments are separate files the
 * player already fetches ahead.
 */
class FragmentPreloader(
    private val cache: Cache,
    client: OkHttpClient,
    private val userAgent: String
) {
    data class Status(
        val mode: PreloadMode = PreloadMode.OFF,
        val active: Boolean = false,
        val aheadMs: Long = 0,
        val workers: Int = 0,
        val bytesPerSecond: Long = 0,
        /** The stream cannot be preloaded (not a direct file, no byte ranges, or the host refused). */
        val unavailable: Boolean = false
    )

    private val upstreamClient = client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Updated by the player from the main thread; read by the workers. */
    @Volatile var playheadMs: Long = 0
    @Volatile var durationMs: Long = 0

    private val inflight = Collections.synchronizedSet(HashSet<Long>())

    /** Show [mode] as chosen but not possible for this stream. */
    fun unavailable(mode: PreloadMode) {
        stop()
        _status.value = Status(mode = mode, unavailable = mode != PreloadMode.OFF)
    }

    /** Show [mode] as chosen, waiting for playback to start. */
    fun waiting(mode: PreloadMode) {
        stop()
        _status.value = Status(mode = mode)
    }

    fun start(mode: PreloadMode, url: String, headers: Map<String, String>, knownTotalBytes: Long) {
        stop()
        _status.value = Status(mode = mode)
        if (mode == PreloadMode.OFF) return
        val factory = OkHttpDataSource.Factory(upstreamClient).setUserAgent(userAgent).setDefaultRequestProperties(headers)
        val governor = WorkerGovernor(mode.workers)
        job = scope.launch {
            // Fragments need byte ranges; a host that ignores Range would resend the whole file each time.
            val probed = rangeTotal(url, headers)
            if (probed == null) {
                _status.value = Status(mode = mode, unavailable = true)
                return@launch
            }
            var total = knownTotalBytes.takeIf { it > 0 } ?: probed
            if (total <= 0) total = ContentMetadata.getContentLength(cache.getContentMetadata(url)).coerceAtLeast(0)
            if (total <= 0) {
                _status.value = Status(mode = mode, unavailable = true)
                return@launch
            }
            while (isActive && durationMs <= 0) delay(500)
            _status.value = _status.value.copy(active = true, workers = governor.workers)

            val running = ArrayList<Job>()
            var roundBytes = 0L
            var roundStarted = System.nanoTime()
            var roundDone = 0
            var failures = 0
            var giveUp = false
            val lock = Any()
            while (isActive && !synchronized(lock) { giveUp }) {
                running.removeAll { !it.isActive }
                while (running.size < governor.workers) {
                    val next = nextFragment(url, total) ?: break
                    running += launch {
                        val len = FragmentPlan.fragmentLength(next, total)
                        val ok = download(factory, url, next, len)
                        inflight.remove(next)
                        synchronized(lock) {
                            if (!ok) {
                                governor.onFailure()
                                if (++failures >= MAX_FAILURES) {
                                    _status.value = Status(mode = mode, unavailable = true)
                                    giveUp = true
                                }
                                return@synchronized
                            }
                            failures = 0
                            roundBytes += len
                            roundDone++
                            if (roundDone >= governor.workers) {
                                val seconds = (System.nanoTime() - roundStarted) / 1e9
                                val speed = if (seconds > 0) roundBytes / seconds else 0.0
                                governor.onRound(speed)
                                _status.value = _status.value.copy(workers = governor.workers, bytesPerSecond = speed.toLong())
                                roundBytes = 0; roundDone = 0; roundStarted = System.nanoTime()
                            }
                        }
                    }
                }
                if (synchronized(lock) { giveUp }) break
                _status.value = _status.value.copy(aheadMs = aheadOf(url, total))
                delay(if (running.isEmpty()) IDLE_MS else TICK_MS)
            }
            running.forEach { it.cancel() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        inflight.clear()
        _status.value = _status.value.copy(active = false, workers = 0, bytesPerSecond = 0)
    }

    fun release() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    /** First fragment in the window that is neither on disk nor being fetched. */
    private fun nextFragment(url: String, total: Long): Long? {
        for (start in FragmentPlan.window(playheadMs, durationMs, total)) {
            val len = FragmentPlan.fragmentLength(start, total)
            if (len <= 0) continue
            if (start in inflight) continue
            if (cache.isCached(url, start, len)) continue
            if (inflight.add(start)) return start
        }
        return null
    }

    private fun aheadOf(url: String, total: Long): Long {
        val from = FragmentPlan.byteAt(playheadMs, total, durationMs)
        val cached = cache.getCachedLength(url, from, total - from).coerceAtLeast(0)
        return FragmentPlan.aheadMs(cached, total, durationMs)
    }

    /**
     * `Range: bytes=0-0` must come back 206 with a total in Content-Range.
     * Returns the total, 0 when ranges work but the total is not stated, or
     * null when ranges are not supported.
     */
    private suspend fun rangeTotal(url: String, headers: Map<String, String>): Long? = runInterruptible {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", userAgent)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        runCatching {
            upstreamClient.newCall(request).execute().use { response ->
                if (response.code != 206) return@use null
                parseContentRangeTotal(response.header("Content-Range")) ?: 0L
            }
        }.getOrNull()
    }

    private suspend fun download(factory: OkHttpDataSource.Factory, url: String, start: Long, length: Long): Boolean {
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(start).setLength(length).setKey(url).build()
        val source = CacheDataSource(cache, factory.createDataSource())
        val writer = CacheWriter(source, spec, null, null)
        return try {
            runInterruptible { writer.cache() }
            true
        } catch (e: CancellationException) {
            writer.cancel()
            throw e
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val IDLE_MS = 1_000L
        private const val MAX_FAILURES = 5

        /** "bytes 0-0/123456" gives 123456; an unknown total ("bytes 0-0/" + "*") gives null. */
        internal fun parseContentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
