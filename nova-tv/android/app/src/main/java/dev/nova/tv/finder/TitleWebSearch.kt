package dev.nova.tv.finder

import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/**
 * The title-specific finder.
 *
 * Every other finder walks directories and then asks each server about a title.
 * This one starts from the title: it searches the open web for the exact name
 * (plus year, and season/episode for a series) and turns the hosts that come
 * back into candidates. That is what the "Find servers for this title" button
 * on the detail screen runs, so pressing it searches the web for *only that
 * title* rather than re-running the general harvest.
 *
 * The result is not trusted: candidates go through
 * [dev.nova.tv.finder.pool.ServerTester] like everything else, and only streams
 * that answer for this exact id are merged into the player.
 */
class TitleWebSearch(
    private val http: HttpClient,
    private val web: WebCrawler = WebCrawler(http)
) {

    suspend fun search(request: TitleRequest): List<FinderCandidate> = coroutineScope {
        val title = request.meta.name?.trim().orEmpty()
        if (title.length < 2) return@coroutineScope emptyList()

        val queries = queriesFor(request, title)
        val gate = Semaphore(CONCURRENCY)

        val pages = queries.map { query ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                        runCatching {
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            http.get(
                                "https://html.duckduckgo.com/html/?q=$encoded",
                                BROWSER_HEADERS,
                                limitBytes = MAX_PAGE_BYTES
                            )
                        }.getOrNull()
                    }
                }
            }
        }.awaitAll().filterNotNull()

        // 1. Direct manifest links the results mention.
        val manifests = pages.flatMap { FinderRules.extractManifestUrls(it, limit = 40) }
            .map { FinderRules.publicHttpsUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_MANIFESTS)

        val fromManifests = web.resolveManifests(manifests, "title")

        // 2. Hosts that look like players, asked for a manifest at their root.
        val bases = pages.flatMap { page ->
            HREF.findAll(page).map { it.groupValues[1] }
                .map { absolutise(page, it) }
                .filter { it.startsWith("http") && looksPlayable(it) }
                .map { rootOf(it) }
                .toList()
        }.distinct().take(MAX_BASES)

        val probed = web.resolveManifests(bases.map { "$it/manifest.json" }, "title")

        // Only manifest-validated servers are returned. Embed players are
        // reported separately by [embedHosts] because they are not addons: the
        // engine registers them as websites, which the resolver can drive.
        (fromManifests + probed).distinctBy { ServerPoolKey.of(it.baseUrl) }
    }

    /**
     * The embed players worth registering for this title.
     *
     * They have no manifest and no `/stream` endpoint, so they can never be
     * pool candidates -- but they are exactly what the website resolver is for.
     */
    fun embedHosts(): List<String> = EMBED_HOSTS

    private fun queriesFor(request: TitleRequest, title: String): List<String> {
        val year = request.meta.year.orEmpty().take(4)
        val quoted = "\"$title\""
        val list = mutableListOf(
            "$quoted watch online free stream",
            "$quoted full movie stream",
            "$quoted stremio addon",
            "$quoted manifest.json stream"
        )
        if (year.length == 4) list += "$quoted $year watch online"
        if (request.meta.isSeries) {
            val s = request.season.coerceAtLeast(1)
            val e = request.episode.coerceAtLeast(1)
            list += "$quoted S${"%02d".format(s)}E${"%02d".format(e)} stream"
            list += "$quoted season $s episode $e watch online"
        }
        list += EMBED_HOSTS.take(4).map { host -> "$quoted site:${host.substringAfter("://")}" }
        return list.distinct().take(MAX_QUERIES)
    }

    private fun absolutise(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> {
            val root = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
            root + href
        }
        else -> base.substringBeforeLast('/') + "/" + href
    }

    private fun rootOf(url: String): String {
        val scheme = url.substringBefore("://")
        val host = url.substringAfter("://").substringBefore('/')
        return "$scheme://$host"
    }

    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".css")) return false
        return PLAYABLE_HINTS.any { lower.contains(it) }
    }

    companion object {
        private const val CONCURRENCY = 16
        private const val PAGE_TIMEOUT_MS = 9_000L
        private const val MAX_PAGE_BYTES = 1_500_000L
        private const val MAX_QUERIES = 12
        private const val MAX_MANIFESTS = 120
        private const val MAX_BASES = 80

        private val HREF = Regex("""href\s*=\s*["']([^"']{3,400})["']""", RegexOption.IGNORE_CASE)

        private val PLAYABLE_HINTS = listOf(
            "watch", "stream", "embed", "player", "movie", "film", "episode",
            "vidsrc", "videasy", "vidlink", "2embed", "multiembed", "embed.su",
            "moviesapi", "superembed", "vidbinge", "111movies", "vixsrc", "vidfast"
        )

        private val EMBED_HOSTS = listOf(
            "https://vidsrc.to", "https://vidsrc.xyz", "https://vidsrc.me",
            "https://vidsrc.pro", "https://vidsrc.net", "https://embed.su",
            "https://multiembed.mov", "https://www.2embed.cc", "https://player.videasy.net",
            "https://vidlink.pro", "https://moviesapi.club", "https://embedmaster.link",
            "https://superembed.stream", "https://111movies.com", "https://vidbinge.dev",
            "https://vidfast.pro", "https://vixsrc.to", "https://player.autoembed.cc"
        )

        private val BROWSER_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
    }
}
