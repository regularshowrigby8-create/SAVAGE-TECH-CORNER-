package dev.nova.tv.finder.pipeline

import kotlinx.serialization.Serializable

/**
 * Shared, flavour-independent finder pipeline.
 *
 * Everything in this package lives under `src/main`; the TV and mobile product
 * flavours use the same code. Discovery is unrestricted in the sense that no
 * pre-baked streaming-site allowlist is used: engines query public indexes by
 * topic. There is no auth, CAPTCHA, paywall or DRM bypass here. The sorter is
 * the deliberate quality gate after discovery, not a moderation allowlist.
 */
const val PIPELINE_BUILD_MARKER = "nova-finder-pipeline-v3"
const val MIN_ENGINE_COUNT = 50
const val MIN_SITE_TARGET = 10_000
const val POOL_CAP = 10_000
const val MIN_STAGE_COUNT = 12
const val MIN_CATALOGUE_ITEMS = 40
const val MIN_SORTER_SCORE = 20
const val RUN_BUDGET_MS = 45_000L
const val MAX_CANDIDATES_PER_RUN = 5_000
/** One discovery index call and one byte probe get short, explicit ceilings. */
const val PIPELINE_FETCH_TIMEOUT_MS = 8_000L
const val PIPELINE_PROBE_TIMEOUT_MS = 8_000L
/** Coroutine ceilings for a bounded APK-debug run; full capacity tests are unbounded. */
const val PIPELINE_FETCH_COROUTINE_TIMEOUT_MS = 12_000L
const val PIPELINE_BOUNDED_LOOK_BUDGET_MS = 180_000L
const val PIPELINE_BOUNDED_LOOK_TIMEOUT_MS = 25_000L
const val PIPELINE_BOUNDED_VERIFICATION_TARGET = 3
const val MAX_INDEX_BYTES = 3_000_000
const val MAX_DOCUMENT_BYTES = 400_000
const val LOOK_CONCURRENCY = 24

@Serializable
enum class PipelineKind { WEBSITE, ADDON }

/** The refined pipeline. Fourteen explicit stages, not a five-stage claim. */
enum class PipelineStage(val label: String) {
    PLAN("01-plan"),
    ENGINES("02-engines"),
    FETCH("03-fetch"),
    PARSE("04-parse"),
    NORMALISE("05-normalise"),
    DEDUPE("06-dedupe"),
    SORT("07-sort"),
    LOOK("08-look"),
    CLASSIFY("09-classify"),
    EXTRACT("10-extract"),
    PROBE("11-probe"),
    VERIFY("12-verify"),
    STORE("13-store"),
    AUDIT("14-audit")
}

data class CandidateSite(
    val url: String,
    val host: String,
    val engineId: String,
    val kind: PipelineKind,
    val topic: String,
    val provenance: String
)

data class SortedSite(
    val site: CandidateSite,
    val score: Int,
    val accepted: Boolean,
    val reason: String
)

data class TransportResult(
    val url: String,
    val status: Int,
    val body: String,
    val contentType: String,
    val error: String = "",
    val network: List<String> = emptyList()
)

data class PipelineProbe(
    val url: String,
    val status: Int,
    val kind: String,
    val contentType: String,
    val sample: String,
    val alive: Boolean,
    val error: String = ""
)

data class CatalogueEvidence(
    val url: String,
    val kind: PipelineKind,
    val combinedHtml: String,
    val catalogueItemCount: Int,
    val hasCatalogue: Boolean,
    val hasServer: Boolean,
    val serverWithCatalogue: Boolean,
    val titleMatched: Boolean,
    val searchable: Boolean,
    val errors: List<String>,
    val network: List<String> = emptyList()
)

data class ExtractedSource(
    val url: String,
    val referer: String,
    val mediaKind: String
)

data class VerificationVerdict(
    val accepted: Boolean,
    val reason: String
)

@Serializable
data class StoredSite(
    val url: String,
    val host: String,
    val engineId: String,
    val kind: PipelineKind,
    val catalogueItemCount: Int,
    val sourceUrl: String,
    val score: Int,
    val provenance: String
)

data class PipelineRejection(
    val stage: String,
    val url: String,
    val reason: String
)

data class PipelineReport(
    val marker: String = PIPELINE_BUILD_MARKER,
    val kind: PipelineKind,
    val engineCount: Int,
    val stageCount: Int,
    val stageNames: List<String>,
    val target: Int = MIN_SITE_TARGET,
    val verifiedCount: Int,
    val targetMet: Boolean,
    val status: String,
    val ok: Boolean,
    val budgetStopped: Boolean,
    val stageCounts: Map<String, Int>,
    val rejections: List<PipelineRejection>,
    val errors: List<String>
)

data class PipelineVerdict(
    val accepted: Boolean,
    val reason: String,
    val stored: StoredSite?,
    val report: PipelineReport
)

/** Minimal URL maths without exceptions or hidden defaults. */
object PipelineText {
    fun hostOf(raw: String): String {
        var value = raw.trim().lowercase()
        if (value.startsWith("//")) value = "https:$value"
        if (!value.startsWith("http://") && !value.startsWith("https://")) return ""
        value = value.substringAfter("://")
        value = value.substringBefore("/").substringBefore("?").substringBefore("#")
        value = value.substringBefore(":")
        if (value.startsWith("www.")) value = value.removePrefix("www.")
        return value.trimEnd('.')
    }

    fun originOf(raw: String): String {
        val host = hostOf(raw)
        return if (host.isEmpty()) "" else "https://$host"
    }

    fun pathOf(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("//")) value = "https:$value"
        if (!value.startsWith("http://") && !value.startsWith("https://")) return "/"
        val afterScheme = value.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return "/"
        return afterScheme.substring(slash).substringBefore("?").substringBefore("#")
    }

    fun pathPrefixOf(template: String): String = pathOf(template)

    fun normalise(candidate: CandidateSite): CandidateSite {
        val host = hostOf(candidate.url)
        return candidate.copy(
            url = if (host.isEmpty()) "" else "https://$host/",
            host = host,
            provenance = "${candidate.engineId}:${candidate.provenance}"
        )
    }

    fun okStatus(status: Int): Boolean = status in 200..299
}

/** Explicit audit state. A rejection or error is always written down. */
class PipelineAudit {
    private companion object {
        const val MAX_REJECTIONS = 1_000
    }

    private val counts = LinkedHashMap<String, Int>()
    private val rejectionList = ArrayList<PipelineRejection>()
    private val errorList = ArrayList<String>()
    private var rejectionOverflow = 0
    private val lock = Any()

    @Volatile
    var budgetStopped: Boolean = false

    init {
        for (stage in PipelineStage.values()) counts[stage.label] = 0
    }

    fun count(stage: PipelineStage, amount: Int = 1) = synchronized(lock) {
        counts[stage.label] = (counts[stage.label] ?: 0) + amount.coerceAtLeast(0)
    }

    fun reject(stage: PipelineStage, url: String, reason: String) = synchronized(lock) {
        if (rejectionList.size < MAX_REJECTIONS) {
            rejectionList += PipelineRejection(stage.label, url, reason)
        } else {
            rejectionOverflow += 1
        }
    }

    fun error(context: String, message: String) = synchronized(lock) {
        errorList += "$context: $message"
    }

    fun report(kind: PipelineKind, engineCount: Int, verifiedCount: Int): PipelineReport = synchronized(lock) {
        val noErrors = errorList.isEmpty()
        val stageCount = PipelineStage.values().size
        val everyStageRecorded = counts.size == stageCount
        val realWork = (counts[PipelineStage.FETCH.label] ?: 0) > 0 &&
            (counts[PipelineStage.VERIFY.label] ?: 0) > 0
        val engineFloorMet = engineCount >= MIN_ENGINE_COUNT
        val stageFloorMet = stageCount >= MIN_STAGE_COUNT
        val targetMet = noErrors && !budgetStopped && verifiedCount >= MIN_SITE_TARGET
        val ok = noErrors && !budgetStopped && targetMet && everyStageRecorded &&
            realWork && engineFloorMet && stageFloorMet
        val status = when {
            !noErrors -> "DEGRADED"
            budgetStopped -> "BUDGET_STOPPED"
            ok -> "TARGET_MET"
            else -> "PARTIAL"
        }
        val recordedRejections = rejectionList.toMutableList()
        if (rejectionOverflow > 0) {
            recordedRejections += PipelineRejection(
                stage = PipelineStage.AUDIT.label,
                url = "",
                reason = "rejection-list-capped-at-$MAX_REJECTIONS-omitted-$rejectionOverflow"
            )
        }
        PipelineReport(
            kind = kind,
            engineCount = engineCount,
            stageCount = stageCount,
            stageNames = PipelineStage.values().map { it.label },
            verifiedCount = verifiedCount,
            targetMet = targetMet,
            status = status,
            ok = ok,
            budgetStopped = budgetStopped,
            stageCounts = counts.toMap(),
            rejections = recordedRejections.toList(),
            errors = errorList.toList()
        )
    }
}

/**
 * Evidence store for accepted sites. The existing JSON pools remain the
 * persisted app stores; this store makes the extract/store stage observable
 * and prevents duplicate acceptance from being counted as new evidence.
 */
interface PipelineStore {
    fun put(site: StoredSite): Boolean
    fun size(): Int
    fun snapshot(): List<StoredSite>
}

class InMemoryPipelineStore(private val cap: Int = POOL_CAP) : PipelineStore {
    private val entries = LinkedHashMap<String, StoredSite>()
    private val lock = Any()

    override fun put(site: StoredSite): Boolean = synchronized(lock) {
        val key = "${site.kind.name.lowercase()}|${site.host}"
        if (entries.containsKey(key)) return@synchronized false
        if (entries.size >= cap) return@synchronized false
        entries[key] = site
        true
    }

    override fun size(): Int = synchronized(lock) { entries.size }

    override fun snapshot(): List<StoredSite> = synchronized(lock) { entries.values.toList() }
}
