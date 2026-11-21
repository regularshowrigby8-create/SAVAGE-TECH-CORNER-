package dev.nova.tv.data.remote

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpCancellationTest {
    @Test fun `cancelling a request cancels the underlying okhttp call`() = runBlocking {
        val enqueued = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = object : Call {
                override fun request(): Request = request
                override fun execute(): Response = error("not used")
                override fun enqueue(responseCallback: Callback) { enqueued.countDown() }
                override fun cancel() { cancelled.countDown() }
                override fun isExecuted(): Boolean = enqueued.count == 0L
                override fun isCanceled(): Boolean = cancelled.count == 0L
                override fun timeout(): Timeout = Timeout()
                override fun clone(): Call = this
            }
        }
        val http = HttpClient(callFactory = factory)
        val job = launch { http.get("https://example.com/slow", attempts = 1) }
        assertTrue(withContext(Dispatchers.IO) { enqueued.await(2, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
