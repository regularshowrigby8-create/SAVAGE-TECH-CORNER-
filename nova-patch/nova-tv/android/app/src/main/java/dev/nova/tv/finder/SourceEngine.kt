package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.repo.LibraryStore
import dev.nova.tv.finder.website.WebsiteFinder
import dev.nova.tv.finder.website.WebsiteRules
import dev.nova.tv.finder.website.SitePool
import dev.nova.tv.finder.website.SiteRecord
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.player.HostHealth
import dev.nova.tv.player.ProbeResult
import dev.nova.tv.player.StreamProbe
import dev.nova.tv.player.StreamRanker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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

/** The three finders the user can switch on and off. */
enum class FinderKind(val label: String) {
    ZEROPLAY("ZeroPlay finder"),
    STREMIO("Stremio finder"),
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
 *   installed addons ──┐
 *   ZeroPlay finder ───┤  (discover, then verify against this exact id)
 *   Stremio finder ────┼─► merge ─► size cap / no torrents ─► probe ─► rank ─► UI
 *   Title finder ──────┤  (verify every known candidate + the user's websites)
 *   websites ──────────┘
 *
 * Discovery is cached for [DISCOVERY_TTL_MS], so after the first title the
 * finders cost one `/stream` call per candidate, not a directory read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceEngine(
    private val repo: CatalogRepository,
    private val zeroPlay: SourceFinder,
    private val stremio: StremioFinder,
    private val websites: WebsiteFinder,
    private val verifier: SourceFinder,
    private val library: LibraryStore,
    private val probe: StreamProbe,
    /** The 3000-slot server pool; null in tests that do not need it. */
    val pool: ServerPool? = null,
    private val harvester: ServerHarvester? = null,
    private val sitePool: SitePool? = null
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
     * that had been turned off kept feeding its hosts into the 3000 slots.
     */
    @Volatile
    private var activeKinds: Set<FinderKind> = FinderKind.entries.toSet()
    private val inflight = HashMap<FinderKind, Deferred<FinderReport>>()

    /** Start directory reads in the background at launch, so the first title is quick. */
    fun warmUp(kinds: Set<FinderKind>) {
        background.launch { kinds.filter { it != FinderKind.TITLE }.forEach { kind -> launch { discover(kind) } } }
        syncPoolFill(kinds)
    }

    private val fillCoordinator = FillCoordinator(background)

    /**
     * Keep filling the pool from whichever finders are switched on, until the
     * 3000 slots are full, the viewer stops it, or every source is exhausted.
     *
     * This used to stop after four rounds that added nothing, which is why the
     * pool plateaued: a GitHub round can take 60 s and return nothing new while
     * the directories are still several pages away. The loops are now two
     * independent things:
     *
     *  - [ServerHarvester.continuousFill] runs every directory source in
     *    parallel and reports when a source genuinely has no unseen work;
     *  - this method keeps that loop alive across finder toggles and feeds the
     *    pooled result in as it arrives, so the UI count climbs while the crawl
     *    runs rather than at the end.
     *
     * The only stop conditions are the ones a person can check: the pool
     * reports full ([ServerPool.CAPACITY]), the harvester reports every source
     * exhausted, or the job is cancelled (finder toggled off, app closed, or the
     * viewer pressed stop). Nothing is ever reported as full while slots are
     * free -- [ServerPool.PoolState.full] reads the real count.
     */
    @Synchronized
    fun syncPoolFill(kinds: Set<FinderKind>) {
        val unchanged = activeKinds == kinds
        activeKinds = kinds.toSet()
        val pool = pool
        val harvester = harvester
        // No finder on means no harvesting at all. With even one finder on, the
        // shared pool is filled by the directories and enabled finders.
        if (pool == null || harvester == null || kinds.isEmpty()) {
            fillCoordinator.stop()
            return
        }
        // warmUp and refresh can request the same fill back-to-back; don't
        // cancel a running fill and start a second copy for an identical state.
        if (unchanged && fillCoordinator.filling) return
        fillCoordinator.start {
            android.util.Log.i("NovaHarvester", "server pool fill started")
            try {
                harvester.continuousFill(
                    poolSize = { pool.state.value.size },
                    target = ServerPool.CAPACITY,
                    onBatch = { fresh -> pool.addAndCount(fresh + cachedCandidates()) }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("NovaHarvester", "server pool fill failed", error)
                harvester.noteFatal(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Stop filling; the pool keeps everything it has. */
    @Synchronized
    fun stopPoolFill() = fillCoordinator.stop()

    /** Is filling wanted (including while a one-shot harvest pauses it)? */
    val filling: Boolean get() = fillCoordinator.filling

    /**
     * The harvester's live counters, so the UI can show real numbers
     * (sources done, requests made, whether anything is still being looked at).
     */
    val harvestState get() = harvester?.state?.value

    /**
     * Read the next slice of every directory into the pool (one round).
     *
     * Calls into [ServerHarvester.harvest], which now runs every source in
     * parallel, so this is the "fill once, now" button rather than the loop.
     * Returns the number of new entries. A source that throws propagates: an
     * exception is a broken source, and the caller words it as a failure rather
     * than as "the directories had nothing".
     */
    suspend fun fillPool(): Int {
        val pool = pool ?: return 0
        val harvester = harvester ?: return 0
        return fillCoordinator.pauseAndRun {
            if (pool.state.value.size >= ServerPool.CAPACITY) return@pauseAndRun 0
            // harvest() keeps its single-argument shape for callers, and is one
            // parallel pass over every source in the new harvester.
            val found = harvester.harvest(0)
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
        // Cancel and wait for any in-flight continuous sweep before touching
        // the same source cursors. A Stop during this operation must stay stopped.
        return fillCoordinator.pauseAndRun {
            val fresh = harvester.harvestOnce()
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
                    FinderKind.ZEROPLAY -> zeroPlay.find(exclude = installed)
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
            // 1. Installed addons: the fast path.
            launch {
                state.track("addons") {
                    state.add(runCatching { repo.loadStreams(request.type, request.streamId) }.getOrDefault(emptyList()))
                    publish(state)
                    // Probe the first answers straight away; Play is ranked by health.
                    state.probe(probe)
                }
                publish(state)
            }

            // 2 + 3. ZeroPlay and Stremio finders: discover, then verify for this id.
            for (kind in listOf(FinderKind.ZEROPLAY, FinderKind.STREMIO).filter { it in request.finders }) {
                launch {
                    state.track(kind.label) {
                        val report = withTimeoutOrNull(DISCOVERY_BUDGET_MS) { discover(kind) }
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
                        verifyInto(state, cachedCandidates())
                        pool?.let { verifyInto(state, it.batch(POOL_BATCH) { base -> state.isClaimed(base) }, POOL_BATCH) }
                    }
                    publish(state)
                }
                // The site list can hold thousands of entries now that the
                // crawler imports verified ones, so this loop must be bounded:
                // running every site for every title would open thousands of
                // connections and never finish. [SITES_PER_TITLE] best sites are
                // tried, proven-first, which is also what the manifest pool does.
                val savedSites = library.websites.value
                val poolHosts = sitePool?.sites()?.map { it.host }?.toHashSet().orEmpty()
                val siteBatch = sitePool?.batchAvailable(savedSites, SITES_PER_TITLE)
                    ?: savedSites.take(SITES_PER_TITLE)
                for (site in siteBatch) {
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
                            val found = websites.findStreams(site, query).filter { it.isPlayable }
                            state.add(found)
                            val host = SiteRecord.hostOf(site.url)
                            if (host in poolHosts) sitePool?.noteResult(host, found.size, found.isEmpty())
                        }
                        publish(state)
                    }
                }
            }
        }
        // Nothing anywhere: scramble the pool once and try the new servers.
        if (FinderKind.TITLE in request.finders && pool != null && state.isEmpty()) {
            state.track(SCRAMBLE_LABEL) {
                publish(state)
                scramblePool()
                verifyInto(state, pool.batch(POOL_BATCH) { base -> state.isClaimed(base) }, POOL_BATCH)
            }
        }
        pool?.flush()
        // Everything has answered: probe whatever is now on top, then finish.
        state.probe(probe)
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

        fun add(found: List<Stream>) = synchronized(lock) {
            for (s in found) {
                val url = s.url?.trim() ?: continue
                if (!s.isPlayable) continue
                streams.putIfAbsent(url + "|" + s.requestHeaders.hashCode(), s)
            }
        }

        fun noteInstalled(name: String) = synchronized(lock) { installed += name }

        fun claim(base: String) = synchronized(lock) { claimed.add(base.trimEnd('/').lowercase()) }

        fun isClaimed(base: String) = synchronized(lock) { base.trimEnd('/').lowercase() in claimed }

        fun isEmpty() = synchronized(lock) { streams.isEmpty() }

        fun reserveInstall() = synchronized(lock) { if (installs < MAX_AUTO_INSTALL) { installs++; true } else false }

        suspend fun track(name: String, block: suspend () -> Unit) {
            synchronized(lock) { running += name }
            try { block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally { synchronized(lock) { running -= name } }
        }

        /** Probe the current top [StreamProbe.DEFAULT_LIMIT] that have not been probed yet. */
        suspend fun probe(probe: StreamProbe) {
            val todo = synchronized(lock) {
                StreamRanker.rank(streams.values.toList(), ctx.copy(probes = probes.toMap()))
                    .filter { it.url !in probes }.take(StreamProbe.DEFAULT_LIMIT)
            }
            if (todo.isEmpty()) return
            val results = probe.probeAll(todo)
            synchronized(lock) { probes.putAll(results) }
        }

        fun snapshot(done: Boolean): SourceSnapshot = synchronized(lock) {
            val p = probes.toMap()
            val ranked = StreamRanker.rank(streams.values.toList(), ctx.copy(probes = p))
            // "Only ones that work": once anything has been probed, a stream
            // that failed its probe is not shown. Before the first probe there
            // is nothing to filter by, so the discoverable list stays live.
            val working = ranked.filter { p[it.url]?.ok == true }
            SourceSnapshot(
                streams = (if (working.isNotEmpty()) working else ranked).take(MAX_SHOWN),
                probes = p,
                running = running.toSet(),
                installed = installed.toList(),
                done = done
            )
        }
    }

    companion object {
        const val PARALLELISM = 12
        private const val VERIFY_CONCURRENCY = 16
        const val POOL_BATCH = 150

        /**
         * How many websites one title is resolved against. Each costs a search
         * plus up to two frame loads, so this is the budget that keeps a title
         * responsive when the imported site pool is full.
         */
        const val SITES_PER_TITLE = 24
        const val SCRAMBLE_LABEL = "New servers"
        private const val MAX_VERIFY = 60
        /** The source sheet lists at most this many entries; the rest add noise. */
        private const val MAX_SHOWN = 24
        private const val MAX_AUTO_INSTALL = 3
        private const val DISCOVERY_TTL_MS = 30 * 60_000L
        private const val DISCOVERY_BUDGET_MS = 30_000L
    }
}
