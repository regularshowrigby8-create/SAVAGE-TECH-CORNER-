package dev.nova.tv.download

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A download that stops must continue from where it stopped, not from zero. */
class DownloadResumeTest {

    @Test
    fun `a slice resumes after the bytes already written`() {
        assertEquals(1000L, DownloadManager.resumeOffset(1000L, 1999L, 0L))
        assertEquals(1250L, DownloadManager.resumeOffset(1000L, 1999L, 250L))
    }

    @Test
    fun `a completed slice restarts past its end`() {
        assertEquals(2000L, DownloadManager.resumeOffset(1000L, 1999L, 1_000L))
    }

    @Test
    fun `offsets outside the slice are clamped`() {
        assertEquals(1000L, DownloadManager.resumeOffset(1000L, 1999L, -5L))
        assertEquals(2000L, DownloadManager.resumeOffset(1000L, 1999L, 99_999L))
    }

    @Test
    fun `retry backoff grows and is capped`() {
        assertEquals(1_000L, DownloadManager.retryDelayMs(1))
        assertEquals(2_000L, DownloadManager.retryDelayMs(2))
        assertEquals(8_000L, DownloadManager.retryDelayMs(4))
        assertEquals(30_000L, DownloadManager.retryDelayMs(9))
        assertTrue(DownloadManager.MAX_ATTEMPTS > 1)
    }

    @Test
    fun `jobs and their slice offsets survive a round trip to disk`() {
        val json = Json { ignoreUnknownKeys = true }
        val job = DownloadJob(
            id = "abcd1234",
            title = "Some Film",
            subtitle = "1080p",
            url = "https://example.com/film.mp4",
            headers = mapOf("Referer" to "https://example.com"),
            path = "/tmp/film.mp4.part",
            totalBytes = 10_000L,
            bytes = 4_500L,
            connections = 4,
            status = DownloadStatus.PAUSED,
            slices = listOf(1_500L, 1_000L, 2_000L, 0L)
        )
        val text = json.encodeToString(ListSerializer(DownloadJob.serializer()), listOf(job))
        val back = json.decodeFromString(ListSerializer(DownloadJob.serializer()), text).single()
        assertEquals(job, back)
    }
}
