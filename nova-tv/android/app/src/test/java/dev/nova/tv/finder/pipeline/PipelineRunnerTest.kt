package dev.nova.tv.finder.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic capacity proof for the shared pipeline.
 *
 * The fake transport returns registry-shaped public-index bodies and
 * catalogue/server pages for 10,000 distinct generated hosts. It does not claim
 * those hosts exist on the live internet; it proves the Kotlin stages can
 * discover, sort, look at, extract, probe, verify and store at the required
 * scale without a false-green report. CI executes this for both flavours.
 */
class PipelineRunnerTest {
    private val sites = List(MIN_SITE_TARGET) { index ->
        "site${index.toString().padStart(5, '0')}.example"
    }

    private class FixtureTransport(
        private val kind: PipelineKind,
        private val sites: List<String>,
        private val seedMarker: String
    ) : PipelineTransport {
        override suspend fun fetch(url: String, referer: String?, read: Int): TransportResult {
            if (EngineRegistry.isEngineUrl(url)) {
                val body = if (seedMarker in url) {
                    sites.joinToString("\n") { "https://$it/" }
                } else {
                    ""
                }
                return TransportResult(url, 200, body, "application/json")
            }
            val host = PipelineText.hostOf(url)
            val path = PipelineText.pathOf(url).lowercase()
            if (url.contains("?s=")) {
                return TransportResult(
                    url, 200,
                    "<h1>The Shawshank Redemption</h1><h1>Game of Thrones</h1><h1>Cowboy Bebop</h1>",
                    "text/html"
                )
            }
            if (kind == PipelineKind.ADDON && path.endsWith("manifest.json")) {
                return TransportResult(
                    url, 200,
                    "{\"id\":\"nova-fixture\",\"resources\":[\"catalog\",\"stream\"],\"types\":[\"movie\"],\"catalogs\":[{\"type\":\"movie\",\"id\":\"top\"}]}",
                    "application/json"
                )
            }
            if (kind == PipelineKind.ADDON && (path.contains("/catalog/") || path.contains("/stream/"))) {
                return TransportResult(
                    url, 200,
                    catalogueHtml() + "<p>tt0111161</p>",
                    "application/json"
                )
            }
            return TransportResult(url, 200, siteHtml(host), "text/html")
        }

        override suspend fun render(url: String, referer: String?): TransportResult {
            val host = PipelineText.hostOf(url)
            return TransportResult(
                url = url,
                status = 200,
                body = siteHtml(host),
                contentType = "text/html",
                network = listOf("https://$host/hls/master.m3u8")
            )
        }

        override suspend fun probe(url: String, referer: String?): PipelineProbe {
            val path = PipelineText.pathOf(url).lowercase()
            val manifest = path.endsWith("manifest.json")
            return PipelineProbe(
                url = url,
                status = 200,
                kind = if (manifest) "manifest" else "hls",
                contentType = if (manifest) "application/json" else "application/vnd.apple.mpegurl",
                sample = if (manifest) "{\"id\":\"nova-fixture\"}" else "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,segment.ts",
                alive = true
            )
        }

        private fun catalogueHtml(): String = buildString {
            repeat(MIN_CATALOGUE_ITEMS) { index ->
                append("<a href=\"/catalog/movie/$index\">item $index</a>")
            }
        }

        private fun siteHtml(host: String): String = buildString {
            append("<html><body><h1>Catalogue</h1>")
            repeat(MIN_CATALOGUE_ITEMS) { index ->
                append("<a href=\"/watch/$index\">item $index</a>")
            }
            append("<video src=\"https://$host/hls/master.m3u8\"></video>")
            append("</body></html>")
        }
    }

    @Test
    fun `registry has at least 50 engines and at least 12 stages`() {
        assertTrue("engine floor", EngineRegistry.ALL.size >= MIN_ENGINE_COUNT)
        assertEquals(65, EngineRegistry.ALL.size)
        assertTrue("website engines", EngineRegistry.forKind(PipelineKind.WEBSITE).size >= 25)
        assertTrue("addon engines", EngineRegistry.forKind(PipelineKind.ADDON).size >= 25)
        assertTrue("stage floor", PipelineStage.values().size >= MIN_STAGE_COUNT)
        assertEquals(14, PipelineStage.values().size)
        assertEquals(MIN_SITE_TARGET, 10_000)
    }

    @Test
    fun `website pipeline verifies 10000 catalogue and server sites`() = runBlocking {
        val store = InMemoryPipelineStore()
        val runner = PipelineRunner(
            PipelineKind.WEBSITE,
            FixtureTransport(PipelineKind.WEBSITE, sites, "movie+streaming+catalogue"),
            store
        )
        val first = runner.run(0)
        assertFalse("a 5000-site partial run cannot be green", first.ok)
        assertEquals("PARTIAL", first.status)
        assertEquals(5_000, first.verifiedCount)
        assertTrue("no integrity errors", first.errors.isEmpty())

        val second = runner.run(1)
        assertTrue("errors: ${second.errors}, rejections=${second.rejections.size}", second.ok)
        assertTrue("targetMet", second.targetMet)
        assertEquals("TARGET_MET", second.status)
        assertFalse("not stopped by budget", second.budgetStopped)
        assertEquals(MIN_SITE_TARGET, second.verifiedCount)
        assertEquals(MIN_SITE_TARGET, store.size())
        assertEquals(65, second.engineCount)
        assertEquals(14, second.stageCount)
        assertEquals(14, second.stageNames.size)
        assertTrue("verifier ran", (second.stageCounts["12-verify"] ?: 0) >= 5_000)
        assertTrue("store stage ran", (second.stageCounts["13-store"] ?: 0) >= 5_000)
    }

    @Test
    fun `addon pipeline verifies 10000 catalogue and stream sites efficiently`() = runBlocking {
        val store = InMemoryPipelineStore()
        val runner = PipelineRunner(
            PipelineKind.ADDON,
            FixtureTransport(PipelineKind.ADDON, sites, "stremio+addon+manifest"),
            store
        )
        val first = runner.run(0)
        assertFalse("a 5000-addon partial run cannot be green", first.ok)
        assertEquals(5_000, first.verifiedCount)
        assertTrue("no integrity errors", first.errors.isEmpty())

        val second = runner.run(1)
        assertTrue("errors: ${second.errors}, rejections=${second.rejections.size}", second.ok)
        assertEquals("TARGET_MET", second.status)
        assertEquals(MIN_SITE_TARGET, second.verifiedCount)
        assertEquals(MIN_SITE_TARGET, store.size())
        assertEquals(65, second.engineCount)
        assertTrue("verifier ran", (second.stageCounts["12-verify"] ?: 0) >= 5_000)
    }

    @Test
    fun `json catalogue responses count real metas items`() {
        val body = buildString {
            append("{\"metas\":[")
            repeat(MIN_CATALOGUE_ITEMS) { index ->
                if (index > 0) append(",")
                append("{\"id\":\"tt$index\",\"type\":\"movie\"}")
            }
            append("]}")
        }
        assertEquals(MIN_CATALOGUE_ITEMS, PipelineExtractor.jsonItemCount(body))
        assertEquals(0, PipelineExtractor.jsonItemCount("not-json"))
        assertEquals(0, PipelineExtractor.jsonItemCount(""))
    }

    @Test
    fun `corporate video and ad frames are rejected by the verifier`() {
        val site = CandidateSite(
            url = "https://site00000.example/",
            host = "site00000.example",
            engineId = "fixture",
            kind = PipelineKind.WEBSITE,
            topic = "movie streaming catalogue",
            provenance = "fixture"
        )
        val evidence = CatalogueEvidence(
            url = site.url,
            kind = PipelineKind.WEBSITE,
            combinedHtml = "catalogue with forty items",
            catalogueItemCount = MIN_CATALOGUE_ITEMS,
            hasCatalogue = true,
            hasServer = true,
            serverWithCatalogue = true,
            titleMatched = true,
            searchable = true,
            errors = emptyList()
        )
        val sources = listOf(
            ExtractedSource("https://cdn.example/corporate.mp4", site.url, "mp4")
        )
        val corporate = PipelineProbe(
            url = "https://dhygzobemt712.cloudfront.net/verifone-homepage-montage.mp4",
            status = 200,
            kind = "mp4",
            contentType = "video/mp4",
            sample = "corporate video bytes",
            alive = true
        )
        val verdict = PipelineVerifier.verify(site, evidence, sources, corporate)
        assertFalse("corporate MP4 must not be suitable", verdict.accepted)
        assertEquals("irrelevant-corporate-video", verdict.reason)

        val ad = corporate.copy(
            url = "https://doubleclick.net/ad.mp4",
            sample = "advertisement bytes"
        )
        assertEquals(
            "ad-or-tracker-frame",
            PipelineVerifier.verify(site, evidence, sources, ad).reason
        )
    }
}
