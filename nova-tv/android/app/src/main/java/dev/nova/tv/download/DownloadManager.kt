package dev.nova.tv.download

import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/**
 * One offline download. [connections] is how many HTTP range requests were
 * used; 1 means the server ignored ranges and the file came down in one piece.
 */
@Serializable
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
    val error: String = "",
    /** Bytes already on disk per range slice, so a stopped download resumes. */
    val slices: List<Long> = emptyList()
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * Multithreaded, resumable downloader for direct HTTP sources, the only kind
 * Nova plays.
 *
 * A file that advertises range support is fetched over [CONNECTIONS] parallel
 * requests, each writing its own slice of the same `.part` file, so a slow
 * single connection is not the ceiling. Servers that ignore ranges fall back
 * to one stream. Files are stored in app-specific external storage, which
 * needs no permission, and are only renamed into place when complete.
 *
 * Three things used to stop a download mid-file, and all three are fixed here:
 *
 *  - the shared OkHttp client has `callTimeout(30s)`, which killed any transfer
 *    longer than half a minute. Downloads get their own client with no
 *    whole-call deadline, only a per-read stall timeout.
 *  - one failed request failed the whole job. Each attempt and each slice now
 *    retries with backoff, resuming from the bytes already written.
 *  - the job list lived only in memory, so a restart forgot the partial file.
 *    It is persisted beside the downloads and restored as PAUSED.
 */
class DownloadManager(
    private val dir: File,
    baseClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    private val client = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    /** Touched from the main thread and from IO; completed entries remove themselves. */
    private val tasks = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * The job list outlives the process. Without this file a relaunch showed an
     * empty Downloads screen and the partial bytes sat on disk with no record.
     */
    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(dir, "downloads.json")
    private val saveLock = Any()
    @Volatile private var lastSave = 0L

    init { restore() }

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
        save(force = true)
        launch(job)
        return id
    }

    fun pause(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.status == DownloadStatus.DONE) return
        tasks[id]?.cancel()
        update(id, force = true) { it.copy(status = DownloadStatus.PAUSED) }
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
        save(force = true)
    }

    private fun launch(job: DownloadJob) {
        tasks[job.id]?.cancel()
        val task = scope.launch(Dispatchers.IO) { download(job) }
        tasks[job.id] = task
        // Only drop the entry if it is still this attempt: pause + resume can
        // start the next attempt before the cancelled one finishes unwinding.
        task.invokeOnCompletion { if (tasks[job.id] === task) tasks.remove(job.id) }
    }

    /**
     * One job, retried until it completes, the viewer cancels it, or
     * [MAX_ATTEMPTS] attempts have failed. Every attempt resumes from the
     * bytes already on disk (or the per-slice offsets), so a dropped
     * connection, a stalled read or a host that briefly answers 5xx continues
     * the file instead of ending the download.
     */
    private suspend fun download(job: DownloadJob) {
        val target = File(job.path)
        val part = File(job.path + ".part")
        var attempt = 0
        while (true) {
            val current = _jobs.value.firstOrNull { it.id == job.id } ?: return
            if (current.status == DownloadStatus.DONE) return
            // Resume where the previous attempt stopped instead of restarting at 0.
            val onDisk = bytesOnDisk(current, part)
            update(current.id, force = true) {
                it.copy(status = DownloadStatus.RUNNING, error = "", bytes = onDisk)
            }
            try {
                attemptTransfer(current, target, part)
                update(current.id, force = true) {
                    it.copy(status = DownloadStatus.DONE, bytes = maxOf(it.bytes, it.totalBytes), slices = emptyList(), error = "")
                }
                return
            } catch (cancelled: CancellationException) {
                update(current.id, force = true) { it.copy(status = DownloadStatus.PAUSED) }
                throw cancelled
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                attempt++
                if (attempt >= MAX_ATTEMPTS) {
                    update(current.id, force = true) {
                        it.copy(status = DownloadStatus.FAILED, error = e.message?.take(140) ?: "Download failed")
                    }
                    return
                }
                update(current.id, force = true) {
                    it.copy(error = "Retrying ${attempt + 1}/$MAX_ATTEMPTS")
                }
                delay(retryDelayMs(attempt))
            }
        }
    }

    /** One complete transfer attempt. Throws so [download] can retry it. */
    private suspend fun attemptTransfer(job: DownloadJob, target: File, part: File) {
        dir.mkdirs()
        val (total, ranges) = probe(job)
        val connections = if (ranges && total > 0) CONNECTIONS else 1
        // Slice offsets only describe this attempt if its shape did not change;
        // a host that stopped honouring ranges starts over.
        val slices = if (connections > 1 && job.slices.size == connections) job.slices else emptyList()
        update(job.id, force = true) {
            it.copy(
                totalBytes = if (total > 0) total else it.totalBytes,
                connections = connections,
                slices = slices,
                bytes = if (slices.isNotEmpty()) slices.sum() else it.bytes
            )
        }
        if (connections > 1) {
            rangeDownload(job, part, total, slices)
        } else {
            // A previous ranged attempt pre-allocated the file to its full
            // length; a single stream must resume from the real bytes.
            if (job.connections > 1 && part.isFile) {
                RandomAccessFile(part, "rw").use { it.setLength(job.slices.sum()) }
            }
            streamDownload(job, part)
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** How far a previous attempt got, before any network call. */
    private fun bytesOnDisk(job: DownloadJob, part: File): Long {
        if (!part.isFile) return 0L
        return if (job.slices.isNotEmpty()) job.slices.sum() else if (job.connections <= 1) part.length() else 0L
    }

    /**
     * Total size and whether the server honours Range requests.
     *
     * A host may refuse the one-byte probe (405, 416, a blocked range) and
     * still serve the whole file. That used to fail the job before a single
     * byte was written; now it falls back to one stream.
     */
    private fun probe(job: DownloadJob): Pair<Long, Boolean> {
        return try {
            val request = request(job).header("Range", "bytes=0-0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return job.totalBytes.coerceAtLeast(0L) to false
                val fromRange = totalFrom(response.header("Content-Range"))
                val total = fromRange ?: response.body?.contentLength() ?: -1L
                // The same 5 GB ceiling the finders enforce: an offline copy of
                // a file Nova would refuse to stream is just wasted storage.
                if (total > FinderRules.MAX_FILE_BYTES) {
                    throw IOException("File is larger than 5 GB")
                }
                // 206 means the range was honoured; a 200 means it was not.
                (if (total > 0) total else 0L) to (response.code == 206 && total > 0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: IOException) {
            if (tooLarge.message?.contains("larger than 5 GB") == true) throw tooLarge
            job.totalBytes.coerceAtLeast(0L) to false
        } catch (e: Exception) {
            job.totalBytes.coerceAtLeast(0L) to false
        }
    }

    private suspend fun rangeDownload(
        job: DownloadJob,
        part: File,
        total: Long,
        slices: List<Long> = emptyList()
    ) = coroutineScope {
        val written = AtomicLongArray(CONNECTIONS)
        slices.forEachIndexed { i, done -> if (i < CONNECTIONS) written.set(i, done.coerceAtLeast(0)) }
        val lastPublish = AtomicLong(0)
        RandomAccessFile(part, "rw").use { file ->
            if (file.length() != total) file.setLength(total)
            val chunk = (total + CONNECTIONS - 1) / CONNECTIONS
            (0 until CONNECTIONS).map { i ->
                async(Dispatchers.IO) {
                    val start = i * chunk
                    val end = minOf(total - 1, start + chunk - 1)
                    if (start > end) return@async
                    // Resume this slice where the previous attempt stopped.
                    val from = resumeOffset(start, end, written.get(i))
                    if (from > end) return@async
                    val request = request(job).header("Range", "bytes=$from-$end").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        // The probe saw 206 for bytes=0-0, but a host may answer a
                        // later range with the whole file. Writing that body here
                        // would corrupt the download and still mark it DONE.
                        if (response.code != 206) throw IOException("Range ignored (HTTP ${response.code})")
                        val body = response.body ?: throw IOException("Empty body")
                        val expected = end - from + 1
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var offset = from
                            while (offset <= end) {
                                // Never write past this slice, however much the
                                // server sends.
                                val room = (end - offset + 1).coerceAtMost(buffer.size.toLong()).toInt()
                                val read = input.read(buffer, 0, room)
                                if (read < 0) break
                                synchronized(file) {
                                    file.seek(offset)
                                    file.write(buffer, 0, read)
                                }
                                offset += read
                                written.addAndGet(i, read.toLong())
                                publishProgress(job, written, lastPublish)
                            }
                            // A body that ends early leaves a zero-filled tail. Fail
                            // so the job is retried instead of renamed into place.
                            if (offset != end + 1) {
                                throw IOException("Short range response (${offset - from}/$expected bytes)")
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        publishProgress(job, written, lastPublish, force = true)
    }

    private fun streamDownload(job: DownloadJob, part: File) {
        // A single stream has no slice map: its length on disk is the resume point.
        val have = if (part.isFile) part.length() else 0L
        val builder = request(job)
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            // 206 means the bytes on disk were kept; 200 means the host ignored
            // the range and the file starts again from zero.
            val resuming = have > 0 && response.code == 206
            val total = totalFrom(response.header("Content-Range"))
                ?: body.contentLength().takeIf { it > 0 }?.plus(if (resuming) have else 0L)
                ?: -1L
            if (total > FinderRules.MAX_FILE_BYTES) throw IOException("File is larger than 5 GB")
            if (total > 0) update(job.id) { it.copy(totalBytes = total) }
            // Appending keeps the bytes a previous attempt already wrote;
            // not resuming truncates the file back to zero.
            java.io.FileOutputStream(part, resuming).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var bytes = if (resuming) have else 0L
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
        val slices = ArrayList<Long>(written.length())
        for (i in 0 until written.length()) {
            val done = written.get(i)
            sum += done
            slices += done
        }
        update(job.id) { it.copy(bytes = sum, slices = slices) }
    }

    private fun request(job: DownloadJob): Request.Builder {
        val builder = Request.Builder().url(job.url).header("User-Agent", USER_AGENT)
        job.headers.forEach { (key, value) -> runCatching { builder.header(key, value) } }
        return builder
    }

    private fun update(id: String, force: Boolean = false, block: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) block(it) else it } }
        save(force)
    }

    /** Reload the job list from disk. Nothing resumes until the viewer asks. */
    private fun restore() {
        val stored = runCatching {
            if (storeFile.isFile) json.decodeFromString(ListSerializer(DownloadJob.serializer()), storeFile.readText()) else null
        }.getOrNull().orEmpty()
        if (stored.isEmpty()) return
        _jobs.value = stored.map { job ->
            val target = File(job.path)
            when {
                // A finished file that the viewer deleted is not still finished.
                job.status == DownloadStatus.DONE && !target.isFile ->
                    job.copy(status = DownloadStatus.FAILED, error = "File removed")
                job.status == DownloadStatus.DONE -> job.copy(bytes = maxOf(job.bytes, job.totalBytes))
                else -> {
                    // Bytes only exist while the .part file does, and a job that
                    // was mid-flight when the process died comes back paused.
                    val part = File(job.path + ".part")
                    val slices = if (part.isFile) job.slices else emptyList()
                    job.copy(
                        status = when (job.status) {
                            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> DownloadStatus.PAUSED
                            else -> job.status
                        },
                        slices = slices,
                        bytes = when {
                            slices.isNotEmpty() -> slices.sum()
                            !part.isFile -> 0L
                            else -> job.bytes
                        }
                    )
                }
            }
        }
    }

    /** Persist the list, throttled so progress updates do not hammer the disk. */
    private fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(saveLock) {
            if (!force && now - lastSave < SAVE_MS) return
            lastSave = now
            val text = runCatching {
                json.encodeToString(ListSerializer(DownloadJob.serializer()), _jobs.value)
            }.getOrNull() ?: return
            runCatching {
                dir.mkdirs()
                val tmp = File(storeFile.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(storeFile)) {
                    storeFile.writeText(text)
                    tmp.delete()
                }
            }
        }
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
        /** Transient failures before a job is marked FAILED; each retry resumes. */
        const val MAX_ATTEMPTS = 6
        private const val BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_MS = 250L
        /** Progress writes are throttled to this; state changes are not. */
        private const val SAVE_MS = 1_500L
        private const val USER_AGENT = "Nova/1.0 (Android)"
        private val EXTENSIONS = setOf("mp4", "mkv", "webm", "m4v", "mov", "avi", "ts")

        /** 1s, 2s, 4s, 8s, 16s, capped at 30s. */
        fun retryDelayMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(30_000L)

        /**
         * Where slice [sliceStart]..[sliceEnd] must restart, given [done] bytes
         * already written. [sliceEnd] + 1 means the slice is complete.
         */
        fun resumeOffset(sliceStart: Long, sliceEnd: Long, done: Long): Long =
            sliceStart + done.coerceIn(0L, sliceEnd - sliceStart + 1)

        /** "bytes 0-0/12345" -> 12345. */
        fun totalFrom(contentRange: String?): Long? =
            contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    }
}
