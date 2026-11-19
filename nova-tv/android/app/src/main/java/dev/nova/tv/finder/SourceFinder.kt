package dev.nova.tv.finder

import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderRules.string
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
import java.net.URLEncoder

/** One addon the finder discovered. Not installed until it proves itself. */
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
    /** "Server Finder · github", as in core.js providerRecord. */
    val from: String
) {
    /** Only imdb-keyed addons can answer for Cinemeta titles. */
    val acceptsImdbIds: Boolean get() = idPrefixes.isEmpty() || idPrefixes.any { it == "tt" }
}

/** Per-directory outcome, so the UI can say which directory failed. */
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

data class FinderReport(
    val candidates: List<FinderCandidate>,
    val sources: List<FinderSourceReport>
) {
    val sourcesFailed: Int get() = sources.count { it.status == FinderSourceReport.Status.FAILED }
}

/** A candidate that returned at least one playable stream for a real title. */
data class VerifiedSource(val candidate: FinderCandidate, val streams: List<Stream>)

/**
 * The ZeroPlay Server Finder (`lib/server-finder/core.js`), ported to Kotlin.
 *
 * It reads three public addon directories -- stremio-addons.net, Cinemeta's
 * addon_catalog, and GitHub repositories tagged `stremio-addon` -- fetches at
 * most [FinderRules.MAX_PER_SOURCE] manifests from each, and keeps the ones
 * that declare a stream resource for films or series.
 *
 * The JS ran each directory in a worker thread; here each one is a coroutine
 * with its own time budget, so one slow directory cannot stall the others.
 * Discovery only produces candidates: [verify] is what promotes one, and only
 * after it returns a playable stream for an actual title (core.js:
 * "candidate requires title-scoped identity-verified playback match").
 */
class SourceFinder(
    private val http: HttpClient,
    private val addons: AddonClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun find(
        sourceIds: List<String> = FinderRules.SOURCE_IDS,
        maxPerSource: Int = FinderRules.MAX_PER_SOURCE,
        exclude: Set<String> = emptySet()
    ): FinderReport = coroutineScope {
        val ids = sourceIds.filter { it in FinderRules.SOURCE_IDS }.distinct()
        val reports = ids.map { id -> async { runSource(id, maxPerSource.coerceIn(1, FinderRules.MAX_PER_SOURCE)) } }
            .awaitAll()
        val seen = HashSet<String>()
        val excluded = exclude.map { it.trimEnd('/').lowercase() }.toSet()
        val candidates = reports.flatMap { it.second }
            .filter { seen.add(it.manifestUrl) && it.baseUrl.lowercase() !in excluded }
            .take(FinderRules.MAX_CANDIDATES)
        FinderReport(candidates, reports.map { it.first })
    }

    /**
     * Ask each candidate for streams of one title; keep those that answer with
     * something Nova can play. This is the only path to "installed".
     */
    suspend fun verify(
        candidates: List<FinderCandidate>,
        type: String,
        id: String
    ): List<VerifiedSource> = coroutineScope {
        val gate = Semaphore(MANIFEST_CONCURRENCY)
        candidates
            .filter { it.acceptsImdbIds && FinderRules.manifestSupports(listOf("stream"), it.types, type) }
            .map { candidate ->
                async {
                    gate.withPermit {
                        val streams = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                            runCatching {
                                addons.fetchStreams(candidate.toAddon(), type, id)
                            }.getOrDefault(emptyList())
                        }.orEmpty().filter { it.isPlayable }
                        if (streams.isEmpty()) null else VerifiedSource(candidate, streams)
                    }
                }
            }.awaitAll().filterNotNull()
    }

    // -- directories -------------------------------------------------------

    private suspend fun runSource(id: String, maxPerSource: Int): Pair<FinderSourceReport, List<FinderCandidate>> {
        val started = System.currentTimeMillis()
        return try {
            val discovered = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                when (id) {
                    "stremio-addons" -> crawlStremioAddons()
                    "catalog-graph" -> crawlCatalogGraph()
                    "github" -> crawlGitHub()
                    else -> error("Unknown crawler source")
                }
            } ?: error("Directory timed out")
            val identified = identifyEntries(id, discovered.entries, maxPerSource)
            val partial = discovered.errors.isNotEmpty()
            FinderSourceReport(
                id = id,
                status = if (partial) FinderSourceReport.Status.PARTIAL else FinderSourceReport.Status.COMPLETE,
                seen = discovered.seen,
                checked = identified.second,
                candidates = identified.first.size,
                error = if (partial) "${discovered.errors.size} catalogue endpoint(s) unavailable" else "",
                elapsedMs = System.currentTimeMillis() - started
            ) to identified.first
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            FinderSourceReport(
                id = id,
                status = FinderSourceReport.Status.FAILED,
                seen = 0, checked = 0, candidates = 0,
                error = FinderRules.clean(error.message ?: "Source unavailable", 140),
                elapsedMs = System.currentTimeMillis() - started
            ) to emptyList()
        }
    }

    private class Discovered(val entries: List<JsonObject>, val seen: Int, val errors: List<String> = emptyList())

    private suspend fun fetchJson(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        val text = http.get(safe, headers = ACCEPT_JSON, attempts = 1, limitBytes = maxBytes)
        return json.parseToJsonElement(text)
    }

    private suspend fun fetchText(url: String): String {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        return http.get(safe, headers = ACCEPT_JSON, attempts = 1, limitBytes = FinderRules.MAX_JSON_BYTES)
    }

    private suspend fun crawlStremioAddons(): Discovered {
        val data = fetchJson(
            "https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1"
        ) as? JsonObject ?: JsonObject(emptyMap())
        val entries = mutableListOf<JsonObject>()
        for (addon in (data["addons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
            entries += addon
            entries += (addon["instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }
        return Discovered(entries.take(100), entries.size)
    }

    private suspend fun crawlCatalogGraph(): Discovered = coroutineScope {
        val endpoints = listOf(
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        )
        val results = endpoints.map { endpoint ->
            async {
                runCatching {
                    ((fetchJson(endpoint) as? JsonObject)?.get("addons") as? JsonArray)
                        .orEmpty().filterIsInstance<JsonObject>()
                }
            }
        }.awaitAll()
        val errors = results.mapNotNull { it.exceptionOrNull()?.message?.let { m -> FinderRules.clean(m, 120) } }
        if (errors.size == endpoints.size) error(errors.firstOrNull() ?: "Catalogues unavailable")
        val entries = results.flatMap { it.getOrDefault(emptyList()) }
        Discovered(entries.take(100), entries.size, errors)
    }

    private suspend fun crawlGitHub(): Discovered = coroutineScope {
        val query = URLEncoder.encode("topic:stremio-addon", "UTF-8")
        val data = fetchJson(
            "https://api.github.com/search/repositories?q=$query&sort=updated&order=desc&per_page=30"
        ) as? JsonObject ?: JsonObject(emptyMap())
        val repos = (data["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val gate = Semaphore(4)
        var readmes = 0
        val readmeLock = Any()
        val entries = repos.take(12).map { repo ->
            async {
                gate.withPermit { githubEntries(repo) { synchronized(readmeLock) { if (readmes < 4) { readmes++; true } else false } } }
            }
        }.awaitAll().flatten()
        Discovered(entries.take(100), repos.size)
    }

    /** core.js crawlGitHub's per-repository body. */
    private suspend fun githubEntries(repo: JsonObject, takeReadmeSlot: () -> Boolean): List<JsonObject> {
        val homepage = FinderRules.publicHttpsUrl(repo.string("homepage"))
        val description = FinderRules.clean(repo.string("description"), 240)
        val stars = (repo["stargazers_count"] as? JsonPrimitive)?.intOrNull ?: 0
        val name = repo.string("name")
        fun entry(manifestUrl: String, repoUrl: String = "") = JsonObject(
            buildMap {
                put("manifestUrl", JsonPrimitive(manifestUrl))
                put("stars", JsonPrimitive(stars))
                put("description", JsonPrimitive(description))
                put("homepage", JsonPrimitive(homepage))
                put("name", JsonPrimitive(name))
                if (repoUrl.isNotEmpty()) put("repoUrl", JsonPrimitive(repoUrl))
            }
        )
        val inline = FinderRules.extractManifestUrls("$description $homepage")
        if (inline.isNotEmpty()) return inline.map { entry(it) }

        val fullName = repo.string("full_name")
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(fullName) || !takeReadmeSlot()) return emptyList()
        val branch = repo.string("default_branch").ifBlank { "main" }
            .replace(Regex("[^A-Za-z0-9_.-]"), "").take(80).ifBlank { "main" }
        val repoUrl = "https://github.com/$fullName"
        val out = mutableListOf<JsonObject>()
        runCatching {
            val readme = fetchText("https://raw.githubusercontent.com/$fullName/$branch/README.md")
            FinderRules.extractManifestUrls(readme).forEach { out += entry(it, repoUrl) }
        }
        val hasPages = (repo["has_pages"] as? JsonPrimitive)?.booleanOrNull == true
        val owner = ((repo["owner"] as? JsonObject)?.get("login") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (hasPages && owner.isNotEmpty() && name.isNotEmpty()) {
            val pages = FinderRules.publicHttpsUrl("https://$owner.github.io/$name/manifest.json")
            if (pages.isNotEmpty()) out += entry(pages, repoUrl)
        }
        return out
    }

    /** core.js identifyEntries: fetch up to [maxPerSource] manifests and keep the stream addons. */
    private suspend fun identifyEntries(
        sourceId: String,
        entries: List<JsonObject>,
        maxPerSource: Int
    ): Pair<List<FinderCandidate>, Int> = coroutineScope {
        val seen = LinkedHashMap<String, JsonObject>()
        for (entry in entries) {
            val url = FinderRules.entryUrl(entry)
            if (url.isEmpty() || url in seen) continue
            seen[url] = entry
            if (seen.size >= maxPerSource) break
        }
        val gate = Semaphore(MANIFEST_CONCURRENCY)
        val candidates = seen.map { (manifestUrl, entry) ->
            async {
                gate.withPermit {
                    runCatching {
                        val manifest = entry["manifest"] as? JsonObject
                            ?: fetchJson(manifestUrl, FinderRules.MAX_MANIFEST_BYTES)
                        candidateFor(manifestUrl, manifest, sourceId, entry)
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        candidates to seen.size
    }

    companion object {
        private const val SOURCE_TIMEOUT_MS = 20_000L
        private const val VERIFY_TIMEOUT_MS = 8_000L
        private const val MANIFEST_CONCURRENCY = 6
        private val ACCEPT_JSON = mapOf(
            "Accept" to "application/json,text/plain;q=0.9,*/*;q=0.8",
            "User-Agent" to "NovaTV-Source-Finder/1.0"
        )

        /** core.js providerRecord, minus the bookkeeping the UI never reads. */
        fun candidateFor(manifestUrl: String, manifest: JsonElement, sourceId: String, meta: JsonObject): FinderCandidate? {
            val safe = FinderRules.publicHttpsUrl(manifestUrl)
            val info = FinderRules.parseManifest(manifest) ?: return null
            if (safe.isEmpty()) return null
            val base = FinderRules.baseFromManifestUrl(safe)
            if (base.isEmpty()) return null
            return FinderCandidate(
                manifestUrl = safe,
                baseUrl = base,
                name = info.name,
                description = info.description.ifBlank { FinderRules.clean(meta.string("description"), 240) },
                version = info.version,
                types = info.types,
                idPrefixes = info.idPrefixes,
                searchable = info.searchable,
                stars = (meta["stars"] as? JsonPrimitive)?.intOrNull ?: 0,
                from = "Server Finder · $sourceId"
            )
        }
    }
}

/** A candidate wears an addon shape just long enough to be asked for streams. */
internal fun FinderCandidate.toAddon(): NovaAddon = NovaAddon(
    baseUrl = baseUrl,
    manifest = dev.nova.tv.data.model.Manifest(
        id = baseUrl,
        name = name,
        version = version,
        description = description,
        types = types,
        idPrefixes = idPrefixes,
        resources = listOf(dev.nova.tv.data.model.ResourceDef(name = "stream"))
    )
)
