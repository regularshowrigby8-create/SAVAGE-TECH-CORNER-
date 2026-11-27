package dev.nova.tv.finder.servers

/** Every admitted candidate leaves through exactly one Outcome. */
enum class Outcome {
    ACCEPT_SITE, ACCEPT_ADDON, MOVE,
    STATIC_ASSET, DUPLICATE_HOST, NOT_A_SITE, BAD_PATH,
    DEAD_DNS, DEAD_TCP, DEAD_HTTP, PARKED, RESELLER,
    NO_EMBEDS, NOT_JSON, NOT_SEARCHABLE, THIN_CATALOGUE,
    MANIFEST_MISSING, MANIFEST_PARSE, MANIFEST_NO_CATALOG, MANIFEST_NO_STREAM,
    ENDPOINT_EMPTY, BLOCKED, BUDGET, UNRECORDED;

    val accepted get() = this == ACCEPT_SITE || this == ACCEPT_ADDON
}

enum class Nature { SITE, ADDON }

/** `base` is the prior accept rate; it orders the frontier, never the verdict. */
enum class Source(val base: Double) {
    SITELINKS(0.55), SIMILAR(0.24), ADDON_GRAPH(0.34),
    GITHUB(0.10), REDDIT(0.12), CDX_RECENT(0.09), STEM_TLD(0.06), LISTICLE(0.05);

    val expansion get() = this == SITELINKS || this == SIMILAR || this == ADDON_GRAPH
}

data class Candidate(
    val url: String,
    val source: Source,
    val parent: String? = null,
    var agreement: Int = 1
) {
    val priority: Double get() = source.base * (1.0 + agreement * 0.5)
}

data class SiteRecord(
    val host: String,
    val score: Double,
    val catalogue: Int,
    val embeds: Int,
    val searchable: Boolean,
    val source: Source,
    val at: Long = System.currentTimeMillis()
) {
    /** Catalogues drift; a two-strike recheck drops the record. */
    var strikes: Int = 0
}

data class DiscoveredAddon(
    val host: String,
    val manifestUrl: String,
    val score: Double,
    val catalogue: Int,
    val streams: Int,
    val source: Source,
    val at: Long = System.currentTimeMillis()
) {
    var strikes: Int = 0
}

sealed class Verdict {
    data class Reject(val outcome: Outcome, val detail: String = "") : Verdict()
    data class Move(val to: String) : Verdict()
    data class PassSite(val record: SiteRecord) : Verdict()
    data class PassAddon(val record: DiscoveredAddon) : Verdict()
}
