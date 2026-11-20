package dev.nova.tv.finder

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.repo.LibraryStore
import dev.nova.tv.finder.website.WebsiteFinder
import dev.nova.tv.finder.website.WebsiteRules
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.player.HostHealth
import dev.nova.tv.player.ProbeResult
import dev.nova.tv.player.StreamProbe
import dev.nova.tv.player.StreamRanker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val harvester: ServerHarvester? = null
) {
    /** At most [PARALLELISM] network calls at once, across every finder. */
    private val io = Dispatchers.IO.limitedParallelism(PARALLELISM)
    private val background = CoroutineScope(SupervisorJob() + io)

    private val discoveryLock = Mutex()
    private val discovered = java.util.concurrent.ConcurrentHashMap<FinderKind, Pair<Long, FinderReport>>()
    private val inflight = HashMap<FinderKind, Deferred<FinderReport>>()

    /** Start directory reads in the background at launch, so the first title is quick. */
    fun warmUp(kinds: Set<FinderKind>) {
        background.launch { kinds.filter { it != FinderKind.TITLE }.forEach { kind -> launch { discover(kind) } } }
        syncPoolFill(kinds)
    }

    private var poolFillJob: Job? = null

    /**
     * Keep filling the pool from whichever finders are switched on.
     *
     * The 3000 slots are one shared pool, so switching a finder off never
     * leaves a hole: the finders still on carry on harvesting until
     * [ServerPool.CAPACITY] is reached. [syncPoolFill] is called on launch and
     * again on every toggle, so a finder switched back on resumes the fill from
     * where it stopped. The loop ends when the pool is full, or when the
     * directories stop returning anything new for [POOL_EMPTY_ROUNDS] rounds.
     */
    fun syncPoolFill(kinds: Set<FinderKind>) {
        activeKinds = kinds
        poolFillJob?.cancel()
        poolFillJob = null
        val pool = pool ?: return
        // No finder on means no harvesting at all. With even one finder on, the
        // shared pool is filled to capacity by the directories plus whatever
        // that finder discovered -- the remaining finders close the gap.
        if (harvester == null || kinds.isEmpty()) return
        poolFillJob = background.launch {
            var emptyRounds = 0
            while (isActive && pool.state.value.size < ServerPool.CAPACITY) {
                val before = pool.state.value.size
                runCatching { fillPool() }
                val after = pool.state.value.size
                if (after > before) emptyRounds = 0 else emptyRounds++
                if (emptyRounds >= POOL_EMPTY_ROUNDS) break
                delay(if (after > before) POOL_FILL_PAUSE_MS else POOL_RETRY_PAUSE_MS)
            }
        }
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
        /** A fill round that adds nothing is not the end; four in a row is. */
        private const val POOL_EMPTY_ROUNDS = 4
        private const val POOL_FILL_PAUSE_MS = 750L
        private const val POOL_RETRY_PAUSE_MS = 4_000L
        private const val VERIFY_CONCURRENCY = 16
        const val POOL_BATCH = 150
        const val SCRAMBLE_LABEL = "New servers"
        private const val MAX_VERIFY = 60
        /** The source sheet lists at most this many entries; the rest add noise. */
        private const val MAX_SHOWN = 24
        private const val MAX_AUTO_INSTALL = 3
        private const val DISCOVERY_TTL_MS = 30 * 60_000L
        private const val DISCOVERY_BUDGET_MS = 30_000L
    }
}
