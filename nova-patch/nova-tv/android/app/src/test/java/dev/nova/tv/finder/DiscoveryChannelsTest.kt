package dev.nova.tv.finder

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonApi
import dev.nova.tv.data.remote.CatalogBatch
import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DiscoveryChannelsTest {
    private val manifest = """{"id":"sample","name":"Sample","resources":["stream"],"types":["movie","series"],"idPrefixes":["tt"]}"""
    private fun entry(host: String) = """{"transportUrl":"https://$host/manifest.json","manifest":$manifest}"""
    private val api = object : AddonApi {
        override suspend fun loadAddon(baseUrl: String): NovaAddon = error("unused")
        override suspend fun fetchCatalogPage(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String>): CatalogBatch = error("unused")
        override suspend fun fetchMeta(addon: NovaAddon, type: String, id: String): MetaPreview? = null
        override suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream> = emptyList()
        override suspend fun fetchSubtitles(addon: NovaAddon, type: String, id: String): List<SubtitleRef> = emptyList()
    }

    private fun fixture(failIssues: Boolean = false, failOfficial: Boolean = false, requests: MutableList<String>): SourceFinder =
        SourceFinder(HttpClient(), api) { url, _, _ ->
            requests += url
            delay(10)
            when {
                url.contains("stremio-addons.net/api/") -> """{"addons":[${entry("directory.example")}]}"""
                url.contains("addon_catalog/all/community") -> """{"addons":[${entry("graph.example")}]}"""
                url.contains("addon_catalog/all/official") -> if (failOfficial) throw IOException("offline") else """{"addons":[${entry("official.example")}]}"""
                url == "https://api.strem.io/addonscollection.json" -> """[${entry("collection.example")}]"""
                url.contains("api.github.com/search/repositories") -> """{"items":[{"homepage":"https://repo.example/manifest.json","name":"sample"}]}"""
                url.contains("api.github.com/repos/stremio-community/") -> if (failIssues) throw IOException("offline") else """[{"title":"sample","body":"stremio://issue.example/manifest.json"}]"""
                url.contains("doingodswork/awesome-stremio/master/README.md") -> "## Addons\nhttps://github.com/foo/bar\n## Tutorials"
                url.contains("foo/bar/HEAD/README.md") -> "https://curated.example/manifest.json"
                url.endsWith("/manifest.json") -> manifest
                else -> error("unexpected URL: $url")
            }
        }

    @Test fun `both finders use six real channels and the deep finder reads additional pages`() = runTest {
        val requests = mutableListOf<String>()
        val finder = fixture(requests = requests)
        val fast = finder.find()
        assertEquals(FinderRules.SOURCE_IDS.toSet(), fast.sources.map { it.id }.toSet())
        assertEquals(6, fast.sources.size)
        assertTrue(fast.sources.all { it.status == FinderSourceReport.Status.COMPLETE })
        assertTrue(fast.candidates.map { it.baseUrl }.containsAll(listOf(
            "https://directory.example", "https://graph.example", "https://collection.example",
            "https://repo.example", "https://issue.example", "https://curated.example"
        )))
        requests.clear()
        val deep = StremioFinder(HttpClient(), finder).find()
        assertEquals(6, deep.sources.size)
        assertTrue(requests.any { "page=5" in it })
        assertTrue(deep.candidates.all { it.from.startsWith("Stremio · ") })
    }

    @Test fun `inline catalog only entries do not consume discovery slots`() = runTest {
        val catalog = """{"id":"catalog","name":"Catalog","resources":["catalog"],"types":["movie"]}"""
        val unusable = (1..12).joinToString(",") { n ->
            """{"transportUrl":"https://catalog$n.example/manifest.json","manifest":$catalog}"""
        }
        val finder = SourceFinder(HttpClient(), api) { _, _, _ ->
            """{"addons":[$unusable,${entry("later.example")}]}"""
        }
        val report = finder.find(sourceIds = listOf("stremio-addons"), maxPerSource = 2)
        assertEquals(listOf("https://later.example"), report.candidates.map { it.baseUrl })
        assertEquals(13, report.sources.single().checked)
    }

    @Test fun `failed and partially failed channels are reported separately`() = runTest {
        val report = fixture(failIssues = true, failOfficial = true, requests = mutableListOf()).find()
        val byId = report.sources.associateBy { it.id }
        assertEquals(FinderSourceReport.Status.FAILED, byId.getValue("community-issues").status)
        assertEquals(FinderSourceReport.Status.PARTIAL, byId.getValue("catalog-graph").status)
        assertEquals(FinderSourceReport.Status.COMPLETE, byId.getValue("stremio-collection").status)
        assertEquals(1, report.sourcesFailed)
        assertTrue(report.candidates.any { it.baseUrl == "https://graph.example" })
    }
}
