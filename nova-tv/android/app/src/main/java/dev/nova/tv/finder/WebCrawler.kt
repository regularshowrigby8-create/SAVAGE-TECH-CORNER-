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
 * The open-web crawler that replaced the old fixed script sequence.
 *
 * The previous finder read three hard-coded directories in order and stopped
 * after eight manifests from each, so the pool ran dry long before its 10,000
 * slots were full. This one treats the whole web as the directory:
 *
 *  1. **Seed pages** - GitHub repository search across many queries, public
 *     "awesome" lists, addon index pages and web-search result pages, all
 *     fetched in parallel.
 *  2. **Link following** - every seed page is parsed for outbound links; the
 *     addon-looking ones are fetched too (depth 1, bounded), so a list that
 *     only links to other lists still yields servers.
 *  3. **Manifest probing** - every `manifest.json` link is fetched, and every
 *     addon-looking base URL is probed at `base/manifest.json`. A server only
 *     survives if its manifest parses as a stream addon for film or series.
 *
 * Everything is concurrent ([CONCURRENCY] requests in flight) and every stage
 * is time-boxed, so one dead host cannot hold up the round. Nothing here is
 * Stremio-directory specific: stremio-addons.net and the `addon_catalog` graph
 * belong to [StremioFinder] and are deliberately not repeated.
 */
class WebCrawler(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One crawl round. [round] rotates the pages and queries so repeats reach new ground. */
    suspend fun crawl(round: Int, budgetMs: Long = BUDGET_MS): List<FinderCandidate> = coroutineScope {
        // Every stage gets a share of the caller's budget, so a crawl started
        // inside a 30 s discovery window still returns what it found instead
        // of being cancelled with nothing to show.
        val seedBudget = (budgetMs * 4 / 10).coerceAtLeast(3_000L)
        val followBudget = (budgetMs * 2 / 10).coerceAtLeast(2_000L)
        val resolveBudget = (budgetMs * 3 / 10).coerceAtLeast(3_000L)

        val seeds = async { withTimeoutOrNull(seedBudget) { seedPages(round) }.orEmpty() }
        val repos = async { withTimeoutOrNull(seedBudget) { github(round) }.orEmpty() }

        val pages = seeds.await()
        val followed = async { withTimeoutOrNull(followBudget) { follow(pages) }.orEmpty() }

        val texts = (pages.map { it.text } + followed.await()).filter { it.isNotBlank() }
        val fromText = texts.flatMap { FinderRules.extractManifestUrls(it) }
        val fromBases = texts.flatMap { addonBases(it) }.map { "$it/manifest.json" }

        val urls = (fromText + fromBases).map { FinderRules.publicHttpsUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_MANIFESTS)

        val fromGitHub = withTimeoutOrNull(resolveBudget / 2) { repos.await() }.orEmpty()
        val resolved = withTimeoutOrNull(resolveBudget) { resolve(urls, emptyList()) }.orEmpty()
        (resolved + fromGitHub).distinctBy { it.baseUrl.lowercase() }
    }

    // -- seeds -------------------------------------------------------------

    private class Page(val url: String, val text: String)

    /** Pages that are likely to list addons, fetched in parallel. */
    private suspend fun seedPages(round: Int): List<Page> = coroutineScope {
        val urls = (LIST_PAGES + searchPages(round)).distinct()
        urls.map { url ->
            async {
                runCatching {
                    Page(url, http.get(url, headers = BROWSER_HEADERS, attempts = 1, limitBytes = MAX_PAGE_BYTES))
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Web-search result pages. Both endpoints are keyless and independently
     * optional, so one engine being blocked cannot make the addon pool empty.
     */
    private fun searchPages(round: Int): List<String> {
        val queries = SEARCH_QUERIES
        val picked = (0 until SEARCH_PER_ROUND).map { queries[(round * SEARCH_PER_ROUND + it) % queries.size] }
        return picked.flatMap { q ->
            val encoded = URLEncoder.encode(q, "UTF-8")
            listOf(
                "https://html.duckduckgo.com/html/?q=$encoded",
                "https://yandex.com/search/?text=$encoded"
            )
        }
    }

    /** Follow addon-looking links one hop out of the seed pages. */
    private suspend fun follow(pages: List<Page>): List<String> = coroutineScope {
        val links = LinkedHashSet<String>()
        for (page in pages) {
            for (href in HREF.findAll(page.text).map { it.groupValues[1] }) {
                val absolute = absolutise(page.url, href) ?: continue
                if (!looksAddonish(absolute)) continue
                links += absolute
                if (links.size >= MAX_FOLLOW) break
            }
            if (links.size >= MAX_FOLLOW) break
        }
        links.map { url ->
            async {
                runCatching {
                    http.get(url, headers = BROWSER_HEADERS, attempts = 1, limitBytes = MAX_PAGE_BYTES)
                }.getOrDefault("")
            }
        }.awaitAll().filter { it.isNotBlank() }
    }

    private fun absolutise(base: String, href: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("javascript:") || h.startsWith("mailto:")) return null
        val resolved = when {
            h.startsWith("http://", true) || h.startsWith("https://", true) -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> {
                val scheme = base.substringBefore("://")
                val host = base.substringAfter("://").substringBefore('/')
                "$scheme://$host$h"
            }
            else -> base.substringBeforeLast('/') + "/" + h
        }
        return FinderRules.publicHttpsUrl(resolved)
    }

    private fun looksAddonish(url: String): Boolean {
        val lower = url.lowercase()
        return ADDON_HINTS.any { it in lower }
    }

    /**
     * Base URLs in free text that are plausibly an addon root. A manifest is
     * usually at `base/manifest.json`, so these are probed rather than trusted.
     */
    private fun addonBases(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (match in URL_PATTERN.findAll(text)) {
            val raw = match.value.trimEnd('.', ',', ';', ')', ']', '"', '\'')
            val safe = FinderRules.publicHttpsUrl(raw)
            if (safe.isEmpty()) continue
            if (safe.contains("manifest.json", true)) continue
            if (!looksAddonish(safe)) continue
            out += safe.trimEnd('/')
            if (out.size >= MAX_BASES) break
        }
        return out.toList()
    }

    // -- GitHub ------------------------------------------------------------

    private suspend fun github(round: Int): List<FinderCandidate> = coroutineScope {
        val page = round % GITHUB_PAGES + 1
        val repos = GITHUB_QUERIES.map { q ->
            async {
                runCatching {
                    val query = URLEncoder.encode(q, "UTF-8")
                    val data = fetchJson(
                        "https://api.github.com/search/repositories?q=$query&sort=updated&order=desc&per_page=100&page=$page"
                    ) as? JsonObject
                    (data?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.str("full_name") }

        val gate = Semaphore(CONCURRENCY)
        var readmes = 0
        val readmeLock = Any()

        val urls = repos.map { repo ->
            async {
                gate.withPermit {
                    val inline = FinderRules.extractManifestUrls("${repo.str("description")} ${repo.str("homepage")}")
                    if (inline.isNotEmpty()) return@withPermit inline.map { it to repo }
                    val take = synchronized(readmeLock) { if (readmes < MAX_READMES) { readmes++; true } else false }
                    val full = repo.str("full_name")
                    if (!take || !SLUG.matches(full)) return@withPermit emptyList()
                    val branch = repo.str("default_branch").ifBlank { "main" }
                        .replace(Regex("[^A-Za-z0-9_.-]"), "").take(80).ifBlank { "main" }
                    runCatching {
                        val readme = http.get(
                            "https://raw.githubusercontent.com/$full/$branch/README.md",
                            attempts = 1, limitBytes = FinderRules.MAX_JSON_BYTES
                        )
                        FinderRules.extractManifestUrls(readme).map { it to repo }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().distinctBy { it.first }.take(MAX_GITHUB_MANIFESTS)

        urls.map { (url, repo) ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                        runCatching {
                            val manifest = fetchJson(url, FinderRules.MAX_MANIFEST_BYTES)
                            val meta = JsonObject(mapOf("stars" to (repo["stargazers_count"] ?: JsonPrimitive(0))))
                            SourceFinder.candidateFor(url, manifest, "web", meta)
                                ?.copy(from = "Web crawl \u00b7 GitHub")
                        }.getOrNull()
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    // -- manifest resolution ----------------------------------------------

    /** Fetch each manifest; keep the ones that are a real stream addon. */
    private suspend fun resolve(urls: List<String>, already: List<FinderCandidate>): List<FinderCandidate> = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        val found = urls.map { url ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                        runCatching {
                            val manifest = fetchJson(url, FinderRules.MAX_MANIFEST_BYTES)
                            SourceFinder.candidateFor(url, manifest, "web", JsonObject(emptyMap()))
                                ?.copy(from = "Web crawl")
                        }.getOrNull()
                    }
                }
            }
        }.awaitAll().filterNotNull()
        (found + already).distinctBy { it.baseUrl.lowercase() }
    }

    /** Public so the pool harvester can reuse the guarded fetch. */
    suspend fun json(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement = fetchJson(url, maxBytes)

    private suspend fun fetchJson(url: String, maxBytes: Long = FinderRules.MAX_JSON_BYTES): JsonElement {
        val safe = FinderRules.publicHttpsUrl(url)
        require(safe.isNotEmpty()) { "Blocked URL" }
        return json.parseToJsonElement(
            http.get(safe, headers = ACCEPT_JSON, attempts = 1, limitBytes = maxBytes)
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        /** Requests in flight at once. The old finder used 6-8. */
        const val CONCURRENCY = 32

        private const val BUDGET_MS = 40_000L
        private const val MANIFEST_TIMEOUT_MS = 5_000L
        private const val MAX_PAGE_BYTES = 2_000_000L
        private const val MAX_FOLLOW = 160
        private const val MAX_BASES = 300
        private const val MAX_MANIFESTS = 1200
        private const val MAX_READMES = 200
        private const val MAX_GITHUB_MANIFESTS = 600
        private const val GITHUB_PAGES = 20
        private const val SEARCH_PER_ROUND = 8

        private val SLUG = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val HREF = Regex("""href\s*=\s*["']([^"']{3,400})["']""", RegexOption.IGNORE_CASE)
        private val URL_PATTERN = Regex("""https?://[^\s"'<>()\[\]{}]+""", RegexOption.IGNORE_CASE)

        private val ADDON_HINTS = listOf(
            "addon", "stremio", "manifest", "plugin", "extension", "catalog", "catalogue",
            "media-server", "mediaserver", "jellyfin", "emby", "kodi", "xbmc", "stream"
        )

        /**
         * Public lists that enumerate addons. Each is plain text or HTML, so a
         * single GET is enough; failures are ignored.
         */
        private val LIST_PAGES = listOf(
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/main/README.md",
            "https://raw.githubusercontent.com/Stremio/stremio-addons-list/master/README.md",
            "https://raw.githubusercontent.com/varun-raj/awesome-stremio-addons/main/README.md",
            "https://raw.githubusercontent.com/itsmepvr/awesome-stremio/master/README.md",
            "https://raw.githubusercontent.com/mrcanelas/tmdb-addon/main/README.md",
            "https://raw.githubusercontent.com/Stremio/addon-helloworld/master/README.md",
            "https://api.github.com/search/repositories?q=topic%3Astremio-addon&sort=stars&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=stremio+addon+in%3Aname%2Cdescription&sort=stars&order=desc&per_page=100",
            "https://api.github.com/search/repositories?q=manifest.json+stream+addon&sort=stars&order=desc&per_page=100"
        )

        private val SEARCH_QUERIES = listOf(
            "stremio addon manifest.json list",
            "stremio addon catalog github",
            "addon manifest.json stream 1080p",
            "self hosted media catalog addon manifest.json",
            "kodi addon repository manifest.json stream",
            "jellyfin plugin catalog repository",
            "awesome stremio addons list",
            "stremio addon community list manifest",
            "streaming addon manifest.json github pages",
            "debrid addon manifest.json"
        )

        private val GITHUB_QUERIES = listOf(
            "topic:stremio-addon",
            "stremio addon",
            "stremio-addon in:name",
            "manifest.json stremio",
            "addon manifest stream",
            "media catalog addon",
            "streaming addon server"
        )

        private val ACCEPT_JSON = mapOf(
            "Accept" to "application/json,text/plain;q=0.9,*/*;q=0.8",
            "User-Agent" to "NovaTV-WebCrawler/2.0"
        )

        private val BROWSER_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
    }
}

// build retrigger 2
