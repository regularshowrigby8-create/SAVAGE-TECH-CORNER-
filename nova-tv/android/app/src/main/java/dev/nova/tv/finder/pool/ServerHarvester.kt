package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.SourceFinder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * Feeds [ServerPool]. Each [harvest] call reads the next slice of every
 * keyless directory, so repeated calls (one per scramble) keep reaching
 * servers the pool has not seen:
 *
 *  - stremio-addons.net: every page (700+ addons plus their hosted instances,
 *    manifests inline);
 *  - Stremio's official collection and Cinemeta's community / official
 *    addon catalogues (manifests inline);
 *  - GitHub repository search for two queries, one 100-result page per query
 *    per round (unauthenticated search allows 10 calls a minute), with
 *    manifest links read from the description, homepage and README.
 */
class ServerHarvester(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun harvest(round: Int): List<FinderCandidate> = coroutineScope {
        val inline = async { withTimeoutOrNull(BUDGET_MS) { inlineDirectories(round) }.orEmpty() }
        val github = async { withTimeoutOrNull(BUDGET_MS) { github(round) }.orEmpty() }
        (inline.await() + github.await()).distinctBy { ServerPool.key(it.baseUrl) }
    }

    /** Directories that ship the manifest with the entry: no extra request per server. */
    private suspend fun inlineDirectories(round: Int): List<FinderCandidate> = coroutineScope {
        // All stremio-addons.net pages on the first round, then two per round in rotation.
        val pages = if (round == 0) (1..STREMIO_PAGES).toList() else listOf((round * 2) % STREMIO_PAGES + 1, (round * 2 + 1) % STREMIO_PAGES + 1)
        val directory = pages.map { page ->
            async {
                runCatching {
                    val data = fetch("https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=$page") as? JsonObject
                    (data?.get("addons") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { addon ->
                        listOf(addon) + (addon["instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    }
                }.getOrDefault(emptyList())
            }
        }
        val catalogues = listOf(
            "https://api.strem.io/addonscollection.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        ).map { url ->
            async {
                runCatching {
                    when (val body = fetch(url)) {
                        is JsonArray -> body.filterIsInstance<JsonObject>()
                        is JsonObject -> (body["addons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }
        }
        (directory + catalogues).awaitAll().flatten().mapNotNull { entry ->
            val url = FinderRules.entryUrl(entry)
            val manifest = entry["manifest"] as? JsonObject ?: return@mapNotNull null
            if (url.isEmpty()) null else SourceFinder.candidateFor(url, manifest, "pool", entry)?.copy(from = "Server pool")
        }
    }

    private suspend fun github(round: Int): List<FinderCandidate> = coroutineScope {
        val page = round % GITHUB_PAGES + 1
        val repos = GITHUB_QUERIES.map { q ->
            async {
                runCatching {
                    val query = URLEncoder.encode(q, "UTF-8")
                    val data = fetch("https://api.github.com/search/repositories?q=$query&sort=updated&order=desc&per_page=100&page=$page") as? JsonObject
                    (data?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.str("full_name") }

        val gate = Semaphore(CONCURRENCY)
        var readmes = 0
        val readmeLock = Any()
        val manifestUrls = repos.map { repo ->
            async {
                gate.withPermit {
                    val text = "${repo.str("description")} ${repo.str("homepage")}"
                    val inline = FinderRules.extractManifestUrls(text)
                    if (inline.isNotEmpty()) return@withPermit inline.map { it to repo }
                    val take = synchronized(readmeLock) { if (readmes < MAX_READMES) { readmes++; true } else false }
                    val full = repo.str("full_name")
                    if (!take || !Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(full)) return@withPermit emptyList()
                    val branch = repo.str("default_branch").ifBlank { "main" }.replace(Regex("[^A-Za-z0-9_.-]"), "")
                    runCatching {
                        val readme = http.get("https://raw.githubusercontent.com/$full/$branch/README.md", attempts = 1, limitBytes = FinderRules.MAX_JSON_BYTES)
                        FinderRules.extractManifestUrls(readme).map { it to repo }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().distinctBy { it.first }.take(MAX_GITHUB_MANIFESTS)

        manifestUrls.map { (url, repo) ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                        runCatching {
                            val manifest = fetch(url, FinderRules.MAX_MANIFEST_BYTES)
                            val meta = JsonObject(mapOf("stars" to (repo["stargazers_count"] ?: JsonPrimitive(0))))
                            SourceFinder.candidateFor(url, manifest, "github", meta)?.copy(from = "Server pool · GitHub")
                        }.getOrNull()
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetch(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        return json.parseToJsonElement(http.get(safe, headers = ACCEPT_JSON, attempts = 1, limitBytes = maxBytes))
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val STREMIO_PAGES = 8
        const val GITHUB_PAGES = 10
        private val GITHUB_QUERIES = listOf("topic:stremio-addon", "stremio addon")
        private const val CONCURRENCY = 8
        private const val MAX_READMES = 60
        private const val MAX_GITHUB_MANIFESTS = 120
        private const val BUDGET_MS = 45_000L
        private const val MANIFEST_TIMEOUT_MS = 6_000L
        private val ACCEPT_JSON = mapOf("Accept" to "application/json", "User-Agent" to "NovaTV-Server-Pool/1.0")
    }
}
