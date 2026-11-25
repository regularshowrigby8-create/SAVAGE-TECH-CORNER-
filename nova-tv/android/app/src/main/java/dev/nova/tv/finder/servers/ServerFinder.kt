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

class ServerFinder(ctx: Context) {

    private val dir = File(ctx.filesDir, "finder").apply { mkdirs() }
    private val verifier = Verifier(blocklist = Blocklist.load(dir))
    private val pool = Pool(File(dir, "pool.json"))
    private val discovery = Discovery(File(dir, "sources.json"))

    private val expansion = Semaphore(10)
    private val fresh = Semaphore(8)
    private val roundId = UUID.randomUUID().toString().take(8)
    private var booted = false

    data class Report(
        val roundId: String,
        val entered: Int,
        val depth: Int,
        val alive: Int,
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

        return Report(roundId, entered, discovery.depth(), pool.size, log.snapshot(), discovery.report(), now() - started)
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
                    discovery.record(c.source, true)
                    pool.putSite(v.record)
                    ticket.resolve(Outcome.ACCEPT_SITE, "cat=${v.record.catalogue}")
                    expandSite(v.record.host)
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

    /** Expansion only ever fires from an accepted page. */
    private suspend fun expandSite(host: String) {
        val page = Net.get("https://$host/", 300_000)?.body ?: return
        Similarity.siteLinks(page, host).forEach { discovery.offer("https://$it/", Source.SITELINKS, host) }
        Similarity.similar(host).forEach { (h, votes) ->
            discovery.offer("https://$h/", Source.SIMILAR, host, agreement = votes)
        }
    }

    private suspend fun refill() {
        val alive = pool.all().mapNotNull { (it as? SiteRecord)?.host ?: (it as? AddonRecord)?.host }
        val stems = FreshSearch.stems(alive)
        if (stems.isEmpty()) return
        FreshSearch.sweep(stems).forEach { discovery.offer("https://$it/", Source.STEM_TLD) }
        FreshSearch.cdxyRecent(stems).forEach { discovery.offer("https://$it/", Source.CDX_RECENT) }
    }

    private fun token(): String? =
        System.getenv("GH_TOKEN") ?: File(dir, "gh_token").takeIf { it.exists() }?.readText()?.trim()
}

object Blocklist {
    fun load(dir: File): Set<String> = File(dir, "blocklist.txt")
        .takeIf { it.exists() }
        ?.readLines()?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        ?: emptySet()
}
