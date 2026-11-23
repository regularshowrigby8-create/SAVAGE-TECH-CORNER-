package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.repo.LibraryStore
import dev.nova.tv.finder.website.WebsiteFinder
import dev.nova.tv.finder.website.WebsiteRules
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.finder.pool.WebsitePool
import dev.nova.tv.finder.website.WebsiteSpider
import dev.nova.tv.player.HostHealth
import dev.nova.tv.player.ProbeResult
import dev.nova.tv.player.StreamProbe
import dev.nova.tv.player.StreamRanker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The three finders the user can switch on and off.
 *
 *  - [ZEROPLAY] is the **website** finder: it finds ordinary streaming sites,
 *    the same kind "Add website" saves, and searches them by title. It does
 *    not look for addons.
 *  - [STREMIO] is the **addon** finder: Stremio manifests, asked for
 *    `/stream/...`.
 *  - [TITLE] asks every known source for the exact title and episode.
 *
 * The two server pools follow that split, so switching one finder off leaves
 * the other one serving playback.
 */
enum class FinderKind(val label: String) {
    ZEROPLAY("Website finder"),
    STREMIO("Addon finder"),
    TITLE("Title finder")
}

/** What the engine is asked: one film, or one episode. */
data class TitleRequest(
    val meta: MetaPreview,
    val season: Int = 0,
    val episode: Int = 0,
    val finders: Set<FinderKind> = FinderKind.entries.toSet(),
    val preferDualAudio: Boolean = false,
    /** Standard play target, from Settings; 720p by default. */
    val preferredQuality: String = StreamRanker.DEFAULT_QUALITY
) {
    val type: String get() = if (meta.isSeries) "series" else "movie"
    val streamId: String get() = FinderRules.catalogueId(meta.id, meta.isSeries, season, episode)
}

/** One progressive update. The list is always ranked and always playable. */
data class SourceSnapshot(
    val streams: List<Stream> = emptyList(),
    val probes: Map<String, ProbeResult> = emptyMap(),
    /** DNS resolution latency per stream URL, used for the ping column. */
    val pings: Map<String, Long> = emptyMap(),
    val running: Set<String> = emptySet(),
    val installed: List<String> = emptyList(),
    val done: Boolean = false
) {
    /** bits/s to seed the player with, from the top source's probe. */
    val bandwidthEstimate: Long get() = StreamRanker.bandwidthEstimate(streams.firstOrNull()?.url?.let { probes[it] })
}

/**
 * The multi-threaded source engine.
 *
 * Opening a title starts every source at once on a bounded IO pool and merges
 * results as they arrive, so the Play button is live after the first answer
 * rather than after the slowest one:
 *
 *   addon pool ────────┐  (installed addons + discovered manifests, /stream/…)
 *   website pool ──────┼─► merge ─► size cap / no torrents ─► probe ─► rank ─► UI
 *   user's websites ───┤  (searched like a pooled site, always on)
 *   title finder ──────┘  (verify every known addon candidate)
 *
 * Discovery is cached for [DISCOVERY_TTL_MS], so after the first title the
 * finders cost one `/stream` call per candidate, not a directory read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceEngine(
    private val repo: CatalogRepository,
    private val stremio: StremioFinder,
    private val websites: WebsiteFinder,
    private val verifier: SourceFinder,
    private val library: LibraryStore,
    private val probe: StreamProbe,
    /** The 6000-slot addon pool; null in tests that do not need it. */
    val pool: ServerPool? = null,
    private val harvester: ServerHarvester? = null,
    /** The 6000-slot website pool: real sites, not addon manifests. */
    val websitePool: WebsitePool? = null,
    private val spider: WebsiteSpider? = null,
    /** DNS latency per host, injectable so the engine stays testable. */
    private val ping: suspend (String) -> Long = { host -> dev.nova.tv.data.remote.NovaDns.pingMs(host) }
) {
    /** At most [PARALLELISM] network calls at once, across every finder. */
    private val io = Dispatchers.IO.limitedParallelism(PARALLELISM)
    private val background = CoroutineScope(SupervisorJob() + io)

    private val discoveryLock = Mutex()
    private val discovered = java.util.concurrent.ConcurrentHashMap<FinderKind, Pair<Long, FinderReport>>()

    /**
     * Finders the viewer currently has switched on.
     *
     * Discovery results are kept per finder even after a finder is switched
     * off (so flipping it back on is instant), but only the active ones may
     * contribute candidates to the shared pool. Without this filter a finder
     * that had been turned off kept feeding its hosts into the 6000 slots.
     */
    @Volatile
    private var activeKinds: Set<FinderKind> = FinderKind.entries.toSet()
    private val inflight = HashMap<FinderKind, Deferred<FinderReport>>()

    /** Start directory reads in the background at launch, so the first title is quick. */
    fun warmUp(kinds: Set<FinderKind>) {
        background.launch { kinds.filter { it != FinderKind.TITLE }.forEach { kind -> launch { discover(kind) } } }
        syncPoolFill(kinds)
    }

    private var addonFillJob: Job? = null
    private var siteFillJob: Job? = null
    /** Website discovery pages already consumed, across pool rounds. */
    private var webRound = 0

    /**
     * Keep both pools filling, each from its own finder.
     *
     * The pools are independent on purpose: the website pool holds ordinary
     * streaming sites ([WebsiteSpider]) and the addon pool holds Stremio
     * manifests ([ServerHarvester]). Switching a finder off fills nothing and
     * breaks nothing -- the other pool keeps serving playback, and the pool
     * that was switched off is simply not consulted.
     */
    fun syncPoolFill(kinds: Set<FinderKind>) {
        activeKinds = kinds
        addonFillJob?.cancel(); addonFillJob = null
        siteFillJob?.cancel(); siteFillJob = null
        val addonPool = pool
        if (addonPool != null && harvester != null && FinderKind.STREMIO in kinds) {
            addonFillJob = background.launch { fillAddonPool(addonPool) }
        }
        val sites = websitePool
        if (sites != null && spider != null && FinderKind.ZEROPLAY in kinds) {
            siteFillJob = background.launch { fillWebsitePool(sites) }
        }
    }

    private suspend fun fillAddonPool(pool: ServerPool) {
        var emptyRounds = 0
        // Keeps going until the 6000 slots are full. "The directories returned
        // nothing" is not the end of the search -- there are millions of
        // addons -- so an empty round backs off and tries different queries,
        // and after a long dry spell the retirement list is cleared so hosts
        // that came back are reachable again.
        while (currentCoroutineContext().isActive && pool.state.value.size < ServerPool.CAPACITY) {
            val before = pool.state.value.size
            runCatching { fillPool() }
            runCatching { pool.pruneDead() }
            val after = pool.state.value.size
            if (after > before) {
                emptyRounds = 0
            } else {
                emptyRounds++
                if (emptyRounds % POOL_STALE_ROUNDS == 0) runCatching { pool.clearRetired() }
            }
            val pause = if (after > before) POOL_FILL_PAUSE_MS
            else (POOL_RETRY_PAUSE_MS shl (emptyRounds.coerceAtMost(5) - 1).coerceAtLeast(0))
            delay(pause.coerceAtMost(POOL_MAX_PAUSE_MS))
        }
    }

    /**
     * Website discovery runs in verified rounds and ends when the public lists
     * have been consumed: unlike addons, there is a finite set of real
     * streaming sites, so re-probing the same hosts forever would be waste.
     */
    private suspend fun fillWebsitePool(sites: WebsitePool) {
        var failures = 0
        while (currentCoroutineContext().isActive && sites.state.value.size < WebsitePool.CAPACITY) {
            val before = sites.state.value.size
            val harvest = runCatching {
                withTimeoutOrNull(WEBSITE_ROUND_BUDGET_MS) { spider?.harvest(webRound) }
            }.getOrNull()
            webRound++
            if (harvest == null) {
                failures++
                if (failures >= WEBSITE_MAX_FAILURES) return
                delay(POOL_RETRY_PAUSE_MS shl (failures - 1).coerceAtMost(4))
                continue
            }
            failures = 0
            sites.add(harvest.sites, from = "Website finder")
            runCatching { sites.pruneDead() }
            if (!harvest.more && sites.state.value.size == before) return
            if (sites.state.value.size > before) delay(POOL_FILL_PAUSE_MS)
        }
    }

    /** One website-discovery round, for the Sources screen's "Find sites" button. */
    suspend fun fillWebsitePoolOnce(): Int {
        val sites = websitePool ?: return 0
        val harvest = spider?.harvest(webRound) ?: return 0
        webRound++
        return sites.add(harvest.sites, from = "Website finder")
    }

    private val harvestLock = Mutex()

    /** Read the next slice of every directory into the pool (one round). */
    suspend fun fillPool(): Int {
        val pool = pool ?: return 0
        val harvester = harvester ?: return 0
        return harvestLock.withLock {
            if (pool.state.value.size >= ServerPool.CAPACITY) return@withLock 0
            val round = pool.cursor
            val found = runCatching { harvester.harvest(round) }.getOrDefault(emptyList())
            pool.cursor = round + 1
            pool.add(found + cachedCandidates())
        }
    }

    /**
     * Swap in new server options: harvest the next directory slice, then let
     * the pool retire the dead ones and rotate the new ones in. Returns how
     * many options are new.
     */
    suspend fun scramblePool(): Int {
        val pool = pool ?: return 0
        val harvester = harvester ?: return 0
        return harvestLock.withLock {
            val round = pool.cursor
            val fresh = runCatching { harvester.harvest(round) }.getOrDefault(emptyList())
            pool.cursor = round + 1
            pool.scramble(fresh + cachedCandidates())
        }
    }

    /** Back to the previous pool. */
    fun revertPool(): Boolean = pool?.revert() ?: false

    /** One finder's discovery, cached and shared between concurrent callers. */
    suspend fun discover(kind: FinderKind, force: Boolean = false): FinderReport {
        val job = discoveryLock.withLock {
            val cached = discovered[kind]
            if (!force && cached != null && System.currentTimeMillis() - cached.first < DISCOVERY_TTL_MS) return cached.second
            inflight[kind]?.takeIf { it.isActive } ?: background.async {
                val installed = repo.addons.value.map { it.baseUrl }.toSet()
                when (kind) {
                    // The website finder discovers sites, not manifests: one
                    // verified round of the spider, merged into its own pool.
                    FinderKind.ZEROPLAY -> {
                        val harvest = spider?.let { runCatching { it.harvest(webRound++) }.getOrNull() }
                        websitePool?.add(harvest?.sites.orEmpty(), from = "Website finder")
                        FinderReport(emptyList(), harvest?.sources.orEmpty())
                    }
                    FinderKind.STREMIO -> stremio.find(exclude = installed)
                    FinderKind.TITLE -> FinderReport(emptyList(), emptyList())
                }
            }.also { inflight[kind] = it }
        }
        val report = job.await()
        discoveryLock.withLock { discovered[kind] = System.currentTimeMillis() to report; inflight.remove(kind) }
        pool?.add(report.candidates)
        return report
    }

    fun cachedCandidates(): List<FinderCandidate> =
        discovered
            .filterKeys { it in activeKinds }
            .values
            .flatMap { it.second.candidates }
            .distinctBy { it.baseUrl.lowercase() }

    fun report(kind: FinderKind): FinderReport? = discovered[kind]?.second

    fun resolve(request: TitleRequest): Flow<SourceSnapshot> = channelFlow {
        val state = EngineState(request)
        val last = library.lastSource(request.meta.id)
        state.ctx = StreamRanker.Context(
            runtimeSeconds = request.meta.runtimeSeconds,
            preferredGroup = last?.bingeGroup,
            preferredSource = last?.sourceBase?.ifBlank { null },
            hostScore = HostHealth::score,
            preferDualAudio = request.preferDualAudio,
            preferredQuality = request.preferredQuality
        )

        coroutineScope {
            // 1. Installed addons: the fast path. They belong to the addon
            //    pool, so switching the addon finder off leaves a website-only
            //    source list instead of quietly serving addon streams anyway.
            if (FinderKind.STREMIO in request.finders) {
                launch {
                    state.track("addons") {
                        state.add(runCatching { repo.loadStreams(request.type, request.streamId) }.getOrDefault(emptyList()))
                        publish(state)
                        // Probe the first answers straight away; Play is ranked by health.
                        state.probe(probe, ping)
                    }
                    publish(state)
                }
            }

            // 2. Website finder: search the website pool for this title. These
            //    are ordinary sites -- the same kind "Add website" saves.
            if (FinderKind.ZEROPLAY in request.finders) {
                launch {
                    state.track(FinderKind.ZEROPLAY.label) { searchSites(state, websitePool) }
                    publish(state)
                }
            }

            // 3. Addon finder: discover Stremio manifests, then verify this id.
            if (FinderKind.STREMIO in request.finders) {
                launch {
                    state.track(FinderKind.STREMIO.label) {
                        val report = withTimeoutOrNull(DISCOVERY_BUDGET_MS) { discover(FinderKind.STREMIO) }
                        if (report != null) verifyInto(state, report.candidates)
                    }
                    publish(state)
                }
            }

            // 4. Title finder: every candidate any finder has ever seen, against
            //    the exact title and episode, plus the user's websites.
            if (FinderKind.TITLE in request.finders) {
                launch {
                    state.track(FinderKind.TITLE.label) {
                        // Cached addon candidates are already filtered to the
                        // finder that is on, and the addon pool is only worth
                        // asking while the addon finder is enabled.
                        verifyInto(state, cachedCandidates())
                        if (FinderKind.STREMIO in request.finders) {
                            pool?.let { verifyInto(state, it.batch(POOL_BATCH) { base -> state.isClaimed(base) }, POOL_BATCH) }
                        }
                    }
                    publish(state)
                }
                // The viewer's own websites are always searched: they added them
                // by hand, so no finder switch should silently drop them.
                for (site in library.websites.value) {
                    launch {
                        state.track(site.name) {
                            val query = WebsiteRules.Query(
                                titles = listOfNotNull(request.meta.name),
                                type = request.type,
                                year = request.meta.year.orEmpty().take(4),
                                season = request.season.coerceAtLeast(1),
                                episode = request.episode.coerceAtLeast(1),
                                imdb = request.meta.id.substringBefore(':')
                            )
                            state.add(websites.findStreams(site, query).filter { it.url != null })
                        }
                        publish(state)
                    }
                }
            }
        }
        // Nothing anywhere: scramble the addon pool once and try the new servers.
        if (FinderKind.TITLE in request.finders && FinderKind.STREMIO in request.finders && pool != null && state.isEmpty()) {
            state.track(SCRAMBLE_LABEL) {
                publish(state)
                scramblePool()
                verifyInto(state, pool.batch(POOL_BATCH) { base -> state.isClaimed(base) }, POOL_BATCH)
            }
        }
        pool?.flush()
        // Everything has answered: probe whatever is now on top, then finish.
        state.probe(probe, ping)
        send(state.snapshot(done = true))
    }.flowOn(io)

    private suspend fun ProducerScope<SourceSnapshot>.publish(state: EngineState) {
        send(state.snapshot(done = false))
    }

    /**
     * Ask candidates for this exact id, [VERIFY_CONCURRENCY] at a time; every
     * hit is merged immediately and the best few are installed so the next
     * title does not need a finder at all.
     */
    private suspend fun ProducerScope<SourceSnapshot>.verifyInto(
        state: EngineState,
        candidates: List<FinderCandidate>,
        limit: Int = MAX_VERIFY
    ) {
        val request = state.request
        val todo = candidates
            .filter { it.acceptsImdbIds && FinderRules.manifestSupports(listOf("stream"), it.types, request.type) }
            .filterNot { repo.isInstalled(it.baseUrl) }
            .filter { state.claim(it.baseUrl) }
            .sortedByDescending { it.stars }
            .take(limit)
        val gate = Semaphore(VERIFY_CONCURRENCY)
        coroutineScope {
            todo.map { candidate ->
                async {
                    gate.withPermit {
                        val hit = verifier.verify(listOf(candidate), request.type, request.streamId).firstOrNull()
                        this@SourceEngine.pool?.noteResult(candidate.baseUrl, hit != null)
                        if (hit != null) {
                            state.add(hit.streams.filter { it.isPlayable })
                            if (state.reserveInstall()) {
                                repo.add(candidate.baseUrl).onSuccess { state.noteInstalled(it.name) }
                            }
                            publish(state)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Search pooled websites for this title, in parallel. A site that answers
     * is marked as working; one that misses twice without ever answering is
     * retired, so the website pool improves as titles are opened.
     */
    private suspend fun ProducerScope<SourceSnapshot>.searchSites(state: EngineState, sites: WebsitePool?) {
        val pool = sites ?: return
        // First run: there may be no sites yet, so discover one round of them
        // instead of returning nothing while the background fill catches up.
        if (pool.entries().isEmpty()) {
            withTimeoutOrNull(WEBSITE_COLD_START_MS) { fillWebsitePoolOnce() }
        }
        val batch = pool.batch(WEBSITE_BATCH) { url -> !state.claim(url) }
        if (batch.isEmpty()) return
        val query = state.websiteQuery()
        val gate = Semaphore(WEBSITE_CONCURRENCY)
        coroutineScope {
            batch.map { site ->
                async {
                    gate.withPermit {
                        // A site that is merely slow gets no miss recorded; only
                        // an answer with no streams tells us the site is bad.
                        val streams = withTimeoutOrNull(WEBSITE_SITE_TIMEOUT_MS) {
                            websites.findStreams(site, query)
                        }
                        if (streams != null) {
                            val playable = streams.filter { it.url != null }
                            pool.noteResult(site.url, playable.isNotEmpty())
                            if (playable.isNotEmpty()) {
                                state.add(playable)
                                publish(state)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Mutable per-request state; every access is under [lock]. */
    private class EngineState(val request: TitleRequest) {
        private val lock = Any()
        private val streams = LinkedHashMap<String, Stream>()
        private val probes = HashMap<String, ProbeResult>()
        private val running = LinkedHashSet<String>()
        private val claimed = HashSet<String>()
        private val installed = mutableListOf<String>()
        private var installs = 0
        @Volatile var ctx = StreamRanker.Context()
        /** URL -> DNS resolution latency, measured before probing. */
        private val pings = HashMap<String, Long>()

        fun add(found: List<Stream>) = synchronized(lock) {
            for (s in found) {
                val url = s.url?.trim() ?: continue
                if (!s.isPlayable) continue
                // A source that answers with a different film is worse than no
                // source: it looks like a hit and plays the wrong thing.
                if (!FinderRules.streamMatchesTitle(
                        s,
                        request.meta.name,
                        request.meta.year,
                        request.season,
                        request.episode
                    )
                ) continue
                streams.putIfAbsent(url + "|" + s.requestHeaders.hashCode(), s)
            }
        }

        fun noteInstalled(name: String) = synchronized(lock) { installed += name }

        fun claim(base: String) = synchronized(lock) { claimed.add(base.trimEnd('/').lowercase()) }

        fun isClaimed(base: String) = synchronized(lock) { base.trimEnd('/').lowercase() in claimed }

        fun isEmpty() = synchronized(lock) { streams.isEmpty() }

        /** The title/episode query every website search uses. */
        fun websiteQuery() = WebsiteRules.Query(
            titles = listOfNotNull(request.meta.name),
            type = request.type,
            year = request.meta.year.orEmpty().take(4),
            season = request.season.coerceAtLeast(1),
            episode = request.episode.coerceAtLeast(1),
            imdb = request.meta.id.substringBefore(':')
        )

        fun reserveInstall() = synchronized(lock) { if (installs < MAX_AUTO_INSTALL) { installs++; true } else false }

        suspend fun track(name: String, block: suspend () -> Unit) {
            synchronized(lock) { running += name }
            try { block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally { synchronized(lock) { running -= name } }
        }

        /** Probe the current top [StreamProbe.DEFAULT_LIMIT] that have not been probed yet. */
        suspend fun probe(probe: StreamProbe, ping: suspend (String) -> Long) {
            val todo = synchronized(lock) {
                StreamRanker.rank(streams.values.toList(), ctx.copy(probes = probes.toMap(), pings = pings.toMap()))
                    .filter { it.url !in probes }.take(StreamProbe.DEFAULT_LIMIT)
            }
            if (todo.isEmpty()) return
            // Resolve the hosts of the candidates first: the ranker's ping
            // weight then orders the list before the first byte is measured.
            val urls = todo.mapNotNull { it.url }.distinct()
            val measured = coroutineScope {
                urls.map { url -> async { url to ping(HostHealth.host(url)) } }.awaitAll()
            }
            synchronized(lock) { measured.forEach { (url, ms) -> if (ms >= 0) pings[url] = ms } }
            val results = probe.probeAll(todo)
            synchronized(lock) {
                probes.putAll(results)
                results.forEach { (url, result) -> if (result.dnsMs >= 0) pings[url] = result.dnsMs }
            }
        }

        fun snapshot(done: Boolean): SourceSnapshot = synchronized(lock) {
            val p = probes.toMap()
            val ranked = StreamRanker.rank(streams.values.toList(), ctx.copy(probes = p, pings = pings.toMap()))
            // "Only ones that work": once anything has been probed, a stream
            // that failed its probe is not shown. Before the first probe there
            // is nothing to filter by, so the discoverable list stays live.
            val working = ranked.filter { p[it.url]?.ok == true }
            SourceSnapshot(
                streams = (if (working.isNotEmpty()) working else ranked).take(MAX_SHOWN),
                probes = p,
                pings = pings.toMap(),
                running = running.toSet(),
                installed = installed.toList(),
                done = done
            )
        }
    }

    companion object {
        const val PARALLELISM = 32
        /** Empty rounds before the retirement list is cleared and retried. */
        private const val POOL_STALE_ROUNDS = 8
        private const val POOL_FILL_PAUSE_MS = 400L
        private const val POOL_RETRY_PAUSE_MS = 3_000L
        private const val POOL_MAX_PAUSE_MS = 60_000L
        private         const val VERIFY_CONCURRENCY = 24
        const val POOL_BATCH = 300
        /** Websites searched per title: enough to find one, cheap enough to open. */
        private const val WEBSITE_BATCH = 24
        private const val WEBSITE_CONCURRENCY = 10
        private const val WEBSITE_SITE_TIMEOUT_MS = 12_000L
        private const val WEBSITE_COLD_START_MS = 60_000L
        private const val WEBSITE_ROUND_BUDGET_MS = 120_000L
        private const val WEBSITE_MAX_FAILURES = 3
        const val SCRAMBLE_LABEL = "New servers"
        private const val MAX_VERIFY = 120
        /** The source sheet lists at most this many entries; the rest add noise. */
        private const val MAX_SHOWN = 24
        private const val MAX_AUTO_INSTALL = 3
        private const val DISCOVERY_TTL_MS = 30 * 60_000L
        private const val DISCOVERY_BUDGET_MS = 30_000L
    }
}
