package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarvesterConcurrencyTest {
    private class ManyRequests : ServerHarvester.Source {
        override val id = "fake"
        override val label = "Fake"
        private var done = false
        override fun isDone() = done
        override fun backoffUntil() = 0L
        override suspend fun sweep(ctx: ServerHarvester.SweepContext): List<FinderCandidate> = coroutineScope {
            (0 until 18).map { i -> async { ctx.get("https://same.example/$i", this@ManyRequests) } }.awaitAll()
            done = true
            emptyList()
        }
    }

    @Test fun `shared harvester gate enforces a real per host limit`() = runTest {
        var active = 0
        var peak = 0
        val harvester = ServerHarvester(HttpClient(), listOf(ManyRequests())) { _, _, _ ->
            active++
            peak = maxOf(peak, active)
            try { delay(20); "{}" } finally { active-- }
        }
        harvester.continuousFill(poolSize = { 0 }, onBatch = {})
        assertEquals(ServerHarvester.HOST_CONCURRENCY, peak)
        assertTrue(peak <= ServerHarvester.GLOBAL_CONCURRENCY)
        assertEquals(18, harvester.state.value.requests)
        assertEquals(0, harvester.state.value.failures)
    }

    @Test fun `rate limited sources cool down instead of immediately being retried`() = runTest {
        var calls = 0
        val source = object : ServerHarvester.Source {
            override val id = "limited"
            override val label = "Limited"
            override fun isDone() = false
            override fun backoffUntil() = 0L
            override suspend fun sweep(ctx: ServerHarvester.SweepContext): List<FinderCandidate> {
                ctx.get("https://limit.example/feed", this)
                return emptyList()
            }
        }
        val harvester = ServerHarvester(HttpClient(), listOf(source)) { _, _, _ ->
            calls++
            throw HttpClient.HttpFailure("rate limited", 429)
        }
        harvester.harvestOnce()
        harvester.harvestOnce()
        assertEquals(1, calls)
        assertEquals(1, harvester.state.value.rateLimited)
    }
}
