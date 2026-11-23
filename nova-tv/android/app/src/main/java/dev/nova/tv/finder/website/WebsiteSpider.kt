package dev.nova.tv.finder.website

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderSourceReport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The website-server finder.
 *
 * This is the counterpart of [dev.nova.tv.finder.StremioFinder], and it is a
 * different kind of source entirely: it does **not** look for addon manifests.
 * It looks for ordinary streaming websites -- the same kind the viewer adds
 * with "Add website" ([WebsiteFinder], [WebsiteRules]) -- so a title can be
 * played from a site without the viewer typing its address first.
 *
 * Two real, keyless engines feed it:
 *
 *  - **FMHY** (`fmhy/edit` on GitHub): the community streaming-site index,
 *    fetched as Markdown. Its link list is the widest public catalogue of
 *    working streaming sites.
 *  - **GitHub lists**: a repository search for streaming-site lists, then each
 *    repository's README, whose links are harvested the same way.
 *
 * A discovered link is not a source yet. Every candidate is profiled with
 * [WebsiteFinder.profile] (where is this site's search?) and then *probed*: a
 * known film is searched on the site and the result links are scored with
 * [WebsiteRules.titleScore]. Only a site that actually answers a title search
 * is kept, and only the search templates that worked are saved -- that is what
 * keeps the pool from filling with dead or unrelated hosts.
 */
class WebsiteSpider(
    private val http: HttpClient,
    private val finder: WebsiteFinder
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One round of discovery: verified sites plus how the engines did. */
    data class Harvest(
        val sites: List<SavedWebsite>,
        /** False once both engines have yielded all the links they have. */
        val more: Boolean,
        val sources: List<FinderSourceReport>
    )

    suspend fun harvest(round: Int): Harvest = coroutineScope {
        val started = System.currentTimeMillis()
        val loaded = lists()
        val candidates = LinkedHashMap<String, String>()
        for ((_, text) in loaded.pages) {
            for (site in siteCandidates(text)) {
                val host = WebsitePoolKey.of(site)
                if (host.isNotEmpty()) candidates.putIfAbsent(host, site)
            }
        }

        val ordered = candidates.values.toList()
        val start = (round.coerceAtLeast(0) * PER_ROUND)
        val slice = ordered.drop(start).take(PER_ROUND)
        val gate = Semaphore(PROFILE_CONCURRENCY)
        val verified = slice.map { url ->
            async { gate.withPermit { verify(url) } }
        }.awaitAll().filterNotNull()

        val elapsed = System.currentTimeMillis() - started
        val reports = listOf(
            report("fmhy", loaded.fmhy, loaded.pages.size, verified.size, elapsed),
            report("github-lists", loaded.github.takeIf { it > 0 }?.let { "" }, loaded.github, verified.size, elapsed),
            report("yandex-crawler", loaded.web.takeIf { it > 0 }?.let { "" }, loaded.web, verified.size, elapsed)
        )
        Harvest(
            sites = verified,
            more = ordered.size > start + PER_ROUND,
            sources = reports
        )
    }

    private fun report(id: String, body: String?, seen: Int, found: Int, elapsed: Long) = FinderSourceReport(
        id = id,
        status = if (body == null) FinderSourceReport.Status.FAILED else FinderSourceReport.Status.COMPLETE,
        seen = seen,
        checked = seen,
        candidates = found,
        error = if (body == null) "List unavailable" else "",
        elapsedMs = elapsed
    )

    /**
     * Profile a candidate, then require proof that it can be searched: a known
     * title has to come back from at least one of its search templates with
     * two plausible result links (or direct media on the page itself).
     */
    private suspend fun verify(url: String): SavedWebsite? {
        val site = runCatching { withTimeoutOrNull(PROFILE_TIMEOUT_MS) { finder.profile(url) } }.getOrNull()
            ?: return null
        val working = LinkedHashSet<String>()
        for (title in PROBE_TITLES) {
            for (template in site.templates.take(4)) {
                val probe = WebsiteRules.buildSearchUrl(template, title)
                if (WebsiteRules.guardUrl(probe) == null) continue
                val doc = runCatching { finder.fetchDocument(probe) }.getOrNull() ?: continue
                val query = WebsiteRules.Query(titles = listOf(title), type = "movie")
                val hits = WebsiteRules.parseResultLinks(doc.text, doc.finalUrl)
                    .count { WebsiteRules.titleScore(it, query) >= 60 }
                val direct = WebsiteRules.parseWatch(doc.text, doc.finalUrl).direct.isNotEmpty()
                if (hits >= 2 || direct) {
                    working += template
                    break
                }
            }
            if (working.isNotEmpty()) break
        }
        if (working.isEmpty()) return null
        return site.copy(templates = working.toList())
    }

    private class Lists(val pages: List<Pair<String, String>>, val fmhy: String?, val github: Int, val web: Int)

    private var cachedLists: Lists? = null

    /**
     * The lists change daily, not per round: fetch them once and reuse them so
     * a 24-site round costs 24 profiles, not 24 list downloads as well.
     */
    private suspend fun lists(): Lists {
        cachedLists?.let { return it }
        val fmhy = runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { fetchText(FMHY_VIDEO) } }.getOrNull()
        val github = runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { githubLists() } }.getOrNull().orEmpty()
        val web = runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { yandexAndCrawlerLists(fmhy) } }.getOrNull().orEmpty()
        val pages = buildList {
            if (fmhy != null) add("fmhy" to fmhy)
            github.forEach { (name, text) -> add("github:$name" to text) }
            addAll(web)
        }
        val result = Lists(pages, fmhy, github.size, web.size)
        if (pages.isNotEmpty()) cachedLists = result
        return result
    }

    /** Search beyond curated lists, then crawl a bounded set of result hosts. */
    private suspend fun yandexAndCrawlerLists(seed: String?): List<Pair<String, String>> = coroutineScope {
        val queries = listOf(
            "free streaming sites watch movies series",
            "watch movies online streaming site",
            "streaming website server list"
        )
        val searches = queries.map { query ->
            async {
                runCatching {
                    fetchText("https://yandex.com/search/?text=" + java.net.URLEncoder.encode(query, "UTF-8"))
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        val roots = buildList {
            if (seed != null) addAll(siteCandidates(seed).take(32))
            searches.forEach { addAll(siteCandidates(it).take(32)) }
        }.distinct().take(64)
        val crawled = roots.map { url ->
            async { runCatching { "crawler:$url" to fetchText(url) }.getOrNull() }
        }.awaitAll().filterNotNull()
        crawled + searches.mapIndexed { index, text -> "yandex:$index" to text }
    }

    /** The link lists, from GitHub, in parallel through the guarded client. */
    private suspend fun githubLists(): List<Pair<String, String>> = coroutineScope {
        val search = runCatching {
            fetchText(
                "https://api.github.com/search/repositories" +
                    "?q=streaming+sites+list+in:name,description&sort=stars&order=desc&per_page=12"
            )
        }.getOrNull() ?: return@coroutineScope emptyList()
        val repos = runCatching {
            (json.parseToJsonElement(search) as? JsonObject)
                ?.get("items") as? JsonArray
        }.getOrNull().orEmpty().filterIsInstance<JsonObject>().mapNotNull { repo ->
            val full = (repo["full_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val branch = (repo["default_branch"] as? JsonPrimitive)?.contentOrNull ?: "main"
            if (!Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""").matches(full)) return@mapNotNull null
            full to branch
        }
        repos.map { (full, branch) ->
            async {
                val text = runCatching { fetchText("https://raw.githubusercontent.com/$full/$branch/README.md") }
                    .getOrNull()
                if (text == null) null else full.substringAfter('/') to text
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchText(url: String): String =
        http.get(url, headers = ACCEPT_TEXT, attempts = 1, limitBytes = MAX_LIST_BYTES)

    companion object {
        private const val FMHY_VIDEO = "https://raw.githubusercontent.com/fmhy/edit/main/docs/video.md"
        /** Sites verified per round; each costs a profile plus a probe request. */
        const val PER_ROUND = 24
        const val PROFILE_CONCURRENCY = 6
        private const val SOURCE_TIMEOUT_MS = 20_000L
        private const val PROFILE_TIMEOUT_MS = 30_000L
        private const val MAX_LIST_BYTES = 2_000_000L
        private val ACCEPT_TEXT = mapOf(
            "Accept" to "text/plain,text/markdown,application/json",
            "User-Agent" to "NovaTV-Website-Finder/1.0"
        )
        private val PROBE_TITLES = listOf("inception", "the matrix")

        /**
         * Hosts that are never a playable website: link hubs, social, storage
         * and the lists themselves. Kept as a function so it stays testable.
         */
        private val BLOCKED_HOSTS = setOf(
            "github.com", "raw.githubusercontent.com", "gist.github.com", "gitlab.com",
            "reddit.com", "old.reddit.com", "wikipedia.org", "wikimedia.org", "archive.org",
            "youtube.com", "youtu.be", "twitter.com", "x.com", "facebook.com", "instagram.com",
            "t.me", "telegram.org", "discord.gg", "discord.com", "medium.com",
            "google.com", "docs.google.com", "drive.google.com", "rentry.co", "rentry.org",
            "fmhy.net", "fmhy.pages.dev", "pastebin.com", "patreon.com", "ko-fi.com",
            "buymeacoffee.com", "paypal.com", "amazon.com", "imdb.com", "tmdb.org",
            "themoviedb.org", "justwatch.com", "trakt.tv", "letterboxd.com"
        )

        /** File extensions that mean "a document", not "a streaming site". */
        private val FILE_SUFFIX = Regex(
            """\.(?:html?|php|md|txt|json|xml|png|jpe?g|gif|webp|svg|ico|css|js|pdf|zip|rar|7z|torrent|mp4|mkv|m3u8)$""",
            RegexOption.IGNORE_CASE
        )

        private val LINK = Regex("""https://[^\s)\]"'<>]+""")

        /**
         * Pull website candidates out of a Markdown/text list: https links,
         * reduced to the site root, blocked hosts and documents dropped, one
         * entry per host. Pure (no network), so it is unit-tested.
         */
        fun siteCandidates(text: String): List<String> {
            val out = LinkedHashMap<String, String>()
            for (match in LINK.findAll(text)) {
                val raw = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '*', '\'', '"', '/')
                val url = runCatching { java.net.URL(raw) }.getOrNull() ?: continue
                if (url.protocol != "https") continue
                val host = url.host.lowercase().removePrefix("www.")
                if (host.isEmpty() || !host.contains('.')) continue
                if (host.endsWith(".onion")) continue
                if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) continue
                val last = url.path.substringAfterLast('/')
                if (FILE_SUFFIX.containsMatchIn(last)) continue
                out.putIfAbsent(host, "https://$host/")
                if (out.size >= 2000) break
            }
            return out.values.toList()
        }
    }
}

/** The pool's own key rule, kept next to the spider so both agree. */
private object WebsitePoolKey {
    fun of(url: String): String = runCatching {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.").orEmpty()
    }.getOrDefault("")
}
