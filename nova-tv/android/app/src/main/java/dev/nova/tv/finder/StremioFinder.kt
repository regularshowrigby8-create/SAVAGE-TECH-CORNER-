package dev.nova.tv.finder

import dev.nova.tv.data.remote.HttpClient
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

/**
 * The Stremio-only finder.
 *
 * Unlike the ZeroPlay finder (three directories, 8 manifests each), this one
 * stays inside the Stremio ecosystem and goes deeper:
 *
 *  - stremio-addons.net, pages 1..[DIRECTORY_PAGES] fetched in parallel;
 *  - the `addon_catalog` graph: Cinemeta's community/official lists, then the
 *    `addon_catalog` resource of every addon found there, breadth-first to
 *    depth [GRAPH_DEPTH]. This is how Stremio itself discovers addon
 *    collections, and it finds addons no single directory lists.
 *
 * Only manifests that declare a `stream` resource for movie/series survive
 * [FinderRules.parseManifest]; torrents and oversize files are filtered later
 * per stream, because an addon can serve both kinds.
 */
class StremioFinder(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun find(exclude: Set<String> = emptySet()): FinderReport = coroutineScope {
        val started = System.currentTimeMillis()
        val directory = async { withTimeoutOrNull(BUDGET_MS) { directoryEntries() } }
        val graph = async { withTimeoutOrNull(BUDGET_MS) { graphEntries() } }
        val dirEntries = directory.await()
        val graphResult = graph.await()

        val excluded = exclude.map { it.trimEnd('/').lowercase() }.toSet()
        val gate = Semaphore(MANIFEST_CONCURRENCY)
        val seen = LinkedHashMap<String, Pair<String, JsonObject>>()
        (dirEntries.orEmpty().map { "stremio-addons" to it } + graphResult?.first.orEmpty().map { "catalog-graph" to it })
            .forEach { (source, entry) ->
                val url = FinderRules.entryUrl(entry)
                if (url.isNotEmpty() && url !in seen && seen.size < MAX_MANIFESTS) seen[url] = source to entry
            }
        val candidates = seen.map { (url, pair) ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                        runCatching {
                            val manifest = pair.second["manifest"] as? JsonObject ?: fetch(url, FinderRules.MAX_MANIFEST_BYTES)
                            SourceFinder.candidateFor(url, manifest, pair.first, pair.second)
                                ?.copy(from = "Stremio · ${pair.first}")
                        }.getOrNull()
                    }
                }
            }
        }.awaitAll().filterNotNull()
            .filter { it.baseUrl.lowercase() !in excluded }
            .distinctBy { it.baseUrl.lowercase() }

        val elapsed = System.currentTimeMillis() - started
        FinderReport(
            candidates = candidates,
            sources = listOf(
                report("stremio-addons", dirEntries, candidates.count { it.from.endsWith("stremio-addons") }, elapsed),
                report("catalog-graph", graphResult?.first, candidates.count { it.from.endsWith("catalog-graph") }, elapsed)
            )
        )
    }

    private fun report(id: String, entries: List<JsonObject>?, found: Int, elapsed: Long) = FinderSourceReport(
        id = id,
        status = if (entries == null) FinderSourceReport.Status.FAILED else FinderSourceReport.Status.COMPLETE,
        seen = entries?.size ?: 0,
        checked = entries?.size ?: 0,
        candidates = found,
        error = if (entries == null) "Directory unavailable" else "",
        elapsedMs = elapsed
    )

    /** stremio-addons.net, several pages at once. A failed page is skipped. */
    private suspend fun directoryEntries(): List<JsonObject> = coroutineScope {
        (1..DIRECTORY_PAGES).map { page ->
            async {
                runCatching {
                    val data = fetch(
                        "https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=$page"
                    ) as? JsonObject
                    (data?.get("addons") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { addon ->
                        listOf(addon) + (addon["instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /**
     * Breadth-first over `addon_catalog`. Depth 0 is Cinemeta's lists; each
     * further level asks the addons just found that themselves publish an
     * addon catalogue.
     */
    private suspend fun graphEntries(): Pair<List<JsonObject>, Int> = coroutineScope {
        val visited = HashSet<String>()
        val all = mutableListOf<JsonObject>()
        var frontier = listOf(
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        )
        var depth = 0
        while (frontier.isNotEmpty() && depth <= GRAPH_DEPTH) {
            visited += frontier
            val gate = Semaphore(MANIFEST_CONCURRENCY)
            val found = frontier.map { endpoint ->
                async {
                    gate.withPermit {
                        runCatching {
                            ((fetch(endpoint) as? JsonObject)?.get("addons") as? JsonArray)
                                .orEmpty().filterIsInstance<JsonObject>()
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
            all += found
            frontier = found.flatMap { addonCatalogEndpoints(it) }.filter { it !in visited }.distinct().take(MAX_FRONTIER)
            depth++
        }
        all to depth
    }

    private suspend fun fetch(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        return json.parseToJsonElement(http.get(safe, headers = ACCEPT_JSON, attempts = 1, limitBytes = maxBytes))
    }

    companion object {
        const val DIRECTORY_PAGES = 5
        const val GRAPH_DEPTH = 2
        private const val MAX_FRONTIER = 12
        private const val MAX_MANIFESTS = 160
        private const val MANIFEST_CONCURRENCY = 8
        private const val BUDGET_MS = 25_000L
        private const val MANIFEST_TIMEOUT_MS = 6_000L
        private val ACCEPT_JSON = mapOf("Accept" to "application/json", "User-Agent" to "NovaTV-Stremio-Finder/1.0")

        /**
         * The addon_catalog URLs an entry publishes:
         * `{base}/addon_catalog/{type}/{id}.json` for each declared catalogue.
         * Pure, so it is unit-tested.
         */
        fun addonCatalogEndpoints(entry: JsonObject): List<String> {
            val manifest = entry["manifest"] as? JsonObject ?: return emptyList()
            val resources = (manifest["resources"] as? JsonArray).orEmpty().mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> (it["name"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }
            if ("addon_catalog" !in resources) return emptyList()
            val transport = (entry["transportUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (entry["manifestUrl"] as? JsonPrimitive)?.contentOrNull
                ?: return emptyList()
            val base = FinderRules.baseFromManifestUrl(transport)
            if (base.isEmpty()) return emptyList()
            val catalogs = (manifest["addonCatalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            return catalogs.mapNotNull { c ->
                val type = (c["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (!Regex("^[A-Za-z0-9_.-]{1,60}$").matches(type) || !Regex("^[A-Za-z0-9_.-]{1,60}$").matches(id)) return@mapNotNull null
                "$base/addon_catalog/$type/$id.json"
            }.take(4)
        }
    }
}
