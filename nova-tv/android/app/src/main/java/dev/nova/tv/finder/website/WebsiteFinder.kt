package dev.nova.tv.finder.website

import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.StreamHints
import dev.nova.tv.data.model.ProxyHeaders
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A website the user added. Only its address and search templates are stored. */
data class SavedWebsite(val url: String, val name: String, val templates: List<String> = emptyList())

/**
 * The network half of the ZeroPlay website features (extract.js
 * profileSite / findTitleOnSite / resolve), on the device.
 *
 * The result is only ever *direct media*. A page or an embed is never shown:
 * the resolver follows the page and up to two player frames and keeps the
 * .m3u8 / .mp4 / .mpd links it finds, exactly like ZeroPlay's `resolve` mode.
 * Those become ordinary [Stream]s and go through the same size cap and probe
 * as addon streams.
 */
class WebsiteFinder(private val http: HttpClient) {

    private val client = http.okHttp.newBuilder()
        .callTimeout(DOC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    data class Document(val text: String, val finalUrl: String)

    /** extract.js fetchDocument: GET, guarded on every hop, body capped at 1.5 MB. */
    suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val safe = WebsiteRules.guardUrl(url) ?: throw IllegalArgumentException("Blocked URL")
        val request = Request.Builder().url(safe.toString())
            .header("User-Agent", WebsiteRules.BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpClient.HttpFailure("HTTP ${response.code}", response.code)
            val finalUrl = response.request.url.toString()
            if (WebsiteRules.guardUrl(finalUrl) == null) throw HttpClient.HttpFailure("Blocked redirect")
            val source = response.body?.source() ?: throw HttpClient.HttpFailure("Empty body")
            source.request(WebsiteRules.MAX_BYTES)
            val size = minOf(source.buffer.size, WebsiteRules.MAX_BYTES)
            Document(source.buffer.readUtf8(size), finalUrl)
        }
    }

    /** extract.js profileSite: where this site's search lives. */
    suspend fun profile(rawUrl: String): SavedWebsite {
        val safe = WebsiteRules.guardUrl(normaliseInput(rawUrl)) ?: throw IllegalArgumentException("That address is not allowed.")
        val first = fetchDocument(safe.toString())
        var templates = WebsiteRules.searchTemplatesFromHtml(first.text, first.finalUrl)
        if (templates.isEmpty() && safe.path.length > 1) {
            runCatching {
                val root = fetchDocument("${safe.protocol}://${safe.authority}/")
                templates = WebsiteRules.searchTemplatesFromHtml(root.text, root.finalUrl)
            }
        }
        if (templates.isEmpty()) templates = WebsiteRules.commonSearchTemplates(first.finalUrl).take(4)
        val name = WebsiteRules.pageTitle(first.text).replace(Regex("""\s+[-|–—].*$"""), "").take(40)
            .ifBlank { safe.host.removePrefix("www.") }
        return SavedWebsite(url = first.finalUrl, name = name, templates = templates.take(5))
    }

    /**
     * extract.js findTitleOnSite + resolve: find the title page, step to the
     * episode, then follow frames to direct media. Returns streams, possibly
     * empty; never throws for an unhelpful site.
     */
    suspend fun findStreams(site: SavedWebsite, query: WebsiteRules.Query): List<Stream> =
        withTimeoutOrNull(SITE_BUDGET_MS) {
            runCatching { findOnSite(site, query) }.getOrDefault(emptyList())
        }.orEmpty()

    private suspend fun findOnSite(site: SavedWebsite, query: WebsiteRules.Query): List<Stream> {
        val home = runCatching { fetchDocument(site.url) }.getOrNull()
        val templates = LinkedHashSet<String>()
        templates += site.templates
        if (home != null) templates += WebsiteRules.searchTemplatesFromHtml(home.text, home.finalUrl)
        templates += WebsiteRules.commonSearchTemplates(site.url)

        var best: Pair<Int, String>? = null
        fun consider(candidates: List<WebsiteRules.Candidate>) {
            for (c in candidates) {
                if (!WebsiteRules.sameSite(c.url, site.url)) continue
                val score = WebsiteRules.titleScore(c, query)
                val tie = best != null && score == best!!.first && query.isSeries &&
                    WebsiteRules.isEpisodeUrl(c.url) && !WebsiteRules.isEpisodeUrl(best!!.second)
                if (best == null || score > best!!.first || tie) best = score to c.url
            }
        }
        if (home != null) consider(WebsiteRules.parseResultLinks(home.text, home.finalUrl))

        // Search pages are fetched in parallel: the JS did them one by one,
        // which is most of why a site lookup took ten seconds.
        val variants = query.titles.filter { it.isNotBlank() }.distinct().take(3)
        for (variant in variants) {
            if ((best?.first ?: 0) >= 95) break
            val urls = templates.take(8).map { WebsiteRules.buildSearchUrl(it, variant) }
                .filter { WebsiteRules.guardUrl(it) != null && WebsiteRules.sameSite(it, site.url) }
            val docs = coroutineScope {
                urls.map { async { runCatching { fetchDocument(it) }.getOrNull() } }.awaitAll().filterNotNull()
            }
            for (doc in docs) {
                consider(WebsiteRules.parseResultLinks(doc.text, doc.finalUrl))
                if (!WebsiteRules.isBlockedPath(doc.finalUrl)) {
                    val title = WebsiteRules.pageTitle(doc.text).replace(Regex("""\s+[-|–—].*$"""), "")
                    consider(listOf(WebsiteRules.Candidate(title, WebsiteRules.candidateSlugTitle(doc.finalUrl), doc.finalUrl)))
                }
            }
        }
        val match = best?.takeIf { it.first >= 80 }?.second ?: return emptyList()
        val watchUrl = resolveEpisode(match, query)
        return resolveDirect(watchUrl).map { media -> toStream(site, media, watchUrl, query) }
    }

    /** extract.js resolveMatchedWatch, reduced to "which page is the episode". */
    private suspend fun resolveEpisode(matchUrl: String, query: WebsiteRules.Query): String {
        if (!query.isSeries) return matchUrl
        val adjusted = WebsiteRules.adjustEpisodeInUrl(matchUrl, query.season, query.episode)
        if (adjusted != matchUrl) return adjusted
        val doc = runCatching { fetchDocument(matchUrl) }.getOrNull() ?: return matchUrl
        WebsiteRules.findEpisodeUrl(doc.text, doc.finalUrl, query.season, query.episode).takeIf { it.isNotEmpty() }?.let { return it }
        for (guess in WebsiteRules.episodeCandidateUrls(doc.finalUrl, query.episode)) {
            val ok = runCatching { fetchDocument(guess) }.getOrNull()
            if (ok != null && WebsiteRules.isEpisodeUrl(ok.finalUrl)) return ok.finalUrl
        }
        return matchUrl
    }

    /** extract.js `resolve` mode: the page, then up to two frames, direct media only. */
    suspend fun resolveDirect(pageUrl: String): List<String> {
        var url = pageUrl
        val direct = LinkedHashSet<String>()
        repeat(MAX_FRAME_DEPTH + 1) {
            val doc = runCatching { fetchDocument(url) }.getOrNull() ?: return direct.toList()
            if (WebsiteRules.isM3uText(doc.text)) { direct += doc.finalUrl; return direct.toList() }
            val watch = WebsiteRules.parseWatch(doc.text, doc.finalUrl)
            direct += watch.direct
            if (direct.isNotEmpty()) return direct.take(MAX_DIRECT)
            url = watch.embeds.firstOrNull { WebsiteRules.guardUrl(it) != null } ?: return direct.toList()
        }
        return direct.take(MAX_DIRECT)
    }

    private fun toStream(site: SavedWebsite, media: String, referer: String, query: WebsiteRules.Query): Stream {
        val host = runCatching { java.net.URL(site.url).host.removePrefix("www.") }.getOrDefault(site.name)
        return Stream(
            url = media,
            name = site.name.ifBlank { host },
            title = query.titles.firstOrNull().orEmpty() + if (query.isSeries) " S${query.season}E${query.episode}" else "",
            behaviorHints = StreamHints(
                proxyHeaders = ProxyHeaders(request = mapOf("Referer" to referer, "User-Agent" to WebsiteRules.BROWSER_UA)),
                bingeGroup = "website:$host"
            ),
            sourceName = site.name.ifBlank { host },
            sourceBase = site.url
        ).let { s -> if (FinderRules.classifyStream(s) is FinderRules.Verdict.Rejected) s.copy(url = null) else s }
    }

    companion object {
        private const val DOC_TIMEOUT_MS = 8_000L
        private const val SITE_BUDGET_MS = 25_000L
        private const val MAX_FRAME_DEPTH = 2
        private const val MAX_DIRECT = 6

        /** "example.com" -> "https://example.com/". */
        fun normaliseInput(raw: String): String {
            val t = raw.trim()
            return if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t"
        }
    }
}
