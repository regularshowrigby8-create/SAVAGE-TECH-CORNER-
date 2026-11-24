package dev.nova.tv.finder.pipeline

import dev.nova.tv.finder.diag.FinderTrace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class PipelineSurfaceResult(
    val added: Int,
    val report: PipelineReport
)

private data class Inspection(
    val stored: StoredSite?,
    val reason: String
)

/**
 * The fourteen-stage runner shared by both product flavours.
 *
 * `run()` is the full evidence-building path: plan, engines, fetch, parse,
 * normalise, dedupe, sort, look, classify, extract, probe, verify, store and
 * audit. `processSurface()` is the cheap frontier adapter used while a legacy
 * harvest is draining one engine URL. `inspectCandidate()` applies the
 * user-like stages to one ordinary page. Every path writes explicit evidence.
 */
class PipelineRunner(
    private val kind: PipelineKind,
    private val transport: PipelineTransport,
    private val store: PipelineStore,
    private val sink: PipelineSink? = null
) {
    private val processed = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    suspend fun run(
        round: Int,
        pages: Int = 1,
        maxCandidates: Int = MAX_CANDIDATES_PER_RUN
    ): PipelineReport {
        val audit = PipelineAudit()
        val engines = EngineRegistry.forKind(kind)
        val engineCount = EngineRegistry.ALL.size
        val stageCount = PipelineStage.values().size
        audit.count(PipelineStage.PLAN)
        if (engineCount < MIN_ENGINE_COUNT) {
            audit.error(PipelineStage.PLAN.label, "engine-count-$engineCount-below-$MIN_ENGINE_COUNT")
        }
        if (stageCount < MIN_STAGE_COUNT) {
            audit.error(PipelineStage.PLAN.label, "stage-count-$stageCount-below-$MIN_STAGE_COUNT")
        }
        if (pages < 1) audit.error(PipelineStage.PLAN.label, "pages-must-be-positive")
        if (maxCandidates > MAX_CANDIDATES_PER_RUN) {
            audit.error(PipelineStage.PLAN.label, "max-candidates-$maxCandidates-exceeds-$MAX_CANDIDATES_PER_RUN")
        }
        val boundedPages = pages.coerceAtLeast(1)
        val boundedCandidates = maxCandidates.coerceIn(1, MAX_CANDIDATES_PER_RUN)
        // The APK debug probe asks for a small batch. Stop that batch after a few
        // real verifications instead of spending the full 10,000-site capacity
        // budget on one emulator run. Full-capacity unit tests stay unbounded.
        val boundedRun = maxCandidates < MAX_CANDIDATES_PER_RUN

        val surfaces = EngineRegistry.surfaceUrls(kind, round, boundedPages)
        audit.count(PipelineStage.ENGINES, surfaces.size)
        FinderTrace.count("pipeline.engines", surfaces.size)
        if (surfaces.isEmpty()) audit.error(PipelineStage.ENGINES.label, "no-surface-urls")

        val fetched = coroutineScope {
            surfaces.map { url -> async { safeFetch(url, MAX_INDEX_BYTES, audit) } }.awaitAll()
        }
        audit.count(PipelineStage.FETCH, fetched.size)

        val parsedCandidates = ArrayList<CandidateSite>()
        for (result in fetched) {
            if (result.error.isNotEmpty() || !PipelineText.okStatus(result.status)) {
                audit.reject(
                    PipelineStage.FETCH,
                    result.url,
                    result.error.ifBlank { "http-${result.status}" }
                )
                continue
            }
            val parsed = EngineRegistry.parse(result.url, result.body, kind)
            if (parsed.error.isNotEmpty()) {
                audit.error(PipelineStage.PARSE.label, parsed.error)
                continue
            }
            if (parsed.candidates.isEmpty()) {
                audit.reject(PipelineStage.PARSE, result.url, "empty-index-response")
            }
            audit.count(PipelineStage.PARSE, parsed.candidates.size)
            parsedCandidates += parsed.candidates
        }

        val normalised = ArrayList<CandidateSite>()
        for (candidate in parsedCandidates) {
            val clean = PipelineText.normalise(candidate)
            if (clean.host.isEmpty()) {
                audit.reject(PipelineStage.NORMALISE, candidate.url, "not-an-absolute-http-url")
            } else {
                normalised += clean
            }
        }
        audit.count(PipelineStage.NORMALISE, normalised.size)

        val seen = HashSet<String>()
        val unique = ArrayList<CandidateSite>()
        for (candidate in normalised) {
            if (!seen.add(candidate.host)) {
                audit.reject(PipelineStage.DEDUPE, candidate.url, "redundant-host")
            } else {
                unique += candidate
            }
        }
        audit.count(PipelineStage.DEDUPE, unique.size)

        // Stage 07 must actually order the accepted frontier by observed
        // quality signals; mapping alone left discovery response order in
        // control of which twenty hosts a bounded debug run could inspect.
        val sorted = unique.map { PipelineSorter.sort(it) }
            .sortedWith(compareByDescending<SortedSite> { it.score }.thenBy { it.site.host })
        audit.count(PipelineStage.SORT, sorted.size)
        for (item in sorted) {
            if (!item.accepted) audit.reject(PipelineStage.SORT, item.site.url, item.reason)
        }

        val selected = ArrayList<SortedSite>()
        synchronized(processed) {
            for (item in sorted) {
                if (selected.size >= boundedCandidates) break
                if (!item.accepted) continue
                if (!processed.add(item.site.host)) {
                    audit.reject(PipelineStage.DEDUPE, item.site.url, "already-inspected")
                    continue
                }
                selected += item
            }
        }

        // Fetching all public indexes is deliberately slower than looking at
        // the best survivors. Give the look stage its own budget so a slow
        // index response cannot mark every candidate "run-budget-expired"
        // before the first user-like visit happens.
        val lookDeadline = if (boundedRun) {
            System.currentTimeMillis() + PIPELINE_BOUNDED_LOOK_BUDGET_MS
        } else {
            Long.MAX_VALUE / 4
        }
        val lookTarget = if (boundedRun) {
            minOf(PIPELINE_BOUNDED_VERIFICATION_TARGET, boundedCandidates)
        } else {
            Int.MAX_VALUE
        }
        val gate = Semaphore(LOOK_CONCURRENCY)
        coroutineScope {
            selected.map { item ->
                async {
                    gate.withPermit {
                        when {
                            System.currentTimeMillis() >= lookDeadline -> {
                                audit.reject(PipelineStage.LOOK, item.site.url, "look-budget-expired")
                                Inspection(null, "look-budget-expired")
                            }
                            store.size() >= lookTarget -> {
                                audit.reject(PipelineStage.LOOK, item.site.url, "verification-target-reached")
                                Inspection(null, "verification-target-reached")
                            }
                            boundedRun -> {
                                val inspection = withTimeoutOrNull(PIPELINE_BOUNDED_LOOK_TIMEOUT_MS) {
                                    inspectOne(item.site, item.score, audit, lookDeadline)
                                }
                                if (inspection == null) {
                                    audit.reject(PipelineStage.LOOK, item.site.url, "look-coroutine-timeout")
                                    Inspection(null, "look-coroutine-timeout")
                                } else {
                                    inspection
                                }
                            }
                            else -> inspectOne(item.site, item.score, audit, lookDeadline)
                        }
                    }
                }
            }.awaitAll()
        }

        val verifiedCount = store.size()
        if (boundedRun && System.currentTimeMillis() >= lookDeadline && verifiedCount < MIN_SITE_TARGET) {
            audit.budgetStopped = true
        }
        audit.count(PipelineStage.AUDIT)
        val report = audit.report(kind, engineCount, verifiedCount)
        FinderTrace.log(
            "pipeline",
            "${kind.name.lowercase()} run#$round engines=${report.engineCount} " +
                "stages=${report.stageCount} verified=$verifiedCount/${MIN_SITE_TARGET} " +
                "status=${report.status} ok=${report.ok} errors=${report.errors.size}"
        )
        FinderTrace.count("pipeline.${kind.name.lowercase()}.${statusKey(report)}", 1)
        return report
    }

    /** Cheap adapter: fetch one public index, sort its discoveries, enqueue them. */
    suspend fun processSurface(url: String): PipelineSurfaceResult {
        val audit = PipelineAudit()
        val engines = EngineRegistry.forKind(kind)
        val engineCount = EngineRegistry.ALL.size
        audit.count(PipelineStage.PLAN)
        audit.count(PipelineStage.ENGINES, engines.size)
        val response = safeFetch(url, MAX_INDEX_BYTES, audit)
        audit.count(PipelineStage.FETCH)
        if (response.error.isNotEmpty() || !PipelineText.okStatus(response.status)) {
            val reason = response.error.ifBlank { "http-${response.status}" }
            audit.reject(PipelineStage.FETCH, url, reason)
            FinderTrace.log("pipeline", "surface FAILED $reason $url")
        }
        val parsed = if (response.error.isEmpty() && PipelineText.okStatus(response.status)) {
            EngineRegistry.parse(url, response.body, kind)
        } else {
            EngineParseResult("", emptyList(), "fetch-failed")
        }
        if (parsed.error.isNotEmpty() && parsed.error != "fetch-failed") {
            audit.error(PipelineStage.PARSE.label, parsed.error)
        }
        audit.count(PipelineStage.PARSE, parsed.candidates.size)

        val normalised = parsed.candidates.map { PipelineText.normalise(it) }
            .filter { it.host.isNotEmpty() }
        audit.count(PipelineStage.NORMALISE, normalised.size)
        val seen = HashSet<String>()
        val unique = normalised.filter { seen.add(it.host) }
        audit.count(PipelineStage.DEDUPE, unique.size)
        val sorted = unique.map { PipelineSorter.sort(it) }
        audit.count(PipelineStage.SORT, sorted.size)

        var added = 0
        val frontier = sink
        for (item in sorted) {
            if (!item.accepted) {
                audit.reject(PipelineStage.SORT, item.site.url, item.reason)
                continue
            }
            if (frontier == null) {
                audit.error(PipelineStage.STORE.label, "no-frontier-sink-wired")
                break
            }
            val accepted = try {
                frontier.push(item.site)
            } catch (error: Exception) {
                audit.error(PipelineStage.STORE.label, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                false
            }
            if (accepted) added++ else audit.reject(PipelineStage.STORE, item.site.url, "frontier-rejected")
        }
        audit.count(PipelineStage.STORE, added)
        audit.count(PipelineStage.AUDIT)
        val report = audit.report(kind, engineCount, store.size())
        FinderTrace.log(
            "pipeline",
            "surface ${statusKey(report)} $url -> $added site(s), errors=${report.errors.size}"
        )
        FinderTrace.count("pipeline.surface.queued", added)
        return PipelineSurfaceResult(added, report)
    }

    /** User-like inspection of one ordinary page URL from a legacy frontier. */
    suspend fun inspectCandidate(url: String): PipelineVerdict {
        val audit = PipelineAudit()
        val engines = EngineRegistry.forKind(kind)
        val engineCount = EngineRegistry.ALL.size
        val host = PipelineText.hostOf(url)
        val candidate = PipelineText.normalise(
            CandidateSite(
                url = url,
                host = host,
                engineId = "legacy-frontier",
                kind = kind,
                topic = "${kind.name.lowercase()} catalogue server",
                provenance = "frontier"
            )
        )
        audit.count(PipelineStage.PLAN)
        audit.count(PipelineStage.ENGINES, engines.size)
        audit.count(PipelineStage.FETCH)
        if (candidate.host.isEmpty()) {
            val reason = "not-an-absolute-http-url"
            audit.reject(PipelineStage.NORMALISE, url, reason)
            audit.count(PipelineStage.AUDIT)
            return PipelineVerdict(false, reason, null, audit.report(kind, engineCount, store.size()))
        }
        val sorted = PipelineSorter.sort(candidate)
        audit.count(PipelineStage.NORMALISE)
        audit.count(PipelineStage.DEDUPE)
        audit.count(PipelineStage.SORT)
        if (!sorted.accepted) {
            audit.reject(PipelineStage.SORT, url, sorted.reason)
            audit.count(PipelineStage.AUDIT)
            return PipelineVerdict(false, sorted.reason, null, audit.report(kind, engineCount, store.size()))
        }
        val inspection = inspectOne(candidate, sorted.score, audit, System.currentTimeMillis() + RUN_BUDGET_MS)
        audit.count(PipelineStage.AUDIT)
        return PipelineVerdict(
            accepted = inspection.stored != null,
            reason = inspection.reason,
            stored = inspection.stored,
            report = audit.report(kind, engineCount, store.size())
        )
    }

    private suspend fun inspectOne(
        site: CandidateSite,
        score: Int,
        audit: PipelineAudit,
        deadline: Long
    ): Inspection {
        if (System.currentTimeMillis() >= deadline) {
            val reason = "run-budget-expired"
            audit.reject(PipelineStage.LOOK, site.url, reason)
            return Inspection(null, reason)
        }
        val evidence = try {
            PipelineLooker.look(kind, site.url, transport)
        } catch (error: Exception) {
            val reason = "looker-threw ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            audit.error(PipelineStage.LOOK.label, reason)
            return Inspection(null, reason)
        }
        audit.count(PipelineStage.LOOK)

        val classified = PipelineVerifier.classify(evidence)
        audit.count(PipelineStage.CLASSIFY)
        if (!classified.accepted) {
            audit.reject(PipelineStage.CLASSIFY, site.url, classified.reason)
            return Inspection(null, classified.reason)
        }

        val sources = PipelineExtractor.extractSources(
            kind = kind,
            html = evidence.combinedHtml,
            network = evidence.network,
            referer = site.url
        )
        audit.count(PipelineStage.EXTRACT, sources.size)
        if (sources.isEmpty()) {
            val reason = "nothing-extracted"
            audit.reject(PipelineStage.EXTRACT, site.url, reason)
            return Inspection(null, reason)
        }
        val source = chooseSource(sources)
            ?: return Inspection(null, "no-probeable-source").also {
                audit.reject(PipelineStage.EXTRACT, site.url, it.reason)
            }

        val probe = safeProbe(source.url, source.referer, audit)
        audit.count(PipelineStage.PROBE)
        val verdict = PipelineVerifier.verify(site, evidence, sources, probe)
        audit.count(PipelineStage.VERIFY)
        if (!verdict.accepted) {
            audit.reject(PipelineStage.VERIFY, site.url, verdict.reason)
            return Inspection(null, verdict.reason)
        }

        val stored = StoredSite(
            url = site.url,
            host = site.host,
            engineId = site.engineId,
            kind = kind,
            catalogueItemCount = evidence.catalogueItemCount,
            sourceUrl = probe.url,
            score = score,
            provenance = site.provenance
        )
        val written = try {
            store.put(stored)
        } catch (error: Exception) {
            audit.error(PipelineStage.STORE.label, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            false
        }
        if (!written) {
            val reason = "duplicate-or-cap"
            audit.reject(PipelineStage.STORE, site.url, reason)
            return Inspection(null, reason)
        }
        if (sink != null) {
            val queued = try {
                sink.push(site)
            } catch (error: Exception) {
                audit.error(PipelineStage.STORE.label, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                false
            }
            if (!queued) audit.error(PipelineStage.STORE.label, "verified-site-not-queued:${site.url}")
        }
        audit.count(PipelineStage.STORE)
        return Inspection(stored, verdict.reason)
    }

    private suspend fun safeFetch(url: String, read: Int, audit: PipelineAudit): TransportResult = try {
        val result = withTimeoutOrNull(PIPELINE_FETCH_COROUTINE_TIMEOUT_MS) {
            transport.fetch(url, read = read)
        }
        if (result == null) {
            audit.reject(PipelineStage.FETCH, url, "fetch-coroutine-timeout")
            TransportResult(url, 0, "", "", "fetch-coroutine-timeout")
        } else {
            result
        }
    } catch (error: Exception) {
        val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        audit.error(PipelineStage.FETCH.label, reason)
        TransportResult(url, 0, "", "", reason)
    }

    private suspend fun safeProbe(url: String, referer: String, audit: PipelineAudit): PipelineProbe = try {
        transport.probe(url, referer)
    } catch (error: Exception) {
        val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        audit.error(PipelineStage.PROBE.label, reason)
        PipelineProbe(url, 0, "unknown", "", "", false, reason)
    }

    private fun chooseSource(sources: List<ExtractedSource>): ExtractedSource? {
        val priority = if (kind == PipelineKind.WEBSITE) {
            listOf("hls", "dash", "mp4", "embed-api")
        } else {
            listOf("manifest", "stream", "catalog")
        }
        for (wanted in priority) {
            val found = sources.firstOrNull { it.mediaKind == wanted }
            if (found != null) return found
        }
        return null
    }

    private fun statusKey(report: PipelineReport): String = report.status.lowercase()
}
