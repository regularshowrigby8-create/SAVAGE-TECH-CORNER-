package dev.nova.tv.finder.discovery

import dev.nova.tv.finder.translated.FinderHelpers
import dev.nova.tv.finder.pipeline.EngineRegistry
import dev.nova.tv.finder.pipeline.PipelineKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
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
        // Measured, not assumed: Bing answers 200 with ~90 hrefs and 7 usable
        // hosts per query. Mojeek answers 403 "your network appears to be
        // sending automated queries" and DuckDuckGo answers 202 with an
        // anomaly page, both of which yield zero links no matter how they are
        // parsed. Only the engine that actually answers is asked.
        return listOf("https://www.bing.com/search?q=$q&count=50")
    }

    /**
     * Certificate transparency for one *domain*.
     *
     * crt.sh rejects a `%keyword%` substring query with 502 and answers a real
     * domain with 300 KB of JSON, so this engine is an expander (given a domain
     * we already have, list everything ever certified under it), not a
     * brand-discovery engine.
     */
    fun ctUrls(domain: String): List<String> =
        listOf("https://crt.sh/?q=${enc(domain)}&output=json")

    /**
     * urlscan.io for one *domain*.
     *
     * `page.domain:*brand*` is refused for anonymous callers ("Regular
     * Expressions and leading wildcard searches are not supported"), while
     * `domain:9anime.to` answers 200 with results. Same engine, only the
     * supported query shape.
     */
    fun urlscanUrls(domain: String): List<String> =
        listOf("https://urlscan.io/api/v1/search/?q=domain%3A${enc(domain)}&size=100")

    /** Common Crawl's index list; the newest collection is read from it. */
    fun commonCrawlIndexList(): String = "https://index.commoncrawl.org/collinfo.json"

    fun commonCrawlUrls(index: String, domain: String): List<String> =
        listOf("https://index.commoncrawl.org/$index-index?url=${enc(domain)}%2F*&output=json&limit=200")

    /** GitHub repository search (no token needed for the search API). */
    fun githubUrls(query: String): List<String> =
        listOf("https://api.github.com/search/repositories?q=${enc(query)}+in:name,description,readme&sort=updated&per_page=100")

    // -- passive engines -----------------------------------------------------
    //
    // Search engines are index-limited and pirate brands actively hide their
    // current domain from them, so `search -> 0 host(s)` is the *expected*
    // result, not a bug. These engines observe infrastructure instead of
    // ranking pages, which is why they still list the domain a brand is using
    // this week.

    /**
     * Cert Spotter for one *domain*.
     *
     * A bare brand is refused (`not_allowed_by_plan`: "Cannot search 9anime
     * because it is not beneath an eTLD available for public registration"),
     * so like crt.sh this is an expander over a domain we already know.
     */
    fun certSpotterUrls(domain: String): List<String> =
        listOf("https://api.certspotter.com/v1/issuances?domain=${enc(domain)}&include_subdomains=true&expand=dns_names")

    /**
     * Wayback CDX for one domain.
     *
     * `url=*.brand.*` is not a CDX pattern and returns `[]`; `matchType=domain`
     * is, and returns the archive's whole capture list for the host. The
     * archive remembers domains that no longer resolve, which is exactly the
     * shape of a rotated pirate brand.
     */
    fun waybackUrls(domain: String, limit: Int = 400): List<String> =
        listOf(
            "https://web.archive.org/cdx/search/cdx?url=${enc(domain)}&matchType=domain" +
                "&output=json&fl=original&collapse=urlkey&limit=$limit&filter=statuscode:200"
        )

    /** RapidDNS: passive DNS subdomain index, plain HTML table. */
    fun rapidDnsUrls(keyword: String): List<String> =
        listOf("https://rapiddns.io/subdomain/${enc(keyword)}?full=1")

    /**
     * AlienVault OTX passive DNS. Kept for the parser's sake, but anonymous
     * callers get `429 {"detail": "Anonymous access to this endpoint is
     * limited"}` on every request, so it is not part of a round.
     */
    fun otxUrls(keyword: String): List<String> =
        listOf("https://otx.alienvault.com/api/v1/indicators/domain/${enc(keyword)}/passive_dns")

    /**
     * TLD sweep: the same brand stem under every cheap TLD.
     *
     * A pirate brand does not disappear when its domain is seized; it moves to
     * the same label under a different TLD. `9anime.to` -> `9anime.rs`,
     * `9anime.is`, `9anime.bz`... This is the cheapest way to find the *current*
     * domain of a brand already seen, and it costs one DNS lookup per guess.
     */
    val TLD_SWEEP = listOf(
        "to", "rs", "is", "se", "bz", "st", "pe", "cc", "gs", "am", "mx", "tv", "nz",
        "fit", "ink", "pw", "lc", "vc", "si", "sx", "hn", "ki", "sh", "ac", "wf", "yt",
        "cn", "li", "nu", "lt", "com", "net", "org", "io", "co", "me", "ru", "su", "pl"
    )

    /**
     * Words that describe the *category*, not a brand.
     *
     * `brandOf("anime.live")` used to return `"anime"`, and the sweep then asked
     * RapidDNS for the literal keyword `anime`. RapidDNS answers that with every
     * `anime.*` domain that exists -- 95 hosts in one on-device round -- and a
     * measured sample of 14 of them was: 3 parked, 1 empty, 1 a 114-byte stub,
     * and the rest apparel shops and news portals. Not one was a streaming
     * site. The same word also went to Bing and the TLD sweep, so a single
     * generic host name poisoned three engines at once.
     *
     * A brand is a *distinctive* label (`aniwaves`, `gotaku`, `hianime`). A
     * category word is not, and sweeping it is pure budget burn, so it must
     * return null and expand nothing.
     */
    private val GENERIC_BRANDS = setOf(
        "anime", "animes", "manga", "cartoon", "cartoons", "donghua", "dub", "sub",
        "movie", "movies", "film", "films", "cinema", "series", "serie", "show",
        "shows", "episode", "episodes", "season", "tv", "television", "video",
        "videos", "stream", "streams", "streaming", "watch", "watching", "play",
        "player", "free", "online", "live", "hd", "full", "best", "top", "new",
        "site", "sites", "web", "app", "apps", "link", "links", "download",
        "media", "tube", "zone", "world", "land", "hub", "base", "spot", "point",
        "home", "house", "city", "life", "love", "art", "pro", "box", "cast",
        "cloud", "host", "server", "servers", "source", "sources", "mirror",
        "the", "my", "get", "one", "two", "all", "any", "now", "here", "go",
        "plus", "club", "cam", "cams", "xxx", "porn", "adult", "hentai",
        "sport", "sports", "news", "blog", "shop", "store", "games", "game"
    )

    /** Registrable label of a host, without digits, TLD or `www.`. */
    fun brandOf(host: String): String? {
        val labels = host.lowercase().split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return null
        var i = labels.size - 2
        while (i > 0 && labels[i].length <= 3 && labels[i] in SUFFIX_PART) i--
        val stem = labels[i].removePrefix("www").trimEnd(*"0123456789".toCharArray())
        if (stem.isEmpty()) return null
        // A category word is not a brand: sweeping it returns the whole category.
        if (stem in GENERIC_BRANDS) return null
        // Nor is a hyphenated pile of category words: `free-tv`, `watch-online`.
        val parts = stem.split('-', '_').filter { it.isNotEmpty() }
        if (parts.isNotEmpty() && parts.all { it in GENERIC_BRANDS }) return null
        // Nor a run-together pile of them: `watchseries`, `freemovies`,
        // `animeonline`. These are as un-sweepable as the words they are made
        // of, and they are common enough to be worth segmenting.
        if (segmentsIntoGeneric(stem)) return null
        // Digits *inside* a label are the norm in this space -- 9anime, 2embed,
        // 4anime, 1movies -- so requiring every character to be a letter threw
        // away the most sweepable brands there are. Only the trailing digits
        // are stripped (they are mirror counters), and a stem with no letter at
        // all is still rejected.
        return stem.takeIf {
            it.length in 3..24 && it.any(Char::isLetter) && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
        }
    }

    private val SUFFIX_PART = setOf("co", "com", "net", "org", "ac", "gov", "edu", "or", "ne", "go")

    /**
     * True when [s] can be cut entirely into [GENERIC_BRANDS] words.
     *
     * `watchseries` -> watch + series, `freemovies` -> free + movies. A real
     * brand leaves a remainder that is not a category word (`moviestar` ->
     * movies + tar, and `tar` is not generic), so this does not swallow brands.
     * Memoised because the stem is at most 24 characters and the recursion is
     * otherwise exponential.
     */
    private fun segmentsIntoGeneric(s: String): Boolean {
        if (s.length < 6) return false
        val memo = HashMap<Int, Boolean>()
        fun go(at: Int): Boolean {
            if (at == s.length) return true
            memo[at]?.let { return it }
            var end = at + 3
            while (end <= s.length && end - at <= 12) {
                if (s.substring(at, end) in GENERIC_BRANDS && go(end)) {
                    memo[at] = true
                    return true
                }
                end++
            }
            memo[at] = false
            return false
        }
        // Require at least two words: a single word is already covered above.
        var first = 3
        while (first < s.length) {
            if (s.substring(0, first) in GENERIC_BRANDS && go(first)) return true
            first++
        }
        return false
    }

    /** Host guesses for one brand stem, rotated by round so a sweep is finite. */
    fun tldSweep(brand: String, round: Int, count: Int = 12): List<String> =
        rotate(TLD_SWEEP, round, count).map { "$brand.$it" }

    /** True for the surfaces that answer with JSON and must never be rendered. */
    fun isJsonSurface(url: String): Boolean {
        if (EngineRegistry.isJsonSurface(url)) return true
        val u = url.lowercase()
        return "output=json" in u || "certspotter.com/v1/" in u ||
            "urlscan.io/api/" in u || "/api/v1/" in u || "search.json" in u ||
            "registry.npmjs.org/-/v1/" in u || "api.github.com/" in u ||
            "collinfo.json" in u || "output=json" in u || "cdx/search/cdx" in u
    }

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
        if (EngineRegistry.isSearchHost(h)) return true
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

    /**
     * Every discovery surface for one round of site discovery.
     *
     * Two tiers, because the engines do not accept the same input. RapidDNS and
     * Bing take a bare *brand* and answer with hosts; crt.sh, Cert Spotter,
     * urlscan and Wayback take a *domain* and answer with everything under it.
     * Asking the second tier for a brand is what produced `ct -> 0` and
     * `urlscan -> 403` on device, so a round only asks the brand tier and
     * [domainSurfaces] expands whatever it finds.
     */
    fun webSurfaces(round: Int): List<String> {
        val out = LinkedHashSet<String>()
        // The explicit engine registry is the primary discovery surface. It is
        // seedless, has at least fifty independently configured engines and is
        // shared by both product flavours through src/main.
        out += EngineRegistry.surfaceUrls(PipelineKind.WEBSITE, round, 1)
        for (q in webQueries(round)) out += searchUrls(q)
        // RapidDNS is the one passive engine that accepts a bare brand, and it
        // answers: 39 hosts for "anime", 41 for "9anime", 14 for "hianime".
        for (kw in webKeywords(round)) out += rapidDnsUrls(kw)
        out += commonCrawlIndexList()
        return out.toList()
    }

    /** Every discovery surface for one round of addon discovery. */
    fun addonSurfaces(round: Int): List<String> {
        val out = LinkedHashSet<String>()
        out += EngineRegistry.surfaceUrls(PipelineKind.ADDON, round, 1)
        for (q in addonQueries(round)) {
            out += githubUrls(q)
            out += npmUrls(q)
            out += redditUrls(q)
        }
        for (kw in addonKeywords(round)) out += rapidDnsUrls(kw)
        out += searchUrls("stremio addon list manifest.json")
        return out.toList()
    }

    /**
     * Expansion surfaces for a domain that is already known.
     *
     * This is the step that turns one discovered host into a corpus: crt.sh
     * lists every name ever certified under it, urlscan every page served from
     * it, Wayback every URL ever archived, Cert Spotter the same from a second
     * log. Each is a JSON document, so none of them needs a renderer.
     */
    fun domainSurfaces(domain: String, round: Int): List<String> {
        val d = domain.lowercase().trim()
        if (d.isEmpty() || '.' !in d) return emptyList()
        val out = LinkedHashSet<String>()
        // Rotate which expanders are asked so a round stays bounded; crt.sh in
        // particular is slow and occasionally 502s, and it is not worth four
        // requests per domain in one pass.
        when (round % 4) {
            0 -> { out += ctUrls(d); out += urlscanUrls(d) }
            1 -> { out += waybackUrls(d); out += certSpotterUrls(d) }
            2 -> { out += ctUrls(d); out += waybackUrls(d) }
            else -> { out += urlscanUrls(d); out += certSpotterUrls(d) }
        }
        return out.toList()
    }

    /**
     * True for a URL that *queries an index* rather than being a page on the
     * web. Such a URL must stay retryable: a round that got 0 because the
     * engine was momentarily down must not poison it forever. Real pages are
     * one-shot, because re-crawling them is what made the frontier loop.
     */
    fun isSurface(url: String): Boolean {
        if (EngineRegistry.isSurface(url)) return true
        val h = dev.nova.tv.finder.translated.FinderHelpers.hostOf(url)
        if (h.isEmpty()) return false
        return SURFACE_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Number of explicitly configured unrestricted engine definitions. */
    val engineCount: Int get() = EngineRegistry.ALL.size

    private val SURFACE_HOSTS = listOf(
        "bing.com", "duckduckgo.com", "mojeek.com", "marginalia.nu", "searx.be",
        "rapiddns.io", "crt.sh", "urlscan.io", "web.archive.org",
        "api.certspotter.com", "index.commoncrawl.org", "otx.alienvault.com",
        "api.github.com", "registry.npmjs.org", "npms.io", "reddit.com",
        "pypi.org", "hn.algolia.com"
    )

    // -- parsers -------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val HREF = Regex("""href\s*=\s*["']([^"'#]{4,400})["']""", RegexOption.IGNORE_CASE)
    private val PLAIN_URL = Regex("""(?:https?:)?//[a-z0-9.-]+\.[a-z]{2,12}(?:/[^\s"'<>)\]]*)?""", RegexOption.IGNORE_CASE)
    /** A hostname sitting alone in a table cell (RapidDNS). */
    private val HOST_CELL = Regex("""<td[^>]*>\s*([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)\s*</td>""", RegexOption.IGNORE_CASE)

    /**
     * Unwrap the redirect hop every search engine puts in front of a result.
     *
     * This is the bug that made every `search -> 0 host(s)` line: the crawler
     * read the raw `href`, saw `duckduckgo.com` or `bing.com`, filtered it as
     * the engine's own host, and reported nothing. The real destination was
     * sitting in a query parameter the whole time.
     *
     *  - DuckDuckGo: `/l/?uddg=<urlencoded>` (also `//duckduckgo.com/l/?uddg=`)
     *  - Bing:       `/ck/a?...&u=a1<base64url>`
     *  - Mojeek / others: `?url=`, `?to=`, `?r=`, `?target=`, `?q=`
     */
    object Unwrap {
        private val KEYS = listOf("uddg", "url", "u", "to", "r", "target", "q")

        fun of(href: String): String? {
            var h = href.trim()
            if (h.isEmpty()) return null
            if (h.startsWith("//")) h = "https:$h"
            if (!h.startsWith("http", ignoreCase = true)) return null
            val uri = runCatching { URI(h) }.getOrNull() ?: return null
            val query = uri.rawQuery
            if (query != null) {
                for (pair in query.split('&')) {
                    val i = pair.indexOf('=')
                    if (i <= 0) continue
                    val key = pair.substring(0, i)
                    if (key !in KEYS) continue
                    val raw = pair.substring(i + 1)
                    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    val candidate = if (key == "u" && decoded.startsWith("a1")) bingBase64(decoded) else decoded
                    if (candidate != null && candidate.startsWith("http", ignoreCase = true)) {
                        // A wrapper can point at another wrapper; one hop is enough.
                        return candidate
                    }
                }
            }
            // Still on an engine host with no usable parameter: not a result.
            val host = uri.host?.lowercase().orEmpty()
            if (isSearchHost(host)) return null
            return h
        }

        /** Bing's `u=a1<base64url>` payload, with the `a1` version prefix. */
        private fun bingBase64(value: String): String? = runCatching {
            val body = value.removePrefix("a1")
                .replace('-', '+')
                .replace('_', '/')
                .let { if (it.length % 4 != 0) it + "=".repeat(4 - it.length % 4) else it }
            String(java.util.Base64.getDecoder().decode(body), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * Absolute links in a page, with engine redirects unwrapped.
     *
     * Both `href` attributes and bare URLs in the markup are read, because a
     * JSON-ish result page and a script-rendered one carry the destination in
     * different places.
     */
    fun linksFromHtml(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in HREF.findAll(html)) {
            val raw = m.groupValues[1].trim()
            if (raw.startsWith("javascript:") || raw.startsWith("#") ||
                raw.startsWith("mailto:") || raw.startsWith("tel:")
            ) continue
            // Resolve BEFORE unwrapping. `Unwrap.of` only accepts an absolute
            // URL, so handing it `/watch/1` returned null and every relative
            // link on every page was silently dropped -- the crawler could
            // never follow a site's own navigation, only its off-site links.
            val absolute = if (raw.startsWith("//") || raw.startsWith("http", ignoreCase = true)) {
                raw
            } else {
                FinderHelpers.urljoin(base, raw)
            }
            val unwrapped = Unwrap.of(absolute) ?: continue
            val abs = FinderHelpers.norm(FinderHelpers.urljoin(base, unwrapped))
            if (abs.startsWith("http")) out += abs
        }
        for (m in PLAIN_URL.findAll(html)) {
            val unwrapped = Unwrap.of(m.value) ?: continue
            val abs = FinderHelpers.norm(unwrapped)
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

    /**
     * Cert Spotter: `[{ "dns_names": ["a.example", "b.example"] }, ...]`.
     *
     * Same data as crt.sh from a second certificate log, which matters because
     * a brand that has rotated its domain very recently is often only in one of
     * the two logs.
     */
    fun hostsFromCertSpotter(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in doc) {
            val names = (el as? JsonObject)?.get("dns_names") as? JsonArray ?: continue
            for (n in names) {
                val h = (n as? JsonPrimitive)?.contentOrNull.orEmpty()
                    .lowercase().removePrefix("*.").trim().trimEnd('.')
                if (h.isNotEmpty()) out += h
            }
        }
        return out.toList()
    }

    /**
     * Wayback CDX: `[["urlkey","timestamp","original"], ...]` with the header
     * row first. Only the `original` cell is a URL, and the host is what we
     * want -- the archive remembers domains that no longer resolve, which is
     * exactly the shape of a rotated pirate brand.
     */
    fun hostsFromWayback(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (row in doc) {
            val cells = row as? JsonArray ?: continue
            val original = (cells.lastOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
            val host = hostOfOriginal(original)
            if (host.isNotEmpty() && host != "web.archive.org") out += host
        }
        return out.toList()
    }

    /**
     * The host of a Wayback `original` cell, unwrapping a replay URL.
     *
     * A capture is often stored as
     * `https://web.archive.org/web/20200101000000/https://real.example/`,
     * whose URI host is `web.archive.org`. Reading that directly threw away
     * the very host the row was about, so every wrapped row looked like the
     * archive itself and `wayback -> 0 domain(s)` even on a 200 with rows.
     */
    private fun hostOfOriginal(original: String): String {
        var s = original.trim()
        val replay = Regex("^https?://web\\.archive\\.org/web/[0-9]+(?:[a-z]{2}_)?/(.+)$", RegexOption.IGNORE_CASE)
        replay.find(s)?.let { s = it.groupValues[1] }
        if (s.startsWith("//")) s = "https:$s"
        val host = runCatching { java.net.URI(s).host }.getOrNull()?.lowercase().orEmpty()
        return host.trimEnd('.')
    }

    /** RapidDNS: an HTML table whose first column is the resolved hostname. */
    fun hostsFromRapidDns(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in HOST_CELL.findAll(text)) {
            val h = m.groupValues[1].lowercase().trim().trimEnd('.')
            if (h.isNotEmpty()) out += h
        }
        return out.toList()
    }

    /** AlienVault OTX: `{"passive_dns":[{"hostname":"a.example"}, ...]}`. */
    fun hostsFromOtx(text: String): List<String> {
        val doc = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val rows = doc["passive_dns"] as? JsonArray ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (el in rows) {
            val h = ((el as? JsonObject)?.get("hostname") as? JsonPrimitive)?.contentOrNull.orEmpty()
                .lowercase().trim().trimEnd('.')
            if (h.isNotEmpty()) out += h
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
