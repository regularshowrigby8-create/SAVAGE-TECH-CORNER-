package dev.nova.tv.finder.discovery

import dev.nova.tv.finder.translated.FinderHelpers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Seedless discovery.
 *
 * The old finders started from a hardcoded list of sites. On device every one
 * of those sites answered with the same 2262-byte Cloudflare interstitial, so
 * the crawl rendered a challenge page, extracted nothing, and stopped: the
 * seed list was not a starting point, it was the whole search space. A list
 * also cannot grow -- whatever is not in it can never be found.
 *
 * This module removes the list. Discovery is now *generated*:
 *
 *  - **Queries** come from a topic corpus (what a viewer wants) combined with
 *    modifiers, rotated by round, so round N asks a question round N-1 did not.
 *  - **Answers** come from open indexes that need no key: web search front
 *    ends, certificate transparency (every TLS certificate ever issued, i.e.
 *    every live domain), urlscan.io, Common Crawl, the GitHub repository API,
 *    the npm registry and Reddit's JSON search.
 *  - **Growth** comes from the link graph: every page found contributes its
 *    outbound links, and every domain found contributes its sitemap, so the
 *    surface expands from whatever it touches instead of from a list.
 *
 * Nothing here names a streaming site or an addon. The only hardcoded strings
 * are *topics* ("watch anime online", "stremio addon") and *keywords* used to
 * query the indexes -- which is what makes the search space unbounded.
 */
object Discovery {

    // -- what people look for ------------------------------------------------

    /** Viewer intents. Combined with [WEB_MODIFIERS] into search queries. */
    val WEB_TOPICS = listOf(
        "watch anime online free",
        "anime streaming site",
        "watch movies online free",
        "free movie streaming",
        "watch tv series online free",
        "hd movie streaming",
        "anime sub and dub",
        "watch cartoons online free",
        "korean drama streaming",
        "documentary streaming free",
        "watch tv shows without signup",
        "new anime episodes streaming",
        "streaming site no ads",
        "watch films online hd",
        "anime english sub streaming"
    )

    /** Query modifiers; empty means "ask the topic plainly". */
    val WEB_MODIFIERS = listOf(
        "", "site list", "reddit", "2025", "free hd", "no ads", "alternatives", "working"
    )

    /**
     * Domain keywords for the certificate-transparency and urlscan queries.
     *
     * These are fragments a streaming domain is likely to contain, not site
     * names: `crt.sh?q=%25anime%25` returns every certificate issued for any
     * domain containing "anime", which is thousands of live hosts -- far more
     * than any hand-written list, and it changes as the ecosystem moves.
     */
    val WEB_KEYWORDS = listOf(
        "anime", "9anime", "hianime", "animekai", "gogoanime", "animeflix",
        "kisskh", "dramacool", "fmovies", "primewire", "soap2day", "lookmovie",
        "vumoo", "123movies", "yesmovies", "solarmovie", "putlocker",
        "animesuge", "animepahe", "watchanime", "streamanime", "animetv",
        "moviesfree", "filmstream", "seriestream", "watchseries"
    )

    /** Addon intents. */
    val ADDON_TOPICS = listOf(
        "stremio addon",
        "stremio catalog addon",
        "stremio stream addon",
        "stremio subtitles addon",
        "stremio metadata addon",
        "stremio anime addon",
        "stremio debrid addon",
        "stremio live tv addon",
        "stremio iptv addon",
        "stremio scraper addon",
        "stremio manifest.json",
        "stremio addon list",
        "stremio community addons",
        "stremio addon directory",
        "stremio addon github"
    )

    /** Keywords for addon domain discovery. */
    val ADDON_KEYWORDS = listOf("stremio", "stremio-addon", "stremioaddon", "stremio-addons")

    // -- host hygiene --------------------------------------------------------

    /**
     * Hosts that answer queries but never host the thing we want. Kept as
     * fragments so `google.` also covers `google.co.uk` and `www.google.com`.
     */
    private val NOISE = listOf(
        "google.", "gstatic.", "googleapis.", "googlesyndication.", "doubleclick.",
        "bing.", "duckduckgo.", "mojeek.", "marginalia.", "searx.", "yandex.",
        "baidu.", "ecosia.", "startpage.", "brave.com", "qwant.",
        "wikipedia.", "wikimedia.", "wiktionary.", "fandom.com",
        "reddit.com", "redd.it", "youtube.", "youtu.be", "vimeo.", "dailymotion.",
        "twitter.", "x.com", "facebook.", "instagram.", "tiktok.", "pinterest.",
        "linkedin.", "tumblr.", "discord.", "telegram.", "whatsapp.",
        "amazon.", "ebay.", "aliexpress.", "etsy.", "walmart.",
        "cloudflare.", "akamai.", "fastly.", "jsdelivr.", "unpkg.", "cdnjs.",
        "w3.org", "schema.org", "archive.org", "stackoverflow.", "stackexchange.",
        "medium.com", "blogspot.", "wordpress.org", "gravatar.", "fontawesome.",
        "bootstrapcdn.", "jquery.", "github.com", "githubusercontent.com",
        "npmjs.", "npms.io", "crt.sh", "urlscan.io", "commoncrawl.org",
        "mozilla.org", "apple.com", "microsoft.com", "adobe.com",
        "imdb.com", "themoviedb.org", "tmdb.org", "trakt.tv", "myanimelist.net",
        "anilist.co", "kitsu.io", "justwatch.com"
    )

    /** True when [host] could plausibly be a streaming site or addon host. */
    fun usableHost(host: String): Boolean {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty() || !h.contains('.')) return false
        if (h.startsWith(".") || h.endsWith(".local")) return false
        return NOISE.none { h == it.trimEnd('.') || h.contains(it) }
    }

    // -- what a streaming site looks like ------------------------------------

    private val STREAM_WORDS = listOf(
        "anime", "movie", "movies", "film", "cinema", "cine", "serie", "series",
        "drama", "cartoon", "stream", "watch", "episode", "hianime", "9anime",
        "zoro", "animekai", "gogo", "kiss", "putlocker", "fmovies", "primewire",
        "soap2day", "lookmovie", "yesmovies", "solarmovie", "123movies",
        "dramacool", "kisskh", "vumoo", "tubi", "pluto", "crackle", "animepahe",
        "animesuge", "animetv", "dorama", "pelis", "serien", "vod"
    )

    /** True when the host or path reads like a catalogue rather than a blog. */
    fun looksLikeStreaming(host: String, url: String = ""): Boolean {
        val blob = (host + " " + url).lowercase()
        return STREAM_WORDS.any { it in blob }
    }

    /** Paths that hold an episode/movie page, i.e. where a player lives. */
    val WATCH_PATH = Regex(
        "/(?:watch|anime|series|tv|movie|movies|episode|episodes|browse|title|" +
            "show|shows|film|films|drama|play|stream)/|/page/\\d+|page=\\d+",
        RegexOption.IGNORE_CASE
    )

    fun watchLike(url: String): Boolean = WATCH_PATH.containsMatchIn(url)

    // -- query generation ----------------------------------------------------

    private fun <T> rotate(items: List<T>, round: Int, count: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val n = minOf(count, items.size)
        return (0 until n).map { i -> items[(round * n + i) % items.size] }
    }

    /** Search-engine queries for one round of site discovery. */
    fun webQueries(round: Int): List<String> =
        rotate(WEB_TOPICS, round, 4).mapIndexed { i, topic ->
            val mod = WEB_MODIFIERS[(round + i) % WEB_MODIFIERS.size]
            if (mod.isEmpty()) topic else "$topic $mod"
        }

    /** Domain keywords for one round of index discovery. */
    fun webKeywords(round: Int): List<String> = rotate(WEB_KEYWORDS, round, 3)

    /** Search-engine queries for one round of addon discovery. */
    fun addonQueries(round: Int): List<String> = rotate(ADDON_TOPICS, round, 5)

    fun addonKeywords(round: Int): List<String> = rotate(ADDON_KEYWORDS, round, 2)

    // -- surface URLs --------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Web-search front ends that return server-rendered result pages.
     *
     * These are read as HTML, so they work without an API key. A blocked or
     * empty engine simply yields nothing and the other surfaces carry the
     * round; discovery never depends on any single one.
     */
    fun searchUrls(query: String): List<String> {
        val q = enc(query)
        return listOf(
            "https://html.duckduckgo.com/html/?q=$q",
            "https://www.mojeek.com/search?q=$q",
            "https://www.bing.com/search?q=$q&count=50"
        )
    }

    /** Certificate transparency: every certificate issued for a keyword. */
    fun ctUrls(keyword: String): List<String> =
        listOf("https://crt.sh/?q=%25${enc(keyword)}%25&output=json")

    /** urlscan.io: pages whose domain matches a keyword. */
    fun urlscanUrls(keyword: String): List<String> =
        listOf("https://urlscan.io/api/v1/search/?q=page.domain%3A*${enc(keyword)}*&size=100")

    /** Common Crawl's index list; the newest collection is read from it. */
    fun commonCrawlIndexList(): String = "https://index.commoncrawl.org/collinfo.json"

    fun commonCrawlUrls(index: String, keyword: String): List<String> =
        listOf("https://index.commoncrawl.org/$index-index?url=*.${enc(keyword)}.*&output=json&limit=200")

    /** GitHub repository search (no token needed for the search API). */
    fun githubUrls(query: String): List<String> =
        listOf("https://api.github.com/search/repositories?q=${enc(query)}+in:name,description,readme&sort=updated&per_page=100")

    /** npm registry search. */
    fun npmUrls(query: String): List<String> =
        listOf("https://registry.npmjs.org/-/v1/search?text=${enc(query)}&size=250")

    /** Reddit JSON search, both site-wide and inside the addon subreddit. */
    fun redditUrls(query: String): List<String> {
        val q = enc(query)
        return listOf(
            "https://www.reddit.com/r/StremioAddons/search.json?q=$q&restrict_sr=1&sort=new&limit=100",
            "https://www.reddit.com/search.json?q=$q&sort=new&limit=100"
        )
    }

    /**
     * True for the web-search front ends whose result pages [linksFromHtml]
     * reads. Those pages are link lists, not sites, so they get a parser of
     * their own instead of being crawled as if they were a streaming site.
     */
    fun isSearchHost(host: String): Boolean {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return false
        return SEARCH_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private val SEARCH_HOSTS = listOf(
        "duckduckgo.com",
        "mojeek.com",
        "bing.com",
        "search.marginalia.nu",
        "searx.be",
        "lite.duckduckgo.com"
    )

    /** Every discovery surface for one round of site discovery. */
    fun webSurfaces(round: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (q in webQueries(round)) out += searchUrls(q)
        for (kw in webKeywords(round)) {
            out += ctUrls(kw)
            out += urlscanUrls(kw)
        }
        out += commonCrawlIndexList()
        return out.toList()
    }

    /** Every discovery surface for one round of addon discovery. */
    fun addonSurfaces(round: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (q in addonQueries(round)) {
            out += githubUrls(q)
            out += npmUrls(q)
            out += redditUrls(q)
        }
        for (kw in addonKeywords(round)) {
            out += ctUrls(kw)
            out += urlscanUrls(kw)
        }
        out += searchUrls("stremio addon list manifest.json")
        return out.toList()
    }

    // -- parsers -------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val HREF = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
    private val UDDG = Regex("""[?&]uddg=([^&"']+)""")

    /** Absolute links in an HTML page, including DuckDuckGo's `uddg=` wrapper. */
    fun linksFromHtml(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in HREF.findAll(html)) {
            var raw = m.groupValues[1].trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("javascript:") || raw.startsWith("#") ||
                raw.startsWith("mailto:") || raw.startsWith("tel:")
            ) continue
            UDDG.find(raw)?.groupValues?.get(1)?.let { wrapped ->
                raw = runCatching { URLDecoder.decode(wrapped, "UTF-8") }.getOrDefault(wrapped)
            }
            val abs = FinderHelpers.norm(FinderHelpers.urljoin(base, raw))
            if (abs.startsWith("http")) out += abs
        }
        return out.toList()
    }

    /** Hosts from a crt.sh JSON response (`name_value` may hold many lines). */
    fun hostsFromCt(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in doc) {
            val names = ((el as? JsonObject)?.get("name_value") as? JsonPrimitive)?.contentOrNull.orEmpty()
            for (line in names.split('\n')) {
                val h = line.trim().lowercase().removePrefix("*.").trimEnd('.')
                if (h.isNotEmpty()) out += h
            }
        }
        return out.toList()
    }

    /** Domains from a urlscan.io search response. */
    fun hostsFromUrlscan(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val results = doc["results"] as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in results) {
            val page = (el as? JsonObject)?.get("page") as? JsonObject ?: continue
            val d = (page["domain"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim().orEmpty()
            if (d.isNotEmpty()) out += d
        }
        return out.toList()
    }

    /** Newest Common Crawl index id from `collinfo.json`. */
    fun latestCommonCrawlIndex(text: String): String {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return ""
        for (el in doc) {
            val id = ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (id.startsWith("CC-MAIN-")) return id
        }
        return ""
    }

    /** URLs from Common Crawl's newline-delimited JSON index response. */
    fun urlsFromCommonCrawl(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            val o = runCatching { json.parseToJsonElement(t) }.getOrNull() as? JsonObject ?: continue
            val u = (o["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (u.startsWith("http")) out += u
        }
        return out.toList()
    }

    /** `https://host/path/manifest.json` occurrences anywhere in a page. */
    private val MANIFEST_IN_TEXT = Regex(
        """https?://[^\s"'<>)\\]+?/manifest\.json""",
        RegexOption.IGNORE_CASE
    )

    fun manifestUrls(text: String): List<String> =
        MANIFEST_IN_TEXT.findAll(text).map { it.value }.distinct().toList()

    /** True when a page talks about Stremio addons in a way worth crawling. */
    fun looksLikeAddonPage(text: String): Boolean {
        val head = text.take(200_000).lowercase()
        if ("manifest.json" in head) return true
        if ("stremio" !in head) return false
        return "catalogs" in head || "resources" in head || "addon" in head
    }

    /** GitHub repo full names from a repository-search response. */
    fun reposFromGithub(text: String): List<Pair<String, String>> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val items = doc["items"] as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<Pair<String, String>>()
        for (el in items) {
            val o = el as? JsonObject ?: continue
            val full = (o["full_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (full.isEmpty()) continue
            val homepage = (o["homepage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val branch = (o["default_branch"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "main" }
            out += full to "$homepage|$branch"
        }
        return out.toList()
    }

    /** Homepage/demo/repository links from an npm search response. */
    fun linksFromNpm(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val items = ((doc["objects"] as? JsonArray) ?: (doc["results"] as? JsonArray)) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in items) {
            val item = el as? JsonObject ?: continue
            val pkg = (item["package"] as? JsonObject) ?: item
            val links = pkg["links"] as? JsonObject
            for (key in listOf("homepage", "demo", "repository")) {
                val v = ((links?.get(key) ?: pkg[key]) as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (v.startsWith("http")) out += v
            }
        }
        return out.toList()
    }
}
