package dev.nova.tv.finder

import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.remote.AddonApi
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderRules.string
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A discovered manifest is not installed until it returns a playable result. */
data class FinderCandidate(
    val manifestUrl: String,
    val baseUrl: String,
    val name: String,
    val description: String,
    val version: String,
    val types: List<String>,
    val idPrefixes: List<String>,
    val searchable: Boolean,
    val stars: Int,
    val from: String
) {
    fun acceptsId(id: String): Boolean = idPrefixes.isEmpty() || idPrefixes.any { id.startsWith(it, ignoreCase = true) }
    val acceptsImdbIds: Boolean get() = acceptsId("tt1234567")
}

data class FinderSourceReport(
    val id: String,
    val status: Status,
    val seen: Int,
    val checked: Int,
    val candidates: Int,
    val error: String = "",
    val elapsedMs: Long = 0
) {
    enum class Status { COMPLETE, PARTIAL, FAILED }
}

data class FinderReport(val candidates: List<FinderCandidate>, val sources: List<FinderSourceReport>) {
    val sourcesFailed: Int get() = sources.count { it.status == FinderSourceReport.Status.FAILED }
}

data class VerifiedSource(val candidate: FinderCandidate, val streams: List<Stream>)

/**
 * Six bounded discovery channels, each with its own outcome: the current
 * community directory, Stremio's collection, the addon-catalogue graph, GitHub
 * repository search, archived community submissions, and a separate curated
 * repository list. They are different feeds, not six independent web search
 * companies: some share infrastructure and may return overlapping manifests.
 *
 * Both finders use this implementation; the deeper profile reads more pages.
 * Calls across both share global/per-host gates and a deadline per source.
 * Inject [requester] for deterministic offline tests.
 */
class SourceFinder(
    http: HttpClient,
    private val addons: AddonApi,
    private val requester: suspend (String, Map<String, String>, Long) -> String = { url, headers, limit ->
        http.get(url, headers = headers, attempts = 1, limitBytes = limit)
    }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val networkGate = Semaphore(NETWORK_CONCURRENCY)
    private val hostGates = ConcurrentHashMap<String, Semaphore>()
    private val verifyGate = Semaphore(MANIFEST_CONCURRENCY)

    suspend fun find(
        sourceIds: List<String> = FinderRules.SOURCE_IDS,
        maxPerSource: Int = FinderRules.MAX_PER_SOURCE,
        exclude: Set<String> = emptySet(),
        deep: Boolean = false
    ): FinderReport = coroutineScope {
        val ids = sourceIds.filter { it in FinderRules.SOURCE_IDS }.distinct()
        val reports = ids.map { id ->
            async { runSource(id, maxPerSource.coerceIn(1, MAX_DEEP_PER_SOURCE), deep) }
        }.awaitAll()
        val seen = HashSet<String>()
        val excluded = exclude.map { it.trimEnd('/').lowercase() }.toSet()
        val candidates = reports.flatMap { it.second }
            .filter { seen.add(it.baseUrl.trimEnd('/').lowercase()) && it.baseUrl.trimEnd('/').lowercase() !in excluded }
            .take(FinderRules.MAX_CANDIDATES)
        FinderReport(candidates, reports.map { it.first })
    }

    /** Ask a candidate for this actual identifier; empty prefixes mean unrestricted. */
    suspend fun verify(candidates: List<FinderCandidate>, type: String, id: String): List<VerifiedSource> = coroutineScope {
        candidates.filter { it.acceptsId(id) && FinderRules.manifestSupports(listOf("stream"), it.types, type) }
            .map { candidate ->
                async {
                    verifyGate.withPermit {
                        val streams = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                            try { addons.fetchStreams(candidate.toAddon(), type, id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { emptyList() }
                        }.orEmpty().filter { it.isPlayable }
                        if (streams.isEmpty()) null else VerifiedSource(candidate, streams)
                    }
                }
            }.awaitAll().filterNotNull()
    }

    private data class Discovered(val entries: List<JsonObject>, val seen: Int, val errors: List<String> = emptyList())
    private data class Identified(val candidates: List<FinderCandidate>, val checked: Int, val failures: Int)

    private suspend fun runSource(id: String, maxPerSource: Int, deep: Boolean): Pair<FinderSourceReport, List<FinderCandidate>> {
        val started = System.currentTimeMillis()
        return try {
            val (discovered, identified) = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                val found = when (id) {
                    "stremio-addons" -> crawlStremioAddons(deep)
                    "catalog-graph" -> crawlCatalogGraph(deep)
                    "stremio-collection" -> crawlCollection()
                    "github" -> crawlGitHub()
                    "community-issues" -> crawlIssues()
                    "community-list" -> crawlCuratedRepositories()
                    else -> error("Unknown discovery channel")
                }
                found to identifyEntries(id, found.entries, maxPerSource)
            } ?: error("Discovery channel timed out")
            val problems = discovered.errors.toMutableList()
            if (identified.failures > 0) problems += "${identified.failures} manifest request(s) unavailable"
            FinderSourceReport(
                id = id,
                status = if (problems.isEmpty()) FinderSourceReport.Status.COMPLETE else FinderSourceReport.Status.PARTIAL,
                seen = discovered.seen, checked = identified.checked, candidates = identified.candidates.size,
                error = FinderRules.clean(problems.joinToString("; "), 140),
                elapsedMs = System.currentTimeMillis() - started
            ) to identified.candidates
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FinderSourceReport(
                id = id, status = FinderSourceReport.Status.FAILED, seen = 0, checked = 0, candidates = 0,
                error = FinderRules.clean(error.message ?: "Source unavailable", 140),
                elapsedMs = System.currentTimeMillis() - started
            ) to emptyList()
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun fetchText(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): String {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        val host = URI(safe).host.lowercase()
        return hostGates.computeIfAbsent(host) { Semaphore(PER_HOST_CONCURRENCY) }.withPermit {
            networkGate.withPermit { requester(safe, ACCEPT_JSON, maxBytes) }
        }
    }

    private suspend fun fetchJson(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement =
        json.parseToJsonElement(fetchText(url, maxBytes))

    private fun addonRows(data: JsonElement): List<JsonObject> {
        val rows = when (data) {
            is JsonArray -> data
            is JsonObject -> data["addons"] as? JsonArray ?: error("Directory response has no addons array")
            else -> error("Invalid directory response")
        }
        return rows.filterIsInstance<JsonObject>()
    }

    private suspend fun crawlStremioAddons(deep: Boolean): Discovered = coroutineScope {
        val pages = if (deep) StremioFinder.DIRECTORY_PAGES else 1
        val results = (1..pages).map { page ->
            async {
                attempt {
                    val root = fetchJson("https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=$page")
                    addonRows(root).flatMap { addon ->
                        listOf(addon) + (addon["instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    }
                }
            }
        }.awaitAll()
        if (results.all { it.isFailure }) error("Community directory unavailable")
        val entries = results.flatMap { it.getOrDefault(emptyList()) }
        Discovered(entries.take(MAX_DIRECTORY_ENTRIES), entries.size,
            results.mapNotNull { it.exceptionOrNull()?.message?.let { msg -> FinderRules.clean(msg, 100) } })
    }

    private suspend fun crawlCatalogGraph(deep: Boolean): Discovered = coroutineScope {
        var frontier = listOf(
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        )
        val visited = HashSet<String>()
        val all = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        var depth = 0
        while (frontier.isNotEmpty() && depth <= (if (deep) StremioFinder.GRAPH_DEPTH else 0)) {
            visited.addAll(frontier)
            val results = frontier.map { endpoint -> async { attempt { addonRows(fetchJson(endpoint)) } } }.awaitAll()
            if (depth == 0 && results.all { it.isFailure }) error("Addon catalogue graph unavailable")
            errors += results.mapNotNull { it.exceptionOrNull()?.message?.let { msg -> FinderRules.clean(msg, 100) } }
            val entries = results.flatMap { it.getOrDefault(emptyList()) }
            all += entries
            frontier = entries.flatMap { StremioFinder.addonCatalogEndpoints(it) }
                .filter { it !in visited }.distinct().take(MAX_FRONTIER)
            depth++
        }
        Discovered(all.take(MAX_DIRECTORY_ENTRIES), all.size, errors)
    }

    private suspend fun crawlCollection(): Discovered {
        val entries = addonRows(fetchJson("https://api.strem.io/addonscollection.json"))
        return Discovered(entries.take(MAX_DIRECTORY_ENTRIES), entries.size)
    }

    private suspend fun crawlIssues(): Discovered {
        val data = fetchJson("https://api.github.com/repos/stremio-community/stremio-addons-list/issues?state=all&per_page=100") as? JsonArray
            ?: error("Invalid community issues response")
        val issues = data.filterIsInstance<JsonObject>()
        val entries = issues.flatMap { issue ->
            val text = issue.string("body").replace("stremio://", "https://", ignoreCase = true)
            FinderRules.extractManifestUrls(text).map { url ->
                JsonObject(mapOf("manifestUrl" to JsonPrimitive(url), "name" to JsonPrimitive(issue.string("title"))))
            }
        }
        return Discovered(entries.take(MAX_DIRECTORY_ENTRIES), issues.size)
    }

    private suspend fun crawlGitHub(): Discovered = coroutineScope {
        val query = URLEncoder.encode("topic:stremio-addon", "UTF-8")
        val data = fetchJson("https://api.github.com/search/repositories?q=$query&sort=updated&order=desc&per_page=30") as? JsonObject
            ?: error("Invalid GitHub search response")
        val repos = (data["items"] as? JsonArray ?: error("GitHub search has no items")).filterIsInstance<JsonObject>()
        var readmes = 0
        val lock = Any()
        val results = repos.take(12).map { repo ->
            async { githubEntries(repo) { synchronized(lock) { if (readmes < 4) { readmes++; true } else false } } }
        }.awaitAll()
        Discovered(results.flatMap { it.first }.take(MAX_DIRECTORY_ENTRIES), repos.size, results.mapNotNull { it.second })
    }

    private suspend fun githubEntries(repo: JsonObject, takeReadmeSlot: () -> Boolean): Pair<List<JsonObject>, String?> {
        val homepage = FinderRules.publicHttpsUrl(repo.string("homepage"))
        val description = FinderRules.clean(repo.string("description"), 240)
        val stars = (repo["stargazers_count"] as? JsonPrimitive)?.intOrNull ?: 0
        val name = repo.string("name")
        fun entry(url: String) = JsonObject(mapOf(
            "manifestUrl" to JsonPrimitive(url), "stars" to JsonPrimitive(stars),
            "description" to JsonPrimitive(description), "name" to JsonPrimitive(name)
        ))
        val inline = FinderRules.extractManifestUrls("$description $homepage")
        if (inline.isNotEmpty()) return inline.map { entry(it) } to null
        val fullName = repo.string("full_name")
        if (!REPO_NAME.matches(fullName) || !takeReadmeSlot()) return emptyList<JsonObject>() to null
        val branch = repo.string("default_branch").ifBlank { "main" }
            .replace(Regex("[^A-Za-z0-9_.-]"), "").take(80).ifBlank { "main" }
        val readme = attempt { fetchText("https://raw.githubusercontent.com/$fullName/$branch/README.md") }
        val out = FinderRules.extractManifestUrls(readme.getOrNull()).map { entry(it) }.toMutableList()
        val hasPages = (repo["has_pages"] as? JsonPrimitive)?.booleanOrNull == true
        val owner = ((repo["owner"] as? JsonObject)?.get("login") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (hasPages && owner.isNotEmpty() && name.isNotEmpty()) {
            val pages = FinderRules.publicHttpsUrl("https://$owner.github.io/$name/manifest.json")
            if (pages.isNotEmpty()) out += entry(pages)
        }
        return out to readme.exceptionOrNull()?.let { "GitHub README unavailable" }
    }

    /** A curated project index, distinct from GitHub's topic search. */
    private suspend fun crawlCuratedRepositories(): Discovered = coroutineScope {
        val text = fetchText("https://raw.githubusercontent.com/doingodswork/awesome-stremio/master/README.md")
        val section = text.substringAfter("## Addons", text).substringBefore("## Tutorials")
        val repos = REPO_LINK.findAll(section).map { it.groupValues[1] }.filter { REPO_NAME.matches(it) }.distinct().take(12).toList()
        val results = repos.map { repo ->
            async { attempt { FinderRules.extractManifestUrls(fetchText("https://raw.githubusercontent.com/$repo/HEAD/README.md")) } }
        }.awaitAll()
        val links = (FinderRules.extractManifestUrls(section) + results.flatMap { it.getOrDefault(emptyList()) }).distinct()
        val entries = links.map { JsonObject(mapOf("manifestUrl" to JsonPrimitive(it))) }
        Discovered(entries.take(MAX_DIRECTORY_ENTRIES), repos.size,
            results.mapNotNull { it.exceptionOrNull()?.let { "Curated README unavailable" } })
    }

    private suspend fun identifyEntries(sourceId: String, entries: List<JsonObject>, maxPerSource: Int): Identified = coroutineScope {
        val seen = HashSet<String>()
        val inline = mutableListOf<FinderCandidate>()
        val pending = LinkedHashMap<String, JsonObject>()
        var checked = 0
        for (entry in entries) {
            val url = FinderRules.entryUrl(entry)
            if (url.isEmpty() || !seen.add(url)) continue
            val manifest = entry["manifest"] as? JsonObject
            if (manifest != null) {
                // Directory manifests are free to inspect. Applying the cap
                // before validation meant the first few catalogue/torrent-only
                // entries could hide every usable stream addon further down.
                checked++
                candidateFor(url, manifest, sourceId, entry)?.let { inline += it }
                if (inline.size >= maxPerSource) break
            } else if (pending.size < maxPerSource) {
                pending[url] = entry
            }
        }
        val slots = (maxPerSource - inline.size).coerceAtLeast(0)
        val results = pending.entries.take(slots).map { (url, entry) ->
            async {
                val parsed = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                    attempt { candidateFor(url, fetchJson(url, FinderRules.MAX_MANIFEST_BYTES), sourceId, entry) }
                }
                (parsed?.getOrNull()) to (parsed == null || parsed.isFailure)
            }
        }.awaitAll()
        Identified(inline + results.mapNotNull { it.first }, checked + results.size, results.count { it.second })
    }

    companion object {
        private const val SOURCE_TIMEOUT_MS = 24_000L
        private const val MANIFEST_TIMEOUT_MS = 6_000L
        private const val VERIFY_TIMEOUT_MS = 8_000L
        private const val NETWORK_CONCURRENCY = 12
        private const val PER_HOST_CONCURRENCY = 3
        private const val MANIFEST_CONCURRENCY = 8
        private const val MAX_DEEP_PER_SOURCE = 24
        private const val MAX_DIRECTORY_ENTRIES = 500
        private const val MAX_FRONTIER = 12
        private val REPO_NAME = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val REPO_LINK = Regex("""https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE)
        private val ACCEPT_JSON = mapOf("Accept" to "application/json,text/plain;q=0.9,*/*;q=0.8", "User-Agent" to "NovaTV-Source-Finder/1.0")

        fun candidateFor(manifestUrl: String, manifest: JsonElement, sourceId: String, meta: JsonObject): FinderCandidate? {
            val safe = FinderRules.publicHttpsUrl(manifestUrl)
            val info = FinderRules.parseManifest(manifest) ?: return null
            if (safe.isEmpty()) return null
            val base = FinderRules.baseFromManifestUrl(safe)
            if (base.isEmpty() || FinderRules.isRedundantSource(info.name, base)) return null
            return FinderCandidate(
                manifestUrl = safe, baseUrl = base, name = info.name,
                description = info.description.ifBlank { FinderRules.clean(meta.string("description"), 240) },
                version = info.version, types = info.types, idPrefixes = info.idPrefixes,
                searchable = info.searchable, stars = (meta["stars"] as? JsonPrimitive)?.intOrNull ?: 0,
                from = "Server Finder · $sourceId"
            )
        }
    }
}

internal fun FinderCandidate.toAddon(): NovaAddon = NovaAddon(
    baseUrl = baseUrl,
    manifest = dev.nova.tv.data.model.Manifest(
        id = baseUrl, name = name, version = version, description = description, types = types,
        idPrefixes = idPrefixes, resources = listOf(dev.nova.tv.data.model.ResourceDef(name = "stream"))
    )
)
