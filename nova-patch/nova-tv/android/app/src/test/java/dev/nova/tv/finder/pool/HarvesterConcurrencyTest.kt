package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

    @Test fun `failed directory page is retried before advancing`() = runTest {
        val directory = ServerHarvester(HttpClient()).sources.first { it.id == "stremio-addons.net" }
        val requests = mutableListOf<String>()
        val harvester = ServerHarvester(HttpClient(), listOf(directory)) { url, _, _ ->
            requests += url
            if (requests.size == 1) throw IOException("offline")
            """{"addons":[]}"""
        }
        harvester.harvestOnce()
        harvester.harvestOnce()
        assertEquals(2, requests.size)
        assertEquals(requests.first(), requests.last())
        assertTrue("page=1" in requests.last())
        assertEquals(1, harvester.state.value.failures)
    }

    @Test fun `failed requests cannot be reported as a complete pass`() = runTest {
        val source = object : ServerHarvester.Source {
            override val id = "once"
            override val label = "Once"
            private var done = false
            override fun isDone() = done
            override fun backoffUntil() = 0L
            override suspend fun sweep(ctx: ServerHarvester.SweepContext): List<FinderCandidate> {
                ctx.get("https://offline.example/feed", this)
                done = true
                return emptyList()
            }
        }
        val harvester = ServerHarvester(HttpClient(), listOf(source)) { _, _, _ -> throw IOException("offline") }
        harvester.continuousFill(poolSize = { 0 }, onBatch = {})
        assertFalse(harvester.state.value.exhausted)
        assertEquals("pass finished with failed requests", harvester.state.value.stopped)
        assertEquals(1, harvester.state.value.failures)
    }

    @Test fun `harvester follows declared addon catalog endpoints rather than manifest URLs`() = runTest {
        val graph = ServerHarvester(HttpClient()).sources.first { it.id == "cinemeta-graph" }
        val requests = mutableListOf<String>()
        val harvester = ServerHarvester(HttpClient(), listOf(graph)) { url, _, _ ->
            requests += url
            when {
                url.contains("community.json") -> """{"addons":[{"transportUrl":"https://graph.example/manifest.json",
                    "manifest":{"resources":["addon_catalog"],"addonCatalogs":[{"type":"all","id":"more"}]}}]}"""
                url.contains("official.json") -> """{"addons":[]}"""
                url == "https://graph.example/addon_catalog/all/more.json" -> """{"addons":[{"transportUrl":"https://found.example/manifest.json",
                    "manifest":{"id":"found","name":"Found","resources":["stream"],"types":["movie"]}}]}"""
                else -> error("unexpected $url")
            }
        }
        harvester.harvestOnce()
        harvester.harvestOnce()
        val nested = harvester.harvestOnce()
        assertTrue(requests.contains("https://graph.example/addon_catalog/all/more.json"))
        assertEquals("https://found.example", nested.single().baseUrl)
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
