package dev.nova.tv.finder.servers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Precision-governed discovery. Cold start with no seed list: listicles,
 * subreddits and addon graphs are offered into [Discovery], every candidate
 * passes [Admission] (offline) and then [Verifier], and only a verdict writes
 * to [Pool]. A website and a Stremio addon are two natures and are judged by
 * two different tests; neither is decided by "did a manifest parse".
 *
 * The engine this replaces (`Servers.kt`: harvester, prober, catalogue,
 * extractor) is kept. It is what pulls embeds out of an accepted page and what
 * the Python twin in `servers.py` is checked against.
 */
class ServerFinder(ctx: Context) {

    private val dir = File(ctx.filesDir, "finder").apply { mkdirs() }
    private val verifier = Verifier(blocklist = Blocklist.load(dir))
    private val pool = Pool(File(dir, "pool.json"))
    /** Public: the engine seeds this frontier and reports from [Discovery]. */
    val discovery = Discovery(File(dir, "sources.json"))
    private val extractor = ServerExtractor()

    private val expansion = Semaphore(10)
    private val fresh = Semaphore(8)
    private val roundId = UUID.randomUUID().toString().take(8)
    private var booted = false

    /** Sites accepted by the round, so the website pool can be fed from it. */
    private val accepted = ConcurrentLinkedQueue<SiteRecord>()

    data class Report(
        val roundId: String,
        val entered: Int,
        val depth: Int,
        val alive: Int,
        val sites: List<SiteRecord>,
        val addons: List<DiscoveredAddon>,
        val tally: Map<Outcome, Int>,
        val sources: Map<String, String>,
        val ms: Long
    )

    /** Cold start with no seed domains: listicles, subreddits and awesome-lists only. */
    suspend fun bootstrap() {
        if (booted) return
        booted = true
        coroutineScope {
            launch { FreshSearch.listicles().forEach { discovery.offer(it, Source.LISTICLE) } }
            launch { FreshSearch.reddit().forEach { discovery.offer(it, Source.REDDIT) } }
            launch { FreshSearch.github(token()).forEach { discovery.offer(it, Source.GITHUB) } }
        }
    }

    suspend fun runRound(budgetMs: Long = 240_000, cap: Int = 10_000): Report {
        bootstrap()
        val started = now()
        Admission.reset()
        accepted.clear()
        val log = RoundLog(File(dir, "rounds/$roundId.log"), roundId)
        var entered = 0

        try {
            coroutineScope {
                repeat(cap) {             // loop guard
                    if (now() - started > budgetMs) return@repeat
                    if (pool.full()) return@repeat
                    var next = discovery.poll()
                    if (next == null) {
                        refill()
                        next = discovery.poll()
                    }
                    val c = next ?: return@repeat
                    entered++

                    launch(Dispatchers.IO) {
                        val gate = if (c.source.expansion) expansion else fresh
                        gate.withPermit {
                            try {
                                withTimeoutOrNull(14_000) { handle(c, log) }
                            } catch (e: CancellationException) {
                                throw e
                            }
                        }
                    }
                    // Keep the strike loop from outrunning the workers.
                    while (gateFree() == 0) kotlinx.coroutines.yield()
                }
            }
        } finally {
            log.audit()
            log.close()
            pool.save()
        }

        val sites = accepted.filterIsInstance<SiteRecord>()
        val addons = pool.all().filterIsInstance<DiscoveredAddon>()
        return Report(
            roundId, entered, discovery.depth(), pool.size,
            sites, addons, log.snapshot(), discovery.report(), now() - started
        )
    }

    private fun gateFree() = expansion.availablePermits + fresh.availablePermits

    private suspend fun handle(c: Candidate, log: RoundLog) {
        val key = Admission.key(c.url) ?: return
        log.enter(key, c.source).use { ticket ->
            if (!ticket.isNewHost) return ticket.resolve(Outcome.DUPLICATE_HOST)
            Admission.classify(c.url)?.let { return ticket.resolve(it) }
            Admission.claim(c.url)?.let { return ticket.resolve(it) }

            when (val v = verifier.verify(c)) {
                is Verdict.Reject -> {
                    discovery.record(c.source, false)
                    ticket.resolve(v.outcome, v.detail)
                }
                is Verdict.Move -> {
                    discovery.record(c.source, false)
                    ticket.resolve(Outcome.MOVE, v.to)
                    discovery.offer(v.to, Source.SITELINKS, c.parent)   // a move is the best signal there is
                }
                is Verdict.PassSite -> {
                    // A site is only worth a pool slot if its embeds can
                    // actually be pulled. Without this the pool fills with
                    // sites that were never opened for players.
                    val servers = pull(v.record.host)
                    if (servers.isEmpty()) {
                        discovery.record(c.source, false)
                        return ticket.resolve(Outcome.NO_EMBEDS, "post-extract")
                    }
                    discovery.record(c.source, true)
                    val record = v.record.copy(score = v.record.score + servers.size)
                    pool.putSite(record)
                    accepted += record
                    ticket.resolve(Outcome.ACCEPT_SITE, "cat=${record.catalogue} srv=${servers.size}")
                    expandSite(record.host)
                }
                is Verdict.PassAddon -> {
                    discovery.record(c.source, true)
                    pool.putAddon(v.record)
                    ticket.resolve(Outcome.ACCEPT_ADDON, "streams=${v.record.streams}")
                    Net.get(v.record.manifestUrl, 120_000)?.body?.let { body ->
                        Similarity.addonGraph(body, v.record.host)
                            .forEach { discovery.offer(it, Source.ADDON_GRAPH, v.record.host) }
                    }
                }
            }
        }
    }

    /** Embeds on the accepted page, pulled with the engine's own extractor. */
    private suspend fun pull(host: String): List<ServerCandidate> {
        val html = Net.get("https://$host/", 500_000)?.body ?: return emptyList()
        return extractor.extract(html, "https://$host/").values.toList()
    }

    /** Expansion only ever fires from an accepted page. */
    private suspend fun expandSite(host: String) {
        val page = Net.get("https://$host/", 300_000)?.body ?: return
        Similarity.siteLinks(page, host).forEach { discovery.offer("https://$it/", Source.SITELINKS, host) }
        Similarity.similar(host).forEach { (h, votes) ->
            discovery.offer("https://$h/", Source.SIMILAR, host, agreement = votes)
        }
    }

    private suspend fun refill() {
        val alive = pool.all().mapNotNull { (it as? SiteRecord)?.host ?: (it as? DiscoveredAddon)?.host }
        val stems = FreshSearch.stems(alive)
        if (stems.isEmpty()) return
        FreshSearch.sweep(stems).forEach { discovery.offer("https://$it/", Source.STEM_TLD) }
        FreshSearch.cdxyRecent(stems).forEach { discovery.offer("https://$it/", Source.CDX_RECENT) }
    }

    private fun token(): String? =
        System.getenv("GH_TOKEN") ?: File(dir, "gh_token").takeIf { it.exists() }?.readText()?.trim()
}

/**
 * The round's Outcome tally expressed in the names `ServerRejections` already
 * has, so `SourceEngine.lastServerRejections` and the Sources screen keep
 * working while the counters come from the new verdicts.
 *
 * New rows are added rather than folded away: a `STATIC_ASSET`, `DUPLICATE_HOST`
 * or `MOVE` is exactly the kind of drop a "0 sites" report used to hide.
 */
fun ServerFinder.Report.rejections(): ServerRejections {
    val counts = LinkedHashMap<String, Int>()
    fun add(key: String, n: Int) {
        if (n > 0) counts[key] = (counts[key] ?: 0) + n
    }
    add("unreachable", (tally[Outcome.DEAD_DNS] ?: 0) + (tally[Outcome.DEAD_TCP] ?: 0) + (tally[Outcome.DEAD_HTTP] ?: 0))
    add("no-embeds", tally[Outcome.NO_EMBEDS] ?: 0)
    add("probe-failed", tally[Outcome.ENDPOINT_EMPTY] ?: 0)
    add("page-not-media", tally[Outcome.NOT_JSON] ?: 0)
    add("catalogue<40", tally[Outcome.THIN_CATALOGUE] ?: 0)
    add("blocked", tally[Outcome.BLOCKED] ?: 0)

    val folded = setOf(
        Outcome.DEAD_DNS, Outcome.DEAD_TCP, Outcome.DEAD_HTTP, Outcome.NO_EMBEDS,
        Outcome.ENDPOINT_EMPTY, Outcome.NOT_JSON, Outcome.THIN_CATALOGUE,
        Outcome.BLOCKED, Outcome.UNRECORDED, Outcome.ACCEPT_SITE, Outcome.ACCEPT_ADDON
    )
    for (o in Outcome.entries) {
        if (o in folded) continue
        add(o.name, tally[o] ?: 0)
    }
    return ServerRejections(counts)
}

object Blocklist {
    fun load(dir: File): Set<String> = File(dir, "blocklist.txt")
        .takeIf { it.exists() }
        ?.readLines()?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
}
