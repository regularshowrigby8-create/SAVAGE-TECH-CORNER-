package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.SourceFinder
import dev.nova.tv.finder.WebCrawler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Feeds [ServerPool] until its 3000 slots are full.
 *
 * The old harvester read stremio-addons.net page by page and a couple of
 * GitHub queries, which is why the pool stalled a few hundred entries in --
 * and it duplicated [dev.nova.tv.finder.StremioFinder], so the same hosts
 * arrived twice.
 *
 * Now each [harvest] round runs three independent stages in parallel:
 *
 *  1. [WebCrawler.crawl] - directory graph + web crawl + embed seeds.
 *  2. A GitHub sweep with a rotating sort order and page, so round N+1 reaches
 *     repositories round N did not.
 *  3. The Stremio community catalogue, walked in full and resolved in slices.
 *
 * [SourceEngine] keeps calling this until [ServerPool.CAPACITY] is reached, so
 * every round only has to add *something*; it never has to add everything.
 */
class ServerHarvester(
    private val http: HttpClient,
    private val web: WebCrawler = WebCrawler(http)
) {

    suspend fun harvest(round: Int): List<FinderCandidate> = coroutineScope {
        val crawled = async { withTimeoutOrNull(BUDGET_MS) { web.crawl(round, BUDGET_MS) }.orEmpty() }
        val repos = async { withTimeoutOrNull(BUDGET_MS) { githubExtras(round) }.orEmpty() }
        val catalog = async { withTimeoutOrNull(BUDGET_MS / 2) { communityCatalog(round) }.orEmpty() }

        (crawled.await() + repos.await() + catalog.await())
            .filterNot { FinderRules.isInternetArchive(it.name, it.baseUrl) }
            .filter { FinderRules.isStorable(it.name, it.baseUrl) }
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
                    ) as? JsonObject
                    (data?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.str("full_name") }

        val urls = repos.mapNotNull { repo ->
            val inline = FinderRules.extractManifestUrls(
                "${repo.str("description")} ${repo.str("homepage")}",
                limit = 4
            )
            inline.firstOrNull()?.let { it to repo }
        }.take(MAX_MANIFESTS)

        urls.map { (url, repo) ->
            async {
                withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                    runCatching {
                        val manifest = web.json(url, FinderRules.MAX_MANIFEST_BYTES.toLong()) ?: return@runCatching null
                        val meta = JsonObject(
                            mapOf("stars" to (repo["stargazers_count"] ?: JsonPrimitive(0)))
                        )
                        SourceFinder.candidateFor(url, manifest, "github", meta)
                            ?.copy(from = "Server pool \u00b7 GitHub")
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * The Stremio community catalogue, which lists every addon submitted to the
     * official directory. The crawler reads it too, but only a slice per round;
     * here the whole document is walked so the first round alone can yield
     * hundreds of manifests.
     */
    private suspend fun communityCatalog(round: Int): List<FinderCandidate> {
        val body = web.json(COMMUNITY_CATALOG, MAX_CATALOG_BYTES) ?: return emptyList()
        val urls = LinkedHashSet<String>()
        collectManifestUrls(body, urls)
        if (urls.isEmpty()) return emptyList()
        val list = urls.toList()
        val start = (round * CATALOG_SLICE) % list.size
        val slice = (list.drop(start) + list.take(start)).take(CATALOG_SLICE)
        return web.resolveManifests(slice, "catalog")
    }

    private fun collectManifestUrls(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                when (value) {
                    is JsonPrimitive -> {
                        if (value.isString && value.content.startsWith("http")) {
                            if (value.content.contains("manifest.json") || key.lowercase() in MANIFEST_KEYS) {
                                into += value.content
                            }
                        }
                    }
                    else -> collectManifestUrls(value, into)
                }
            }
            is JsonArray -> element.forEach { collectManifestUrls(it, into) }
            else -> Unit
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val GITHUB_PAGES = 10
        private const val BUDGET_MS = 90_000L
        private const val MANIFEST_TIMEOUT_MS = 7_000L
        private const val MAX_MANIFESTS = 600
        private const val MAX_CATALOG_BYTES = 6_000_000L
        private const val CATALOG_SLICE = 400
        private const val COMMUNITY_CATALOG = "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"
        private val MANIFEST_KEYS = setOf("manifesturl", "transporturl", "url", "manifest")
        private val GITHUB_QUERIES = listOf(
            "topic:stremio-addon",
            "topic:stremio",
            "stremio addon",
            "stremio-addon in:name",
            "manifest.json stremio",
            "addon manifest stream",
            "stremio stream addon",
            "stremio embed addon",
            "media catalog addon",
            "streaming addon server"
        )
    }
}
