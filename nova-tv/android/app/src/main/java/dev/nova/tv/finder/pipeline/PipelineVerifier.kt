package dev.nova.tv.finder.pipeline

/**
 * Stages 09 and 12. Classify the user-like evidence, then verify that the
 * extracted server is real, playable-shaped and suitable. Corporate MP4s,
 * ad/tracker frames, CMS handlers, torrent links, bot-wall shells and silent
 * probe failures are rejected with explicit reasons.
 */
object PipelineVerifier {
    private val corporateVideo = Regex(
        """verifone|homepage-montage|corporate[_-]?video|odkm_corporate|ondemandkorea\.com/.*corporate""",
        RegexOption.IGNORE_CASE
    )
    private val stubShell = Regex(
        """just a moment|checking your browser|enable javascript|cloudflare challenge|access denied|page not found|404 error|domain (?:is )?for sale""",
        RegexOption.IGNORE_CASE
    )
    private val adMarker = Regex(
        """doubleclick|googlesyndication|googleadservices|google-analytics|googletagmanager|adtrafficquality|pagead|adservice""",
        RegexOption.IGNORE_CASE
    )
    private val torrentMarker = Regex(
        """magnet:|xt=urn:btih|torrentio|jackettio|infohash""",
        RegexOption.IGNORE_CASE
    )

    fun classify(evidence: CatalogueEvidence): VerificationVerdict = when {
        evidence.errors.isNotEmpty() -> VerificationVerdict(false, "looker-errors:${evidence.errors.first()}")
        !evidence.searchable -> VerificationVerdict(false, "not-searchable-as-a-user")
        evidence.catalogueItemCount < MIN_CATALOGUE_ITEMS ->
            VerificationVerdict(false, "catalogue-below-${MIN_CATALOGUE_ITEMS}")
        !evidence.hasCatalogue -> VerificationVerdict(false, "no-catalogue")
        !evidence.hasServer -> VerificationVerdict(false, "no-server")
        !evidence.serverWithCatalogue -> VerificationVerdict(false, "server-without-catalogue")
        stubShell.containsMatchIn(evidence.combinedHtml) -> VerificationVerdict(false, "stub-or-bot-wall")
        else -> VerificationVerdict(true, "catalogue-and-server-observed")
    }

    fun verify(
        site: CandidateSite,
        evidence: CatalogueEvidence,
        sources: List<ExtractedSource>,
        probe: PipelineProbe
    ): VerificationVerdict {
        val classified = classify(evidence)
        if (!classified.accepted) return classified

        val siteGarbage = PipelineSorter.garbageReason(site)
        if (siteGarbage.isNotEmpty()) return VerificationVerdict(false, "garbage-site:$siteGarbage")
        if (sources.isEmpty()) return VerificationVerdict(false, "nothing-extracted")
        if (torrentMarker.containsMatchIn(probe.url) || torrentMarker.containsMatchIn(evidence.combinedHtml)) {
            return VerificationVerdict(false, "torrent-backed")
        }
        if (adMarker.containsMatchIn(probe.url) || adMarker.containsMatchIn(probe.sample)) {
            return VerificationVerdict(false, "ad-or-tracker-frame")
        }
        if (corporateVideo.containsMatchIn(probe.url) || corporateVideo.containsMatchIn(probe.sample)) {
            return VerificationVerdict(false, "irrelevant-corporate-video")
        }
        if (probe.error.isNotEmpty()) return VerificationVerdict(false, "probe-error:${probe.error}")
        if (!PipelineText.okStatus(probe.status)) return VerificationVerdict(false, "bad-probe-status:${probe.status}")
        if (!probe.alive) return VerificationVerdict(false, "probe-not-alive")
        if (probe.kind in setOf("unknown", "page")) return VerificationVerdict(false, "probe-not-media:${probe.kind}")
        if (probe.sample.isBlank()) return VerificationVerdict(false, "empty-server-body")
        if (stubShell.containsMatchIn(probe.sample)) return VerificationVerdict(false, "fake-server-body")
        return VerificationVerdict(true, "verified-${probe.kind}-with-catalogue")
    }
}
