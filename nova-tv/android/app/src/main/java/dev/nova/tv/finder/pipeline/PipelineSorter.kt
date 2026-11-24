package dev.nova.tv.finder.pipeline

/**
 * Stage 07. Remove stubs, ads, irrelevant index/CDN/corporate hosts and
 * redundant shapes before spending a user-like visit on them.
 *
 * This is not an allowlist: every host discovered by an unrestricted engine may
 * proceed. The rejected set is written down with a reason; acceptance here only
 * buys an inspection, never a claim that the site works.
 */
object PipelineSorter {
    private val privateHost = Regex(
        """^(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|169\.254\.\d+\.\d+|::1|\[::1\])$""",
        RegexOption.IGNORE_CASE
    )
    private val ipv4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adtrafficquality.google", "syndicatedsearch.goog", "adservice.google",
        "facebook.net", "fbcdn.net", "amazon-adsystem.com", "criteo.com",
        "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "3lift.com", "sharethrough.com"
    )
    private val irrelevantHosts = listOf(
        "google.com", "gstatic.com", "googleapis.com", "bing.com",
        "duckduckgo.com", "youtube.com", "youtu.be", "vimeo.com",
        "wikipedia.org", "wikimedia.org", "fandom.com", "reddit.com",
        "github.com", "githubusercontent.com", "npmjs.com", "npms.io",
        "crt.sh", "urlscan.io", "commoncrawl.org", "web.archive.org",
        "merriam-webster.com", "webflow.com", "shopify.com", "odkmedia.net",
        "akamai.com", "fastly.net",
        "jsdelivr.net", "unpkg.com", "cdnjs.com", "gravatar.com",
        "bootstrapcdn.com", "jquery.com", "w3.org", "schema.org"
    )
    private val junkPath = Regex(
        "/(?:wp-admin|wp-json|wp-includes|wp-login|xmlrpc\\.php|admin-ajax\\.php|" +
            "ads?|adserver|adframe|analytics|track(?:ing|er)?|pixel|beacon|prebid|" +
            "gpt|pagead|telemetry|cdn-cgi)(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )
    private val assetPath = Regex(
        """\.(?:js|css|woff2?|ttf|eot|png|jpe?g|gif|svg|ico|map)(?:$|\?)""",
        RegexOption.IGNORE_CASE
    )
    private val mediaWords = listOf(
        "movie", "series", "anime", "watch", "stream", "episode", "catalog",
        "catalogue", "kitsu", "drama", "film", "tv", "show", "stremio"
    )
    private val strongerWords = listOf("movie", "series", "anime", "watch", "stream", "stremio")

    private fun suffix(host: String, list: List<String>): Boolean =
        list.any { host == it || host.endsWith(".$it") }

    fun garbageReason(site: CandidateSite): String {
        val host = site.host.lowercase().trimEnd('.')
        val path = PipelineText.pathOf(site.url).lowercase()
        return when {
            host.isEmpty() -> "empty-host"
            privateHost.matches(host) || ipv4.matches(host) -> "private-or-ip-host"
            '.' !in host || host.length < 4 -> "not-a-public-domain"
            host.substringAfterLast('.').length < 2 -> "invalid-tld"
            EngineRegistry.ALL.any { it.host == host } -> "public-index-host"
            suffix(host, adHosts) -> "ad-or-tracker-host"
            suffix(host, irrelevantHosts) -> "irrelevant-index-cdn-or-corporate-host"
            junkPath.containsMatchIn(path) -> "cms-ad-or-tracker-path"
            assetPath.containsMatchIn(path) -> "static-asset-not-site"
            "magnet:" in site.url.lowercase() || "xt=urn:btih" in site.url.lowercase() -> "torrent-not-site"
            else -> ""
        }
    }

    fun sort(site: CandidateSite): SortedSite {
        val garbage = garbageReason(site)
        if (garbage.isNotEmpty()) return SortedSite(site, 0, false, garbage)
        val host = site.host.lowercase()
        val path = PipelineText.pathOf(site.url).lowercase()
        val topic = site.topic.lowercase()
        var score = MIN_SORTER_SCORE
        if (strongerWords.any { it in topic }) score += 12
        if (mediaWords.any { it in host }) score += 10
        if (mediaWords.any { it in path }) score += 8
        if (listOf("crtsh", "wayback", "urlscan", "github", "npm", "pypi", "reddit").any { it in site.provenance }) score += 4
        if (path.contains("manifest.json") || path.contains("/catalog/") || path.contains("/stream/")) score += 10
        val accepted = score >= MIN_SORTER_SCORE
        return SortedSite(
            site = site,
            score = score,
            accepted = accepted,
            reason = if (accepted) "public-site-candidate" else "score-below-floor"
        )
    }
}
