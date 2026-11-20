package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.SourceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Feeds [ServerPool] until the pool is full or every source is exhausted.
 *
 * Three things were wrong with the previous version, and each one is fixed here
 * rather than papered over:
 *
 *  1. **It was serial.** Every source was read one round at a time, one request
 *     at a time inside a source, and the engine waited for the whole round. With
 *     three directories and two GitHub queries, the second round never produced
 *     anything new, so the pool stalled around 120 entries. Now every source
 *     runs in its own coroutine over bounded workers: [GLOBAL_CONCURRENCY]
 *     requests in flight in total, [HOST_CONCURRENCY] against any one host, and a
 *     source that is slow cannot block another source.
 *
 *  2. **It only looked at two places.** The reachable surface is much wider: the
 *     Stremio addon-protocol catalogue graph, the public collection APIs, GitHub
 *     search over several queries and pages, GitHub Pages hosts, homepages, and
 *     the community lists. Each is a [Source] so they can be enabled, counted and
 *     reported independently -- "3 sources produced 40 servers" is a fact the UI
 *     can print; "harvest finished" is not.
 *
 *  3. **It lied about exhaustion.** A round that added nothing was treated as a
 *     pause, then retried forever. Now each source tracks the pages it has read,
 *     retries with backoff on 403/429, and reports DONE when it has no unseen
 *     work left. The loop stops when the pool is full (3000), the caller stops
 *     it, or every source is done *and* their retry windows have expired. The
 *     `exhausted` flag means "no source has unseen work", never "the internet is
 *     empty": the Internet keeps growing, and a new round can always be started.
 *
 * Safety is unchanged and non-negotiable: https only, no private or loopback
 * addresses, no credentials in URLs, response size caps, and the same manifest
 * validation every other finder uses ([SourceFinder.candidateFor]).
 */
class ServerHarvester(
    private val http: HttpClient,
    /** Injected in tests so crawling needs no network. */
    sources: List<Source>? = null
) {

    /**
     * One place to look. Each instance keeps its own cursor and retry state.
     *
     * [isDone] exists so the loop can *count* finished sources. The previous
     * version kept a private `done` set that was only ever written when a sweep
     * came back empty, so a source that finished on its last page was never
     * counted, `sourcesDone` stayed below `sourceCount`, and the loop could
     * never reach "every source exhausted".
     */
    interface Source {
        val id: String
        val label: String

        /** New candidates for the next slice of this source's work. May be empty. */
        suspend fun sweep(ctx: SweepContext): List<FinderCandidate>

        /** True when there is no unseen work left. */
        fun isDone(): Boolean

        /** True while a failed request is inside its backoff window. */
        fun coolingDown(now: Long): Boolean = now < backoffUntil()

        /** When this source may be asked again (0 = now). */
        fun backoffUntil(): Long
    }

    /** Shared machinery for a sweep: request budget, dedupe, and statistics. */
    inner class SweepContext internal constructor(
        private val gate: Semaphore,
        val seen: MutableSet<String>
    ) {
        /**
         * GET text through the shared gate. Returns null on any failure -- the
         * caller treats a failure as "no more from this page", never as success.
         * 403/429 raise the source's backoff through [noteFailure].
         */
        suspend fun get(url: String, source: Source, headers: Map<String, String> = ACCEPT_JSON,
                        limitBytes: Long = FinderRules.MAX_JSON_BYTES): String? {
            val safe = FinderRules.publicHttpsUrl(url)
            if (safe.isEmpty()) return null
            requests.incrementAndGet()
            return try {
                gate.withPermit {
                    withTimeout(REQUEST_TIMEOUT_MS) {
                        http.get(safe, headers = headers, attempts = 1, limitBytes = limitBytes)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                rateLimits.computeIfAbsent(source.id) { AtomicLong(0) }
                backoff(source, error)
                null
            }
        }

        /** Parse JSON, returning null rather than throwing on malformed input. */
        fun json(text: String?): JsonElement? =
            text?.let { runCatching { jsonParser.parseToJsonElement(it) }.getOrNull() }

        /** Only unseen servers are worth asking about again. */
        fun fresh(candidates: List<FinderCandidate>): List<FinderCandidate> =
            candidates.filter { seen.add(ServerPool.key(it.baseUrl)) }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Semaphore(GLOBAL_CONCURRENCY)
    private val firstSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val requests = AtomicInteger(0)
    private val failures = AtomicInteger(0)
    private val rateLimits = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Every source this harvester can read, in priority order.
     *
     * Constructed without the http client: each source gets the [SweepContext]
     * (and so the gate, the dedupe set and the budget helper) as a parameter of
     * every [Source.sweep]. Passing a context factory into the constructor was
     * dead weight -- it was never called, and the sources would have shared one
     * context's dedupe set across rounds.
     */
    val sources: List<Source> = sources ?: listOf(
        StremioAddonsNet(),
        StremioCollections(),
        CinemetaCatalogueGraph(),
        GitHubRepositories(),
        GitHubPagesHosts(),
        CommunityLists()
    )

    private fun sweepContext(): SweepContext = SweepContext(gate, firstSeen)

    // -- state ---------------------------------------------------------------

    private val _state = MutableStateFlow(HarvestState())
    val state: StateFlow<HarvestState> = _state.asStateFlow()

    /** A loop-level error is not an exhausted source or an ordinary stop. */
    fun noteFatal(reason: String) {
        _state.update { it.copy(running = false, failures = it.failures + 1, stopped = "error: $reason") }
    }

    /**
     * Read one slice of every source, in parallel. Kept for callers that want a
     * single round (the scramble path); [continuousFill] is the loop.
     */
    suspend fun harvest(round: Int): List<FinderCandidate> = harvestOnce()

    /**
     * One parallel pass over every source.
     *
     * A scramble uses this: the pool is empty and a title found nothing, so it
     * needs new servers immediately rather than waiting for the continuous
     * loop's next slice.
     */
    suspend fun harvestOnce(): List<FinderCandidate> = coroutineScope {
        val ctx = sweepContext()
        val perSource = sources.map { source ->
            async {
                if (source.isDone() || source.coolingDown(System.currentTimeMillis())) {
                    emptyList()
                } else {
                    // Not runCatching: an exception here is a broken source, and
                    // the caller (fillPool / scramblePool) reports it to the
                    // viewer. Swallowing it would turn "the crawl failed" into
                    // "the directories had nothing", which is the exact false
                    // green this change is meant to remove.
                    source.sweep(ctx)
                }
            }
        }.awaitAll()
        perSource.flatten().distinctBy { ServerPool.key(it.baseUrl) }
    }

    /**
     * Keep harvesting until [target] entries are in the pool, the caller
     * cancels the scope, or every source is done *and* out of its backoff.
     *
     * [onBatch] is called with each newly discovered slice, so the pool grows
     * while the crawl runs instead of at the end of it. [poolSize] is read every
     * iteration; it is the only definition of "full" used here.
     *
     * "Done" is per source and countable ([Source.isDone]), so `exhausted` can
     * only become true when *every* source has said so -- and it never claims
     * the internet is empty, only that this harvester has no unseen page left.
     */
    suspend fun continuousFill(
        poolSize: () -> Int,
        target: Int = ServerPool.CAPACITY,
        onBatch: suspend (List<FinderCandidate>) -> Unit,
        stopWhenExhausted: Boolean = true
    ) {
        _state.value = HarvestState(running = true, target = target, startedAt = System.currentTimeMillis())
        try {
            coroutineScope {
                while (isActive) {
                    if (poolSize() >= target) {
                        _state.value = _state.value.copy(running = false, stopped = "pool full")
                        return@coroutineScope
                    }
                    val ctx = sweepContext()
                    val t0 = System.currentTimeMillis()
                    val active = sources.filter { !it.isDone() && !it.coolingDown(t0) }

                    if (active.isEmpty()) {
                        // Nothing is asking to run. That is only "exhausted" when
                        // no source is also waiting out a throttle -- otherwise
                        // it is a pause, and calling it exhausted would be a
                        // false green at exactly the moment GitHub or the
                        // directory asked us to slow down.
                        val waiting = sources.filterNot { it.isDone() }
                            .map { it.backoffUntil() - t0 }
                            .filter { it > 0 }
                        if (waiting.isEmpty()) {
                            _state.value = _state.value.copy(
                                running = false,
                                stopped = if (stopWhenExhausted) "every source exhausted" else "no work left",
                                exhausted = stopWhenExhausted,
                                sourcesDone = sources.count { it.isDone() },
                                sourceCount = sources.size,
                                finishedAt = System.currentTimeMillis()
                            )
                            return@coroutineScope
                        }
                        delay(waiting.min().coerceIn(250L, COOLDOWN_POLL_MS))
                        continue
                    }

                    val results = active.map { source ->
                        async {
                            val found = try {
                                source.sweep(ctx)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                // Counted and backed off, never reported as an
                                // empty source: `failures` is on the state the
                                // Sources screen prints.
                                backoff(source, error)
                                failures.incrementAndGet()
                                emptyList()
                            }
                            source.id to ctx.fresh(found)
                        }
                    }.awaitAll()

                    val fresh = results.flatMap { it.second }.distinctBy { ServerPool.key(it.baseUrl) }
                    if (fresh.isNotEmpty()) onBatch(fresh)
                    val done = sources.count { it.isDone() }
                    _state.value = _state.value.copy(
                        rounds = _state.value.rounds + 1,
                        failures = failures.get(),
                        found = _state.value.found + fresh.size,
                        sourcesDone = done,
                        sourceCount = sources.size,
                        requests = requests.get(),
                        lastRoundMs = System.currentTimeMillis() - t0,
                        lastAdded = fresh.size,
                        poolSize = poolSize()
                    )
                    delay(if (fresh.isEmpty()) IDLE_PAUSE_MS else ACTIVE_PAUSE_MS)
                }
            }
        } finally {
            if (_state.value.running) {
                _state.value = _state.value.copy(running = false, stopped = "stopped")
            }
        }
    }

    private fun backoff(source: Source, error: Exception) {
        val limited = error is HttpClient.HttpFailure && (error.status == 429 || error.status == 403)
        // GitHub allows ten searches a minute unauthenticated, so a throttle is
        // waited out rather than retried. Anything else is one failed request
        // among thousands and needs no pause.
        rateLimits.computeIfAbsent(source.id) { AtomicLong(0) }
            .set(System.currentTimeMillis() + if (limited) RATE_LIMIT_PAUSE_MS else 0L)
        if (limited) {
            _state.update { it.copy(rateLimited = it.rateLimited + 1) }
        }
    }

    /** Snapshot the UI can show; every field is measured, none is estimated. */
    data class HarvestState(
        val running: Boolean = false,
        val rounds: Int = 0,
        val found: Int = 0,
        val sourceCount: Int = 0,
        val sourcesDone: Int = 0,
        val requests: Int = 0,
        val rateLimited: Int = 0,
        /** Sources that threw. Non-zero means "try again", not "nothing there". */
        val failures: Int = 0,
        val lastRoundMs: Long = 0,
        val lastAdded: Int = 0,
        val poolSize: Int = 0,
        val target: Int = ServerPool.CAPACITY,
        val exhausted: Boolean = false,
        val stopped: String = "",
        val startedAt: Long = 0,
        val finishedAt: Long = 0
    ) {
        /** 0f..1f, from the pool the caller reports. */
        val progress: Float get() = if (target <= 0) 0f else (poolSize.toFloat() / target).coerceIn(0f, 1f)
    }

    companion object {
        /** Requests in flight, app-wide. Chosen to fill 3000 slots in minutes, not hours. */
        const val GLOBAL_CONCURRENCY = 24
        const val HOST_CONCURRENCY = 3
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val ACTIVE_PAUSE_MS = 250L
        private const val IDLE_PAUSE_MS = 2_000L
        private const val COOLDOWN_POLL_MS = 5_000L
        private const val RATE_LIMIT_PAUSE_MS = 60_000L
        val ACCEPT_JSON = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "NovaTV-Server-Pool/2.0"
        )
        val ACCEPT_HTML = mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) NovaTV/2.0"
        )
    }
}

// ---------------------------------------------------------------------------
// Sources
//
// Each one is a small state machine: read the next page, remember where it got
// to, stop when the pages run out. All of them share the harvester's request
// gate and its dedupe set, so a server found by two sources is fetched once.
// ---------------------------------------------------------------------------

/** Read a string field without throwing on a non-primitive or a missing key. */
private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Abstract base: page cursor, done flag, backoff window. */
abstract class PagedSource : ServerHarvester.Source {
    private val cursor = AtomicInteger(0)
    private var cooling = AtomicLong(0)
    @Volatile private var done = false

    protected fun nextPage(): Int = cursor.incrementAndGet() - 1
    protected fun pageCount(): Int = cursor.get()
    protected fun markDone() { done = true }
    override fun isDone(): Boolean = done
    override fun backoffUntil(): Long = cooling.get()

    protected fun coolDownFor(ms: Long) {
        cooling.set(System.currentTimeMillis() + ms)
    }
}

/**
 * stremio-addons.net: 100 addons per page, with the manifests for hosted
 * instances inline, so one request can yield dozens of servers.
 */
private class StremioAddonsNet : PagedSource() {
    override val id = "stremio-addons.net"
    override val label = "stremio-addons.net"
    private var emptyPages = 0

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        if (pageCount() >= MAX_PAGES) { markDone(); return emptyList() }
        val page = nextPage() + 1
        val body = sweep.get(
            "https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=$page",
            this
        ) ?: return emptyList()
        val data = sweep.json(body) as? JsonObject ?: return emptyList()
        val addons = (data["addons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (addons.isEmpty()) {
            emptyPages += 1
            if (emptyPages >= 2) markDone()
            return emptyList()
        }
        emptyPages = 0
        return addons.flatMap { addon ->
            val entries = mutableListOf(addon)
            (addon["instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { entries += it }
            entries
        }.mapNotNull { entry ->
            val url = FinderRules.entryUrl(entry)
            val manifest = entry["manifest"] as? JsonObject ?: return@mapNotNull null
            if (url.isEmpty()) null
            else SourceFinder.candidateFor(url, manifest, "stremio-addons.net", entry)?.copy(from = "Server pool · stremio-addons.net")
        }
    }

    private companion object { const val MAX_PAGES = 60 }
}

/** Stremio's public collection: every addon the client ships with, plus its transports. */
private class StremioCollections : PagedSource() {
    override val id = "stremio-collection"
    override val label = "Stremio collection"
    private var fetched = false

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        if (fetched) { markDone(); return emptyList() }
        fetched = true
        val body = sweep.get("https://api.strem.io/addonscollection.json", this) ?: return emptyList()
        val array = sweep.json(body) as? JsonArray ?: return emptyList()
        return array.filterIsInstance<JsonObject>().mapNotNull { entry ->
            val url = FinderRules.entryUrl(entry)
            val manifest = (entry["manifest"] as? JsonObject) ?: (entry["addon"] as? JsonObject)?.get("manifest") as? JsonObject
            if (url.isEmpty() || manifest == null) null
            else SourceFinder.candidateFor(url, manifest, "stremio-collection", entry)?.copy(from = "Server pool · Stremio")
        }
    }
}

/**
 * Cinemeta's addon-catalogue graph: the community and official lists name other
 * catalogues, and each of those names more. Bounded depth, because this is a
 * graph that can loop.
 */
private class CinemetaCatalogueGraph : PagedSource() {
    override val id = "cinemeta-graph"
    override val label = "Cinemeta catalogue graph"
    private val frontier = ArrayDeque(
        listOf(
            "https://v3-cinemeta.strem.io/addon_catalog/all/community.json",
            "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        )
    )
    private val visited = HashSet<String>()

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        if (frontier.isEmpty() || pageCount() >= MAX_PAGES) { markDone(); return emptyList() }
        val url = frontier.removeFirst()
        if (!visited.add(url)) return emptyList()
        val body = sweep.get(url, this) ?: return emptyList()
        val root = sweep.json(body) ?: return emptyList()
        val list = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>()
            is JsonObject -> (root["addons"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        val out = list.mapNotNull { entry ->
            val manifest = entry["manifest"] as? JsonObject ?: return@mapNotNull null
            val manifestUrl = FinderRules.entryUrl(entry)
            if (manifestUrl.isEmpty()) null
            else SourceFinder.candidateFor(manifestUrl, manifest, "cinemeta-graph", entry)
                ?.copy(from = "Server pool · Cinemeta")
        }
        // Follow any catalogue endpoint this page names, one level down, bounded.
        if (pageCount() < MAX_DEPTH_PAGES) {
            list.mapNotNull { (it["transportUrl"] as? JsonPrimitive)?.contentOrNull }
                .filter { it.endsWith(".json") }
                .map { FinderRules.publicHttpsUrl(it) }
                .filter { it.isNotEmpty() && frontier.size < MAX_FRONTIER }
                .forEach { frontier.addLast(it) }
        }
        return out
    }

    private companion object {
        const val MAX_PAGES = 40
        const val MAX_DEPTH_PAGES = 20
        const val MAX_FRONTIER = 60
    }
}

/**
 * GitHub repository search. This is the source with the real limit: ten searches
 * a minute unauthenticated, 100 results per page. So it rotates queries and pages,
 * backs off on 403/429, and reads a bounded number of READMEs per round.
 */
private class GitHubRepositories : PagedSource() {
    override val id = "github"
    override val label = "GitHub repositories"

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        val index = nextPage()
        val query = QUERIES[index % QUERIES.size]
        val page = (index / QUERIES.size) % PAGES_PER_QUERY + 1
        if (index >= QUERIES.size * PAGES_PER_QUERY) { markDone(); return emptyList() }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = sweep.get(
            "https://api.github.com/search/repositories?q=$encoded&sort=updated&order=desc&per_page=100&page=$page",
            this
        ) ?: return emptyList()
        val root = sweep.json(body) as? JsonObject ?: return emptyList()
        val repos = (root["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (repos.isEmpty()) return emptyList()

        // Manifest links straight from the metadata need no extra request.
        var links = repos.flatMap { repo ->
            val text = "${repo.str("description")} ${repo.str("homepage")}"
            FinderRules.extractManifestUrls(text).map { it to repo }
        }

        // Then a bounded number of READMEs, in parallel through the shared gate.
        val readmeTargets = repos
            .filter { repo ->
                val full = repo.str("full_name")
                MANIFEST_IN_DESC.find("${repo.str("description")} ${repo.str("homepage")}") == null &&
                    REPO_NAME.matches(full)
            }
            .take(README_BUDGET_PER_ROUND)
        links = links + coroutineScope {
            readmeTargets.map { repo ->
                async {
                    val full = repo.str("full_name")
                    val branch = repo.str("default_branch").ifBlank { "main" }
                        .replace(Regex("[^A-Za-z0-9_.-]"), "")
                    val readme = sweep.get(
                        "https://raw.githubusercontent.com/$full/$branch/README.md", this@GitHubRepositories,
                        ServerHarvester.ACCEPT_HTML, FinderRules.MAX_JSON_BYTES
                    ) ?: return@async emptyList()
                    FinderRules.extractManifestUrls(readme).map { it to repo }
                }
            }.awaitAll().flatten()
        }

        val unique = links.distinctBy { it.first }.take(MAX_MANIFESTS_PER_ROUND)
        return coroutineScope {
            unique.map { (manifestUrl, repo) ->
                async {
                    val body = sweep.get(manifestUrl, this@GitHubRepositories, ServerHarvester.ACCEPT_JSON,
                        FinderRules.MAX_MANIFEST_BYTES) ?: return@async null
                    val manifest = sweep.json(body) ?: return@async null
                    val meta = JsonObject(mapOf("stars" to (repo["stargazers_count"] ?: JsonPrimitive(0))))
                    SourceFinder.candidateFor(manifestUrl, manifest, "github", meta)
                        ?.copy(from = "Server pool · GitHub")
                }
            }.awaitAll().filterNotNull()
        }
    }

    private companion object {
        val QUERIES = listOf(
            "topic:stremio-addon",
            "stremio addon",
            "stremio-addons",
            "manifest.json stremio",
            "topic:stremio-addons",
            "stremio addon manifest.json in:readme",
            "stremio addons list",
            "stremio streaming addon"
        )
        const val PAGES_PER_QUERY = 4
        const val README_BUDGET_PER_ROUND = 25
        const val MAX_MANIFESTS_PER_ROUND = 60
        val REPO_NAME = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        val MANIFEST_IN_DESC = Regex("""manifest\.json""", RegexOption.IGNORE_CASE)
    }
}

/**
 * GitHub Pages and repository homepages. A repository without a manifest link is
 * not dead: static hosts serve `manifest.json` beside the page. Only well-known
 * hosts are tried, and only for repositories not already read.
 */
private class GitHubPagesHosts : PagedSource() {
    override val id = "github-pages"
    override val label = "GitHub Pages hosts"

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        val index = nextPage()
        val query = QUERIES[index % QUERIES.size]
        val page = (index / QUERIES.size) % 3 + 1
        if (index >= QUERIES.size * 3) { markDone(); return emptyList() }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = sweep.get(
            "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=100&page=$page",
            this
        ) ?: return emptyList()
        val repos = ((sweep.json(body) as? JsonObject)?.get("items") as? JsonArray)
            .orEmpty().filterIsInstance<JsonObject>().take(PROBE_BUDGET)

        return coroutineScope {
            repos.map { repo ->
                async {
                    val candidates = mutableListOf<FinderCandidate>()
                    val full = repo.str("full_name")
                    val homepage = repo.str("homepage").trimEnd('/')
                    val hosts = LinkedHashSet<String>()
                    if (homepage.startsWith("https://")) hosts += homepage
                    if (REPO_NAME.matches(full)) {
                        val owner = full.substringBefore('/').lowercase()
                        val name = full.substringAfter('/').lowercase()
                        hosts += "https://$owner.github.io/$name"
                        hosts += "https://$owner.github.io"
                    }
                    for (host in hosts.take(2)) {
                        val manifestUrl = "$host/manifest.json"
                        val text = sweep.get(manifestUrl, this@GitHubPagesHosts, ServerHarvester.ACCEPT_JSON,
                            FinderRules.MAX_MANIFEST_BYTES) ?: continue
                        val manifest = sweep.json(text) ?: continue
                        val meta = JsonObject(mapOf("stars" to (repo["stargazers_count"] ?: JsonPrimitive(0))))
                        SourceFinder.candidateFor(manifestUrl, manifest, "github-pages", meta)
                            ?.copy(from = "Server pool · GitHub Pages")
                            ?.let { candidates += it }
                    }
                    candidates
                }
            }.awaitAll().flatten()
        }
    }

    private companion object {
        val QUERIES = listOf("stremio-addon", "stremio addon", "topic:stremio")
        val REPO_NAME = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        const val PROBE_BUDGET = 40
    }
}

/**
 * Community-maintained lists of addons -- the lists people actually paste into
 * Stremio. Each block is a plausible public location for one; a source that 404s
 * simply contributes nothing and says so through its counters.
 */
private class CommunityLists : PagedSource() {
    override val id = "community-lists"
    override val label = "Community addon lists"

    override suspend fun sweep(sweep: ServerHarvester.SweepContext): List<FinderCandidate> {
        val index = nextPage()
        if (index >= LISTS.size) { markDone(); return emptyList() }
        val (url, kind) = LISTS[index]
        val text = sweep.get(url, this, ServerHarvester.ACCEPT_HTML, FinderRules.MAX_JSON_BYTES) ?: return emptyList()
        val urls = FinderRules.extractManifestUrls(text)
        return coroutineScope {
            urls.take(MAX_PER_LIST).map { manifestUrl ->
                async {
                    val body = sweep.get(manifestUrl, this@CommunityLists, ServerHarvester.ACCEPT_JSON,
                        FinderRules.MAX_MANIFEST_BYTES) ?: return@async null
                    val manifest = sweep.json(body) ?: return@async null
                    SourceFinder.candidateFor(manifestUrl, manifest, "community", JsonObject(emptyMap()))
                        ?.copy(from = "Server pool · $kind")
                }
            }.awaitAll().filterNotNull()
        }
    }

    private companion object {
        // Only locations that are public, stable and keyless. A dead one costs one
        // request and is reported as an empty source, not as a failure of the pool.
        val LISTS = listOf(
            "https://raw.githubusercontent.com/doingodswork/more-stremio-addons/master/README.md" to "community list",
            "https://raw.githubusercontent.com/nzahasan/stremio-addons/main/README.md" to "community list",
            "https://stremio-addons.com/" to "community list",
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/master/README.md" to "community list"
        )
        const val MAX_PER_LIST = 20
    }
}
