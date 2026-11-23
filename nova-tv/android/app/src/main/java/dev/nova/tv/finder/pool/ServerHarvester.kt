package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.WebCrawler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Feeds [ServerPool] until its 6000 slots are full.
 *
 * The old harvester read stremio-addons.net page by page and a couple of
 * GitHub queries, which is why the pool stalled a few hundred entries in --
 * and it duplicated [dev.nova.tv.finder.StremioFinder], so the same hosts
 * arrived twice.
 *
 * Now each [harvest] round runs the open-web crawler ([WebCrawler]) at a new
 * rotation plus a GitHub sweep, both in parallel. Rounds are cheap to repeat:
 * the crawler rotates its search queries, GitHub pages and followed links, so
 * round N+1 reaches ground round N did not. [SourceEngine] keeps calling this
 * until [ServerPool.CAPACITY] is reached.
 */
class ServerHarvester(
    private val http: HttpClient,
    private val web: WebCrawler = WebCrawler(http)
) {

    suspend fun harvest(round: Int): List<FinderCandidate> = coroutineScope {
        val crawled = async { withTimeoutOrNull(BUDGET_MS) { web.crawl(round, BUDGET_MS) }.orEmpty() }
        val extra = async { withTimeoutOrNull(BUDGET_MS) { githubExtras(round) }.orEmpty() }
        (crawled.await() + extra.await())
            .filterNot { FinderRules.isInternetArchive(it.name, it.baseUrl) }
            .distinctBy { ServerPool.key(it.baseUrl) }
    }

    /**
     * A second GitHub pass with different sort orders. The crawler already
     * searches GitHub; this reaches the "newest" and "most starred" ends of the
     * same queries, which the relevance sort buries.
     */
    private suspend fun githubExtras(round: Int): List<FinderCandidate> = coroutineScope {
        val sort = if (round % 2 == 0) "stars" else "updated"
        val page = round % GITHUB_PAGES + 1
        val repos = GITHUB_QUERIES.map { q ->
            async {
                runCatching {
                    val query = java.net.URLEncoder.encode(q, "UTF-8")
                    val data = web.json(
                        "https://api.github.com/search/repositories?q=$query&sort=$sort&order=desc&per_page=100&page=$page"
                    ) as? kotlinx.serialization.json.JsonObject
                    (data?.get("items") as? kotlinx.serialization.json.JsonArray)
                        .orEmpty().filterIsInstance<kotlinx.serialization.json.JsonObject>()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.str("full_name") }

        val urls = repos.mapNotNull { repo ->
            val inline = FinderRules.extractManifestUrls("${repo.str("description")} ${repo.str("homepage")}")
            inline.firstOrNull()?.let { it to repo }
        }.take(MAX_MANIFESTS)

        urls.map { (url, repo) ->
            async {
                withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                    runCatching {
                        val manifest = web.json(url, FinderRules.MAX_MANIFEST_BYTES)
                        val meta = kotlinx.serialization.json.JsonObject(
                            mapOf("stars" to (repo["stargazers_count"] ?: kotlinx.serialization.json.JsonPrimitive(0)))
                        )
                        dev.nova.tv.finder.SourceFinder.candidateFor(url, manifest, "github", meta)
                            ?.copy(from = "Server pool \u00b7 GitHub")
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val GITHUB_PAGES = 20
        private const val BUDGET_MS = 120_000L
        private const val MANIFEST_TIMEOUT_MS = 5_000L
        private const val MAX_MANIFESTS = 600
        private val GITHUB_QUERIES = listOf(
            "topic:stremio-addon",
            "stremio addon",
            "stremio-addon in:name",
            "manifest.json stremio",
            "addon manifest stream",
            "media catalog addon",
            "streaming addon server",
            "addon stream 1080p manifest",
            "topic:stremio",
            "stremio stream addon"
        )
    }
}
