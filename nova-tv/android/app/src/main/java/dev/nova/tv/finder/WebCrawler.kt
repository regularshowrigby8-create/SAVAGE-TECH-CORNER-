package dev.nova.tv.finder

import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * Breadth-first addon discovery.
 *
 * Three independent streams feed the candidate list:
 *
 *  1. **Directory graph** - the Stremio `addon_catalog` community + official JSON
 *     documents and the paginated `stremio-addons.net` API. These are the same
 *     directories ZeroPlay's `lib/server-finder/core.js` walked, and they are by
 *     far the densest source of live manifests.
 *  2. **Web crawl** - seed list pages (READMEs, awesome lists, GitHub search API)
 *     plus DuckDuckGo HTML search, then link-following on anything that looks
 *     addon-ish.
 *  3. **Embed seeds** - the well known embed players (vidsrc / embed.su /
 *     videasy / vidlink / 2embed ...). These are not Stremio addons, but the
 *     resolver can still turn them into playable streams, and they are rotated
 *     across rounds so every round probes a different slice.
 *
 * Everything is fetched through a single [Semaphore] so the whole crawl is
 * genuinely multithreaded (48 in flight) without melting the device.
 */
class WebCrawler(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun crawl(round: Int, budgetMs: Long = BUDGET_MS): List<FinderCandidate> = coroutineScope {
        val directoryBudget = (budgetMs * 45 / 100).coerceAtLeast(4_000L)
        val seedBudget = (budgetMs * 40 / 100).coerceAtLeast(3_000L)
        val followBudget = (budgetMs * 20 / 100).coerceAtLeast(2_000L)
        val resolveBudget = (budgetMs * 55 / 100).coerceAtLeast(4_000L)

        val directory = async { withTimeoutOrNull(directoryBudget) { directory(round) }.orEmpty() }
        val seeds = async { withTimeoutOrNull(seedBudget) { seedPages(round) }.orEmpty() }
        val repos = async { withTimeoutOrNull(seedBudget) { github(round) }.orEmpty() }

        val pages = seeds.await()
        val followed = async { withTimeoutOrNull(followBudget) { follow(pages) }.orEmpty() }

        val texts = (pages.map { it.text } + followed.await()).filter { it.isNotBlank() }
        val fromText = texts.flatMap { FinderRules.extractManifestUrls(it) }
        val fromBases = texts.flatMap { addonBases(it) }.map { "$it/manifest.json" }

        val urls = (fromText + fromBases)
            .map { FinderRules.publicHttpsUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_MANIFESTS)

        val resolved = withTimeoutOrNull(resolveBudget) { resolve(urls, emptyList()) }.orEmpty()
        val fromGitHub = withTimeoutOrNull(resolveBudget / 2) { repos.await() }.orEmpty()

        (directory.await() + resolved + fromGitHub).distinctBy { ServerPoolKey.of(it.baseUrl) }
    }

    // ---------------------------------------------------------------- directory

    private suspend fun directory(round: Int): List<FinderCandidate> = coroutineScope {
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<FinderCandidate>>>()

        jobs += async { manifestsIn(COMMUNITY_CATALOG) }
        jobs += async { manifestsIn(OFFICIAL_CATALOG) }

        val pages = API_PAGES_PER_ROUND.coerceAtMost(API_TOTAL_PAGES)
        val start = (round * pages) % API_TOTAL_PAGES
        for (i in 0 until pages) {
            val page = (start + i) % API_TOTAL_PAGES
            jobs += async { manifestsIn("$ADDONS_API?page=$page&limit=100") }
        }

        jobs.awaitAll().flatten()
            .distinctBy { ServerPoolKey.of(it.baseUrl) }
            .take(MAX_DIRECTORY_MANIFESTS)
    }

    /** Walk an arbitrary JSON document and pull every manifest-looking URL out of it. */
    private suspend fun manifestsIn(url: String): List<FinderCandidate> {
        val body = fetchJson(url, MAX_DIRECTORY_BYTES) ?: return emptyList()
        val found = LinkedHashSet<String>()
        walk(body) { key, value ->
            if (key != null && key.lowercase() in MANIFEST_KEYS) found += value
            else if (key == null && value.contains("/manifest.json")) found += value
        }
        val urls = found.map { normaliseManifest(it) }.filter { it.isNotEmpty() }.distinct()
        return resolve(urls.take(MAX_DIRECTORY_MANIFESTS), emptyList())
    }

    private fun walk(element: JsonElement, visit: (String?, String) -> Unit) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                when (value) {
                    is JsonPrimitive -> if (value.isString) visit(key, value.content)
                    else -> walk(value, visit)
                }
            }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun normaliseManifest(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("http")) return ""
        return if (trimmed.contains("manifest.json")) trimmed
        else trimmed.trimEnd('/') + "/manifest.json"
    }

    /**
     * Embed players for this round.
     *
     * These are HTML players, not Stremio addons: they have no `manifest.json`
     * and they do not answer `/stream/...`. They therefore cannot be pool
     * candidates -- [dev.nova.tv.finder.pool.ServerTester] would reject every
     * one of them at the manifest step, and a stored one could never return a
     * stream. They are handed to the website resolver instead, which knows how
     * to drive a player page into direct media.
     */
    fun embedHosts(round: Int): List<String> =
        EMBED_SEEDS.filterIndexed { index, _ -> index % EMBED_ROTATION == round % EMBED_ROTATION }

    /**
     * Build a [FinderCandidate] from a base URL and its parsed manifest.
     *
     * A candidate only exists once [FinderRules.parseManifest] has accepted the
     * document, so every candidate here declares a `stream` resource for film
     * or series and is not a torrent index or a duplicate VOD front-end. That
     * is what makes the crawler a *server* finder rather than an addon finder.
     */
    private fun candidate(base: String, source: String, info: FinderRules.ManifestInfo): FinderCandidate =
        FinderCandidate(
            manifestUrl = "$base/manifest.json",
            baseUrl = base,
            name = info.name,
            description = info.description,
            version = info.version,
            types = info.types,
            idPrefixes = info.idPrefixes,
            searchable = info.searchable,
            stars = 0,
            from = "Server Finder \u00b7 $source"
        )

    // -------------------------------------------------------------- web crawl

    private suspend fun seedPages(round: Int): List<Page> = coroutineScope {
        val pages = LIST_PAGES.map { url -> async { fetchPage(url) } }
        val searches = searchPages(round).map { url -> async { fetchPage(url) } }
        (pages + searches).awaitAll().filterNotNull()
    }

    private suspend fun searchPages(round: Int): List<String> = coroutineScope {
        val total = SEARCH_QUERIES.size
        val take = SEARCH_PER_ROUND.coerceAtMost(total)
        val start = (round * take) % total
        val queries = (0 until take).map { SEARCH_QUERIES[(start + it) % total] }
        queries.map { query ->
            async {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val html = http.get(
                    "https://html.duckduckgo.com/html/?q=$encoded",
                    BROWSER_HEADERS,
                    limitBytes = MAX_PAGE_BYTES
                )
                HREF.findAll(html).map { it.groupValues[1] }
                    .filter { it.startsWith("http") && looksAddonish(it) }
                    .toList()
            }
        }.awaitAll().flatten()
    }

    private suspend fun follow(pages: List<Page>): List<String> = coroutineScope {
        val links = pages.flatMap { page ->
            HREF.findAll(page.text).map { it.groupValues[1] }
                .map { absolutise(page.url, it) }
                .filter { it.startsWith("http") && looksAddonish(it) }
                .toList()
        }.distinct().take(MAX_FOLLOW)

        links.map { link -> async { fetchPage(link)?.text } }.awaitAll().filterNotNull()
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

    private fun looksAddonish(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".svg")) return false
        return ADDON_HINTS.any { lower.contains(it) }
    }

    private fun addonBases(text: String): List<String> {
        val fromUrls = URL_PATTERN.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'') }
            .filter { it.contains("manifest.json") || looksAddonish(it) }
            .map { it.substringBefore("/manifest.json").trimEnd('/') }
            .toList()
        return fromUrls.filter { it.startsWith("http") }.distinct().take(MAX_BASES)
    }

    // ------------------------------------------------------------------ github

    private suspend fun github(round: Int): List<FinderCandidate> = coroutineScope {
        val total = GITHUB_QUERIES.size
        val take = GITHUB_PAGES.coerceAtMost(total)
        val start = (round * take) % total
        val queries = (0 until take).map { GITHUB_QUERIES[(start + it) % total] }

        val repos = queries.map { query ->
            async {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val body = fetchJson(
                    "https://api.github.com/search/repositories?q=$encoded&sort=stars&order=desc&per_page=100",
                    MAX_DIRECTORY_BYTES
                )
                body?.let { collectRepoBases(it) }.orEmpty()
            }
        }.awaitAll().flatten().distinct().take(MAX_READMES)

        val pairs = repos.map { repo ->
            async {
                // 1. Metadata mining. core.js read `homepage` and `description`
                //    before touching the README: an addon repo almost always
                //    puts its manifest URL in one or the other, and that costs
                //    no extra request. Nova previously went straight to the
                //    README, so addons whose README is a screenshot lost.
                val inline = FinderRules.extractManifestUrls(repo.metaText)
                    .map { FinderRules.publicHttpsUrl(it) }

                // 2. GitHub Pages. A repo that publishes a Pages site very
                //    often serves its manifest from the conventional path even
                //    when no document mentions it.
                val pages = repo.pagesManifests()

                // 3. README, two ways. The API endpoint is richer but is rate
                //    limited to 60 requests an hour unauthenticated; the raw
                //    host is not. Trying raw first (and the API as fallback)
                //    means a rate-limited round still discovers addons.
                val readme = readmeManifests(repo)

                (inline + pages + readme).distinct().map { it to repo }
            }
        }.awaitAll().flatten()

        // Provenance: remember which repository each manifest came from, then
        // stamp it onto the candidate so the Sources screen can show the origin.
        val origin = HashMap<String, RepoRef>()
        pairs.forEach { (url, repo) -> origin[ServerPoolKey.of(url)] = repo }

        val manifests = pairs.map { it.first }.filter { it.isNotEmpty() }.distinct().take(MAX_GITHUB_MANIFESTS)
        resolve(manifests, emptyList()).map { found ->
            val repo = origin[ServerPoolKey.of("${found.baseUrl}/manifest.json")]
            if (repo == null) found
            else found.copy(
                homepage = repo.homepage,
                tags = repo.topics,
                stars = repo.stars
            )
        }
    }

    /**
     * A repository worth probing for a manifest, with the metadata the search
     * payload already gave us.
     *
     * Keeping `homepage`, `description` and `topics` means the manifest URL can
     * often be found without any second request, and it lets a candidate carry
     * its provenance through to the Sources screen.
     */
    private data class RepoRef(
        val full: String,
        val hasPages: Boolean,
        val branch: String,
        val homepage: String,
        val description: String,
        val topics: List<String>,
        val stars: Int
    ) {
        /** Free text that may contain a manifest URL. */
        val metaText: String get() = listOf(homepage, description).joinToString(" ")

        fun pagesManifests(): List<String> {
            if (!hasPages) return emptyList()
            val owner = full.substringBefore('/')
            val name = full.substringAfter('/')
            if (owner.isBlank() || name.isBlank()) return emptyList()
            return listOf(
                "https://$owner.github.io/$name/manifest.json",
                "https://$owner.github.io/manifest.json"
            )
        }

    }

    /**
     * Manifest URLs mentioned in a repository's README.
     *
     * Raw host first, REST API second: the API is capped at 60 requests an hour
     * unauthenticated, so a long crawl can exhaust it and start finding nothing.
     * `raw.githubusercontent.com` has no such cap, so the raw path keeps a round
     * productive after the quota is gone.
     */
    private suspend fun readmeManifests(repo: RepoRef): List<String> {
        val raw = fetchText("https://raw.githubusercontent.com/${repo.full}/${repo.branch}/README.md")
        val text = raw ?: fetchJson("https://api.github.com/repos/${repo.full}/readme", MAX_PAGE_BYTES)
            ?.let { readmeText(it) }
            .orEmpty()
        if (text.isBlank()) return emptyList()
        return FinderRules.extractManifestUrls(text).map { FinderRules.publicHttpsUrl(it) }
    }

    private fun collectRepoBases(body: JsonElement): List<RepoRef> {
        val root = body as? JsonObject ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val full = obj.str("full_name") ?: return@mapNotNull null
            if (!SLUG.matches(full)) return@mapNotNull null
            val pages = obj["has_pages"]?.toString()?.trim('"')?.toBooleanStrictOrNull() ?: false
            val branch = obj.str("default_branch")
                .replace(Regex("[^A-Za-z0-9_.-]"), "")
                .take(80)
                .ifBlank { "main" }
            val topics = (obj["topics"] as? JsonArray).strings().take(20)
            val stars = obj["stargazers_count"]?.toString()?.toIntOrNull() ?: 0
            RepoRef(
                full = full,
                hasPages = pages,
                branch = branch,
                homepage = obj.str("homepage"),
                description = obj.str("description"),
                topics = topics,
                stars = stars
            )
        }
    }

    private fun readmeText(body: JsonElement): String {
        val obj = body as? JsonObject ?: return ""
        val encoded = obj.str("content") ?: return ""
        return runCatching {
            String(android.util.Base64.decode(encoded.replace("\n", ""), android.util.Base64.DEFAULT))
        }.getOrDefault("")
    }

    // ----------------------------------------------------------------- resolve

    suspend fun resolveManifests(urls: List<String>, source: String = "web"): List<FinderCandidate> =
        resolve(urls, emptyList(), source)

    suspend fun json(url: String, maxBytes: Long = MAX_PAGE_BYTES): JsonElement? = fetchJson(url, maxBytes)

    private suspend fun resolve(
        urls: List<String>,
        already: List<FinderCandidate>,
        source: String = "web"
    ): List<FinderCandidate> =
        coroutineScope {
            val gate = Semaphore(CONCURRENCY)
            val known = already.map { ServerPoolKey.of(it.baseUrl) }.toSet()
            val pending = urls.filter { ServerPoolKey.of(it) !in known }.distinct()

            pending.map { url ->
                async {
                    // Failure isolation. core.js ran its manifest checks through
                    // mapLimit, which caught per item, so one dead host cost one
                    // candidate. A bare `awaitAll` over throwing children does
                    // the opposite: the first DNS failure cancels every sibling
                    // and the whole round returns nothing -- the exact shape of
                    // the "nothing new found" bug. Every item is therefore
                    // wrapped, and only its own slot is lost.
                    runCatching {
                        gate.withPermit {
                            val body = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                                http.get(url, ACCEPT_JSON, limitBytes = FinderRules.MAX_MANIFEST_BYTES.toLong())
                            } ?: return@withPermit null
                            val manifest = FinderRules.parseManifest(body) ?: return@withPermit null
                            val base = FinderRules.baseFromManifestUrl(url)
                            if (base.isEmpty()) return@withPermit null
                            if (!FinderRules.isStorable(manifest.name, base)) return@withPermit null
                            candidate(base, source, manifest)
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

    // ------------------------------------------------------------------ fetch

    private suspend fun fetchPage(url: String): Page? = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
        runCatching { Page(url, http.get(url, BROWSER_HEADERS, limitBytes = MAX_PAGE_BYTES)) }.getOrNull()
    }

    private suspend fun fetchJson(url: String, maxBytes: Long): JsonElement? =
        withTimeoutOrNull(DIRECTORY_TIMEOUT_MS) {
            runCatching { json.parseToJsonElement(http.get(url, ACCEPT_JSON, limitBytes = maxBytes)) }
                .getOrNull()
        }

    /**
     * Plain text fetch, used for `raw.githubusercontent.com` READMEs.
     *
     * The raw host is not rate limited the way the REST API is, so a round that
     * has exhausted its GitHub API quota still finds addons through this path.
     */
    private suspend fun fetchText(url: String): String? =
        withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
            runCatching { http.get(url, BROWSER_HEADERS, limitBytes = MAX_PAGE_BYTES) }.getOrNull()
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private data class Page(val url: String, val text: String)

    companion object {
        const val CONCURRENCY = 48
        private const val BUDGET_MS = 45_000L
        private const val MANIFEST_TIMEOUT_MS = 7_000L
        private const val DIRECTORY_TIMEOUT_MS = 10_000L
        private const val MAX_PAGE_BYTES = 2_000_000L
        private const val MAX_DIRECTORY_BYTES = 4_000_000L
        private const val MAX_FOLLOW = 240
        private const val MAX_BASES = 400
        private const val MAX_MANIFESTS = 1_200
        private const val MAX_DIRECTORY_MANIFESTS = 900
        private const val MAX_READMES = 400
        private const val MAX_GITHUB_MANIFESTS = 600
        private const val GITHUB_PAGES = 10
        private const val SEARCH_PER_ROUND = 8
        private const val API_TOTAL_PAGES = 24
        private const val API_PAGES_PER_ROUND = 4
        private const val EMBED_ROTATION = 3

        private const val COMMUNITY_CATALOG = "https://v3-cinemeta.strem.io/addon_catalog/all/community.json"
        private const val OFFICIAL_CATALOG = "https://v3-cinemeta.strem.io/addon_catalog/all/official.json"
        private const val ADDONS_API = "https://stremio-addons.net/api/v0/addons"

        private val MANIFEST_KEYS = setOf("manifestUrl", "transportUrl", "url", "manifest")
        private val SLUG = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val HREF = Regex("""href\s*=\s*["']([^"']{3,400})["']""", RegexOption.IGNORE_CASE)
        private val URL_PATTERN = Regex("""https?://[^\s"'<>()\[\]{}]+""", RegexOption.IGNORE_CASE)

        private val ADDON_HINTS = listOf(
            "addon", "stremio", "manifest", "plugin", "extension", "catalog", "catalogue",
            "media-server", "mediaserver", "jellyfin", "emby", "kodi", "xbmc", "stream",
            "embed", "player", "vidsrc", "videasy", "vidlink", "moviesapi", "superembed",
            "2embed", "multiembed", "vidbinge", "111movies", "embedmaster"
        )

        private val EMBED_SEEDS = listOf(
            "https://vidsrc.to", "https://vidsrc.xyz", "https://vidsrc.me",
            "https://vidsrc.pro", "https://vidsrc.net", "https://vidsrc.in",
            "https://embed.su", "https://embed.su/api", "https://multiembed.mov",
            "https://www.2embed.cc", "https://2embed.to", "https://player.videasy.net",
            "https://vidlink.pro", "https://moviesapi.club", "https://embedmaster.link",
            "https://superembed.stream", "https://111movies.com", "https://vidbinge.dev",
            "https://fmovies.to", "https://watchsomuch.tv", "https://embed-api.asplantnet.com",
            "https://player.autoembed.cc", "https://autoembed.co", "https://vidfast.pro",
            "https://vidora.su", "https://vixsrc.to", "https://streamsrcs.2embed.cc"
        )

        private val LIST_PAGES = listOf(
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/main/README.md",
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/master/README.md",
            "https://raw.githubusercontent.com/varun-raj/awesome-stremio-addons/main/README.md",
            "https://raw.githubusercontent.com/itsmepvr/awesome-stremio/master/README.md",
            "https://raw.githubusercontent.com/mrcanelas/tmdb-addon/main/README.md",
            "https://raw.githubusercontent.com/Stremio/addon-helloworld/master/README.md",
            "https://raw.githubusercontent.com/doingodswork/awesome-stremio-addons/master/README.md",
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/main/lists/community.md",
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/main/lists/official.md",
            "https://api.github.com/search/repositories?q=topic%3Astremio-addon&sort=stars&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=stremio+addon+in%3Aname%2Cdescription&sort=stars&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=manifest.json+stream+addon&sort=stars&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=topic%3Astremio&sort=updated&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=topic%3Aaddon+stremio&sort=updated&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=stremio+streaming+addon&sort=updated&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=stremio+debrid+addon&sort=updated&order=desc&per_page=100"
        )

        private val SEARCH_QUERIES = listOf(
            "stremio addon manifest.json list",
            "stremio addon install url",
            "stremio addon directory",
            "stremio addon netlify manifest",
            "stremio addon vercel manifest",
            "stremio addon cloudflare workers manifest",
            "stremio addon deno deploy manifest",
            "stremio addon render manifest",
            "stremio addon railway manifest",
            "stremio addon github pages manifest",
            "stremio addon embed player manifest",
            "stremio addon iframe player",
            "vidsrc stremio addon",
            "videasy stremio addon",
            "vidlink stremio addon",
            "2embed stremio addon",
            "stremio movies addon manifest",
            "stremio tv shows addon manifest",
            "stremio anime addon manifest",
            "stremio live tv addon manifest",
            "stremio sports addon manifest",
            "stremio subtitles addon manifest",
            "stremio torrent addon manifest",
            "stremio direct stream addon manifest",
            "stremio addon index json"
        )

        private val GITHUB_QUERIES = listOf(
            "topic:stremio",
            "topic:stremio-addon",
            "stremio addon catalog",
            "stremio addon stream",
            "stremio addon manifest",
            "stremio embed addon",
            "stremio player addon",
            "stremio movies addon",
            "stremio series addon",
            "stremio anime addon",
            "stremio live addon",
            "stremio sports addon",
            "stremio subtitles addon",
            "stremio debrid addon",
            "stremio torrent addon",
            "stremio addon in:readme manifest.json"
        )

        private val ACCEPT_JSON = mapOf(
            "Accept" to "application/json,text/plain;q=0.9,*/*;q=0.8",
            "User-Agent" to "NovaTV-WebCrawler/3.0"
        )
        private val BROWSER_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
    }
}

internal object ServerPoolKey {
    fun of(url: String): String = url.trim().lowercase()
        .substringAfter("://")
        .replace(Regex("""/manifest\.json.*$"""), "")
        .substringBefore('?').substringBefore('#')
        .trimEnd('/')
}
