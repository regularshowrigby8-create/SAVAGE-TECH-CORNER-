package dev.nova.tv.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/**
 * One offline download. [connections] is how many HTTP range requests were
 * used; 1 means the server ignored ranges and the file came down in one piece.
 */
data class DownloadJob(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val path: String = "",
    val totalBytes: Long = 0,
    val bytes: Long = 0,
    val connections: Int = 1,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String = ""
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * Multithreaded downloader for direct HTTP sources, the only kind Nova plays.
 *
 * A file that advertises range support is fetched over [CONNECTIONS] parallel
 * requests, each writing its own slice of the same `.part` file, so a slow
 * single connection is not the ceiling. Servers that ignore ranges fall back
 * to one stream. Files are stored in app-specific external storage, which
 * needs no permission, and are only renamed into place when complete.
 */
class DownloadManager(
    private val dir: File,
    private val client: OkHttpClient,
    private val scope: CoroutineScope
) {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val tasks = HashMap<String, Job>()

    /** Returns the new job id, or null for a duplicate or non-HTTP url. */
    fun start(title: String, subtitle: String, url: String, headers: Map<String, String>): String? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        if (_jobs.value.any { it.url == url && it.status != DownloadStatus.DONE }) return null
        val id = UUID.randomUUID().toString().take(8)
        val job = DownloadJob(
            id = id,
            title = title.ifBlank { "Nova download" },
            subtitle = subtitle,
            url = url,
            headers = headers,
            path = File(dir, fileName(title, url, id)).absolutePath
        )
        _jobs.update { it + job }
        launch(job)
        return id
    }

    fun pause(id: String) {
        tasks[id]?.cancel()
        update(id) { it.copy(status = DownloadStatus.PAUSED) }
    }

    fun resume(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.QUEUED) return
        launch(job)
    }

    fun remove(id: String) {
        tasks.remove(id)?.cancel()
        val job = _jobs.value.firstOrNull { it.id == id }
        if (job != null) {
            runCatching { File(job.path).delete() }
            runCatching { File(job.path + ".part").delete() }
        }
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    private fun launch(job: DownloadJob) {
        tasks[job.id]?.cancel()
        tasks[job.id] = scope.launch(Dispatchers.IO) { download(job) }
    }

    private suspend fun download(job: DownloadJob) {
        update(job.id) { it.copy(status = DownloadStatus.RUNNING, error = "", bytes = 0) }
        val target = File(job.path)
        val part = File(job.path + ".part")
        try {
            dir.mkdirs()
            val (total, ranges) = probe(job)
            val connections = if (ranges && total > 0) CONNECTIONS else 1
            update(job.id) { it.copy(totalBytes = if (total > 0) total else it.totalBytes, connections = connections) }
            if (connections > 1) rangeDownload(job, part, total) else streamDownload(job, part)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            update(job.id) { it.copy(status = DownloadStatus.DONE, bytes = maxOf(it.bytes, it.totalBytes)) }
        } catch (cancelled: CancellationException) {
            update(job.id) { it.copy(status = DownloadStatus.PAUSED) }
            throw cancelled
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            update(job.id) { it.copy(status = DownloadStatus.FAILED, error = e.message?.take(140) ?: "Download failed") }
        } finally {
            tasks.remove(job.id)
        }
    }

    /** Total size and whether the server honours Range requests. */
    private fun probe(job: DownloadJob): Pair<Long, Boolean> {
        val request = request(job).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val fromRange = totalFrom(response.header("Content-Range"))
            val total = fromRange ?: response.body?.contentLength() ?: -1L
            // The same 5 GB ceiling the finders enforce: an offline copy of a
            // file Nova would refuse to stream is just wasted storage.
            if (total > FinderRules.MAX_FILE_BYTES) {
                throw IOException("File is larger than 5 GB")
            }
            // 206 means the range was honoured; a 200 means it was not.
            return (if (total > 0) total else 0L) to (response.code == 206 && total > 0)
        }
    }

    private suspend fun rangeDownload(job: DownloadJob, part: File, total: Long) = coroutineScope {
        val written = AtomicLongArray(CONNECTIONS)
        val lastPublish = AtomicLong(0)
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val chunk = (total + CONNECTIONS - 1) / CONNECTIONS
            (0 until CONNECTIONS).map { i ->
                async(Dispatchers.IO) {
                    val start = i * chunk
                    val end = minOf(total - 1, start + chunk - 1)
                    if (start > end) return@async
                    var attempt = 0
                    while (true) {
                        try {
                            // Reset this slice's counter: a retry rewrites from
                            // the same offset and must not double-count bytes.
                            written.set(i, 0L)
                            val request = request(job).header("Range", "bytes=$start-$end").build()
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                                val body = response.body ?: throw IOException("Empty body")
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(BUFFER_BYTES)
                                    var offset = start
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        synchronized(file) {
                                            file.seek(offset)
                                            file.write(buffer, 0, read)
                                        }
                                        offset += read
                                        written.set(i, offset - start)
                                        publishProgress(job, written, lastPublish)
                                    }
                                }
                            }
                            return@async
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: IOException) {
                            // A single flaky connection must not fail the whole
                            // file while five other slices are still running.
                            attempt++
                            if (attempt >= MAX_ATTEMPTS) throw e
                            delay(RETRY_MS * attempt)
                        }
                    }
                }
            }.awaitAll()
        }
        publishProgress(job, written, lastPublish, force = true)
    }

    private fun streamDownload(job: DownloadJob, part: File) {
        val request = request(job).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            val total = body.contentLength()
            if (total > 0) update(job.id) { it.copy(totalBytes = total) }
            part.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var bytes = 0L
                    var last = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        val now = System.currentTimeMillis()
                        if (now - last > PROGRESS_MS) {
                            last = now
                            update(job.id) { it.copy(bytes = bytes) }
                        }
                    }
                    update(job.id) { it.copy(bytes = bytes) }
                }
            }
        }
    }

    private fun publishProgress(job: DownloadJob, written: AtomicLongArray, lastPublish: AtomicLong, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish.get() < PROGRESS_MS) return
        lastPublish.set(now)
        var sum = 0L
        for (i in 0 until written.length()) sum += written.get(i)
        update(job.id) { it.copy(bytes = sum) }
    }

    private fun request(job: DownloadJob): Request.Builder {
        val builder = Request.Builder().url(job.url).header("User-Agent", USER_AGENT)
        job.headers.forEach { (key, value) -> runCatching { builder.header(key, value) } }
        return builder
    }

    private fun update(id: String, block: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    private fun fileName(title: String, url: String, id: String): String {
        val cleaned = title.replace(Regex("[^A-Za-z0-9._ -]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(70)
            .ifBlank { "nova-download" }
        val extension = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in EXTENSIONS } ?: "mp4"
        return "$cleaned-$id.$extension"
    }

    companion object {
        /** Parallel range requests per file. Six saturates a typical phone link. */
        const val CONNECTIONS = 6
        private const val BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_MS = 250L
        /** A slice is retried this many times before the file is failed. */
        private const val MAX_ATTEMPTS = 3
        /** Linear backoff between slice retries. */
        private const val RETRY_MS = 600L
        private const val USER_AGENT = "Nova/1.0 (Android)"
        private val EXTENSIONS = setOf("mp4", "mkv", "webm", "m4v", "mov", "avi", "ts")

        /** "bytes 0-0/12345" -> 12345. */
        fun totalFrom(contentRange: String?): Long? =
            contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
