package dev.nova.tv.finder.pipeline

import java.net.URLEncoder
import java.net.URLDecoder

/**
 * Sixty-five configured discovery engines across eleven public endpoint
 * families. They are independent crawler configurations, not sixty-five
 * third-party services. There is no curated streaming-site list: each engine
 * asks a public index a topic question. No engine bypasses authentication,
 * CAPTCHAs, paywalls, DRM or private access controls.
 */
enum class EngineParser { URL_LINES, JSON_URLS, HTML_LINKS, HOST_LINES, MANIFEST_JSON }

data class EngineRequest(val url: String, val engineId: String)

data class EngineParseResult(
    val engineId: String,
    val candidates: List<CandidateSite>,
    val error: String = ""
)

data class EngineSpec(
    val id: String,
    val kind: PipelineKind,
    val family: String,
    val topic: String,
    val urlTemplate: String,
    val parser: EngineParser,
    val maxPages: Int = 20,
    val pageSize: Int = 100,
    val requiresQuery: Boolean = true,
    val accessControlBypass: Boolean = false
) {
    val host: String = PipelineText.hostOf(urlTemplate)
    val pathPrefix: String = PipelineText.pathPrefixOf(urlTemplate)

    fun request(round: Int, page: Int): EngineRequest {
        val modifier = EngineRegistry.QUERY_MODIFIERS[Math.floorMod(round, EngineRegistry.QUERY_MODIFIERS.size)]
        val query = listOf(topic, modifier).filter { it.isNotBlank() }.joinToString(" ")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val offset = page * pageSize
        val url = urlTemplate
            .replace("{query}", encoded)
            .replace("{page}", (page + 1).toString())
            .replace("{offset}", offset.toString())
        return EngineRequest(url, id)
    }

    fun matches(url: String): Boolean {
        val candidateHost = PipelineText.hostOf(url)
        if (candidateHost != host) return false
        return PipelineText.pathOf(url).startsWith(pathPrefix)
    }

    fun parse(body: String): List<CandidateSite> =
        EngineParsers.parse(this, body)
}

private object EngineParsers {
    private val URL = Regex("""https?://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"'>]+)["']""", RegexOption.IGNORE_CASE)
    private val HOST = Regex("""\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}\b""", RegexOption.IGNORE_CASE)
    private val MANIFEST = Regex("""https?://[^\s"'<>)]+manifest\.json""", RegexOption.IGNORE_CASE)

    fun parse(spec: EngineSpec, body: String): List<CandidateSite> {
        val cleaned = body.replace("\\/", "/")
        val base = PipelineText.originOf(spec.urlTemplate)
        val urls = LinkedHashSet<String>()
        when (spec.parser) {
            EngineParser.URL_LINES -> {
                for (match in URL.findAll(cleaned)) urls += cleanUrl(match.value)
            }
            EngineParser.JSON_URLS -> {
                for (match in URL.findAll(cleaned)) urls += cleanUrl(match.value)
            }
            EngineParser.HTML_LINKS -> {
                for (match in HREF.findAll(cleaned)) {
                    val raw = match.groupValues[1].trim()
                    if (raw.startsWith("javascript:") || raw.startsWith("#") || raw.startsWith("mailto:")) continue
                    val absolute = when {
                        raw.startsWith("http://") || raw.startsWith("https://") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> "$base$raw"
                        else -> "$base/$raw"
                    }
                    urls += cleanUrl(absolute)
                }
                for (match in URL.findAll(cleaned)) urls += cleanUrl(match.value)
            }
            EngineParser.HOST_LINES -> {
                for (match in HOST.findAll(cleaned)) urls += "https://${match.value.lowercase()}/"
            }
            EngineParser.MANIFEST_JSON -> {
                for (match in MANIFEST.findAll(cleaned)) urls += cleanUrl(match.value)
            }
        }
        return urls
            .filter { it.startsWith("http") && PipelineText.hostOf(it).isNotEmpty() }
            .map {
                CandidateSite(
                    url = it,
                    host = PipelineText.hostOf(it),
                    engineId = spec.id,
                    kind = spec.kind,
                    topic = spec.topic,
                    provenance = spec.family
                )
            }
            .toList()
    }

    private val redirectKeys = setOf("uddg", "u", "url", "r", "target", "redirect")

    /** Search engines often wrap the result URL in a redirect parameter.
     * Without this, DuckDuckGo/Bing results are sorted as the search host and
     * discarded, so the crawl finds the index instead of the site it indexed. */
    private fun unwrapRedirect(value: String): String {
        val query = value.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return value
        for (part in query.split('&')) {
            val key = part.substringBefore('=').lowercase()
            val encoded = part.substringAfter('=', "")
            if (key !in redirectKeys || encoded.isEmpty()) continue
            val decoded = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (error: Exception) {
                encoded
            }
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded.trim().trimEnd(',', ';', ')', ']', '}', '"', '\'')
            }
        }
        return value
    }

    private fun cleanUrl(raw: String): String {
        val trimmed = raw.trim()
            .trimEnd(',', ';', ')', ']', '}', '"', '\'')
            .replace("\\/", "/")
        return unwrapRedirect(trimmed)
    }
}

object EngineRegistry {
    val QUERY_MODIFIERS = listOf(
        "", "catalogue", "server", "working", "public", "episode", "manifest", "index"
    )

    val ALL: List<EngineSpec> = listOf(
        EngineSpec(
            id = "web-github-repo-01",
            kind = PipelineKind.WEBSITE,
            family = "github-repository",
            topic = "movie streaming catalogue",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-github-repo-02",
            kind = PipelineKind.WEBSITE,
            family = "github-repository",
            topic = "anime streaming frontend",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-github-repo-03",
            kind = PipelineKind.WEBSITE,
            family = "github-repository",
            topic = "series streaming website",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-github-repo-04",
            kind = PipelineKind.WEBSITE,
            family = "github-repository",
            topic = "watch episode catalogue",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-github-repo-05",
            kind = PipelineKind.WEBSITE,
            family = "github-repository",
            topic = "public video catalogue",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-hackernews-01",
            kind = PipelineKind.WEBSITE,
            family = "hn-algolia",
            topic = "movie streaming catalogue",
            urlTemplate = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-hackernews-02",
            kind = PipelineKind.WEBSITE,
            family = "hn-algolia",
            topic = "anime streaming frontend",
            urlTemplate = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-hackernews-03",
            kind = PipelineKind.WEBSITE,
            family = "hn-algolia",
            topic = "series streaming website",
            urlTemplate = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-hackernews-04",
            kind = PipelineKind.WEBSITE,
            family = "hn-algolia",
            topic = "watch episode catalogue",
            urlTemplate = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-hackernews-05",
            kind = PipelineKind.WEBSITE,
            family = "hn-algolia",
            topic = "public video catalogue",
            urlTemplate = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-crtsh-01",
            kind = PipelineKind.WEBSITE,
            family = "crtsh",
            topic = "anime",
            urlTemplate = "https://crt.sh/?q={query}&output=json",
            parser = EngineParser.HOST_LINES,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-crtsh-02",
            kind = PipelineKind.WEBSITE,
            family = "crtsh",
            topic = "movie",
            urlTemplate = "https://crt.sh/?q={query}&output=json",
            parser = EngineParser.HOST_LINES,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-crtsh-03",
            kind = PipelineKind.WEBSITE,
            family = "crtsh",
            topic = "series",
            urlTemplate = "https://crt.sh/?q={query}&output=json",
            parser = EngineParser.HOST_LINES,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-crtsh-04",
            kind = PipelineKind.WEBSITE,
            family = "crtsh",
            topic = "watch",
            urlTemplate = "https://crt.sh/?q={query}&output=json",
            parser = EngineParser.HOST_LINES,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-crtsh-05",
            kind = PipelineKind.WEBSITE,
            family = "crtsh",
            topic = "stream",
            urlTemplate = "https://crt.sh/?q={query}&output=json",
            parser = EngineParser.HOST_LINES,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-urlscan-01",
            kind = PipelineKind.WEBSITE,
            family = "urlscan",
            topic = "movie streaming catalogue",
            urlTemplate = "https://urlscan.io/api/v1/search/?q={query}&size=100&offset={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-urlscan-02",
            kind = PipelineKind.WEBSITE,
            family = "urlscan",
            topic = "anime streaming frontend",
            urlTemplate = "https://urlscan.io/api/v1/search/?q={query}&size=100&offset={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-urlscan-03",
            kind = PipelineKind.WEBSITE,
            family = "urlscan",
            topic = "series streaming website",
            urlTemplate = "https://urlscan.io/api/v1/search/?q={query}&size=100&offset={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-urlscan-04",
            kind = PipelineKind.WEBSITE,
            family = "urlscan",
            topic = "watch episode catalogue",
            urlTemplate = "https://urlscan.io/api/v1/search/?q={query}&size=100&offset={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-urlscan-05",
            kind = PipelineKind.WEBSITE,
            family = "urlscan",
            topic = "public video catalogue",
            urlTemplate = "https://urlscan.io/api/v1/search/?q={query}&size=100&offset={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-wayback-01",
            kind = PipelineKind.WEBSITE,
            family = "wayback-cdx",
            topic = "anime",
            urlTemplate = "https://web.archive.org/cdx/search/cdx?url={query}&matchType=domain&output=json&fl=original&collapse=urlkey&limit=1000&filter=statuscode:200",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-wayback-02",
            kind = PipelineKind.WEBSITE,
            family = "wayback-cdx",
            topic = "movie",
            urlTemplate = "https://web.archive.org/cdx/search/cdx?url={query}&matchType=domain&output=json&fl=original&collapse=urlkey&limit=1000&filter=statuscode:200",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-wayback-03",
            kind = PipelineKind.WEBSITE,
            family = "wayback-cdx",
            topic = "series",
            urlTemplate = "https://web.archive.org/cdx/search/cdx?url={query}&matchType=domain&output=json&fl=original&collapse=urlkey&limit=1000&filter=statuscode:200",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-wayback-04",
            kind = PipelineKind.WEBSITE,
            family = "wayback-cdx",
            topic = "watch",
            urlTemplate = "https://web.archive.org/cdx/search/cdx?url={query}&matchType=domain&output=json&fl=original&collapse=urlkey&limit=1000&filter=statuscode:200",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-wayback-05",
            kind = PipelineKind.WEBSITE,
            family = "wayback-cdx",
            topic = "stream",
            urlTemplate = "https://web.archive.org/cdx/search/cdx?url={query}&matchType=domain&output=json&fl=original&collapse=urlkey&limit=1000&filter=statuscode:200",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-bing-rss-01",
            kind = PipelineKind.WEBSITE,
            family = "bing-rss",
            topic = "movie streaming catalogue",
            urlTemplate = "https://www.bing.com/search?q={query}&format=rss&first={offset}",
            parser = EngineParser.URL_LINES,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-bing-rss-02",
            kind = PipelineKind.WEBSITE,
            family = "bing-rss",
            topic = "anime streaming frontend",
            urlTemplate = "https://www.bing.com/search?q={query}&format=rss&first={offset}",
            parser = EngineParser.URL_LINES,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-bing-rss-03",
            kind = PipelineKind.WEBSITE,
            family = "bing-rss",
            topic = "series streaming website",
            urlTemplate = "https://www.bing.com/search?q={query}&format=rss&first={offset}",
            parser = EngineParser.URL_LINES,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-bing-rss-04",
            kind = PipelineKind.WEBSITE,
            family = "bing-rss",
            topic = "watch episode catalogue",
            urlTemplate = "https://www.bing.com/search?q={query}&format=rss&first={offset}",
            parser = EngineParser.URL_LINES,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-bing-rss-05",
            kind = PipelineKind.WEBSITE,
            family = "bing-rss",
            topic = "public video catalogue",
            urlTemplate = "https://www.bing.com/search?q={query}&format=rss&first={offset}",
            parser = EngineParser.URL_LINES,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-duckduckgo-01",
            kind = PipelineKind.WEBSITE,
            family = "duckduckgo-html",
            topic = "movie streaming catalogue",
            urlTemplate = "https://html.duckduckgo.com/html/?q={query}&s={offset}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-duckduckgo-02",
            kind = PipelineKind.WEBSITE,
            family = "duckduckgo-html",
            topic = "anime streaming frontend",
            urlTemplate = "https://html.duckduckgo.com/html/?q={query}&s={offset}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-duckduckgo-03",
            kind = PipelineKind.WEBSITE,
            family = "duckduckgo-html",
            topic = "series streaming website",
            urlTemplate = "https://html.duckduckgo.com/html/?q={query}&s={offset}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-duckduckgo-04",
            kind = PipelineKind.WEBSITE,
            family = "duckduckgo-html",
            topic = "watch episode catalogue",
            urlTemplate = "https://html.duckduckgo.com/html/?q={query}&s={offset}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "web-duckduckgo-05",
            kind = PipelineKind.WEBSITE,
            family = "duckduckgo-html",
            topic = "public video catalogue",
            urlTemplate = "https://html.duckduckgo.com/html/?q={query}&s={offset}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-01",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio addon manifest",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-02",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio catalog addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-03",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio stream addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-04",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio anime addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-05",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio subtitles addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-06",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio metadata addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-07",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio community addons",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-08",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio addon directory",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-09",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio self hosted addon",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-repo-10",
            kind = PipelineKind.ADDON,
            family = "github-repository",
            topic = "stremio addon github",
            urlTemplate = "https://api.github.com/search/repositories?q={query}&sort=updated&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-issues-01",
            kind = PipelineKind.ADDON,
            family = "github-issues",
            topic = "v3-cinemeta.strem.io",
            urlTemplate = "https://api.github.com/search/issues?q={query}+is%3Aissue&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-issues-02",
            kind = PipelineKind.ADDON,
            family = "github-issues",
            topic = "watchhub.strem.io",
            urlTemplate = "https://api.github.com/search/issues?q={query}+is%3Aissue&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-issues-03",
            kind = PipelineKind.ADDON,
            family = "github-issues",
            topic = "yastream.tamthai.de",
            urlTemplate = "https://api.github.com/search/issues?q={query}+is%3Aissue&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-issues-04",
            kind = PipelineKind.ADDON,
            family = "github-issues",
            topic = "movie-addon.vercel.app",
            urlTemplate = "https://api.github.com/search/issues?q={query}+is%3Aissue&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-github-issues-05",
            kind = PipelineKind.ADDON,
            family = "github-issues",
            topic = "mediafusion.elfhosted.com",
            urlTemplate = "https://api.github.com/search/issues?q={query}+is%3Aissue&per_page=100&page={page}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-npm-01",
            kind = PipelineKind.ADDON,
            family = "npm-registry",
            topic = "stremio addon",
            urlTemplate = "https://registry.npmjs.org/-/v1/search?text={query}&size=250&from={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 250,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-npm-02",
            kind = PipelineKind.ADDON,
            family = "npm-registry",
            topic = "stremio catalog",
            urlTemplate = "https://registry.npmjs.org/-/v1/search?text={query}&size=250&from={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 250,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-npm-03",
            kind = PipelineKind.ADDON,
            family = "npm-registry",
            topic = "stremio stream",
            urlTemplate = "https://registry.npmjs.org/-/v1/search?text={query}&size=250&from={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 250,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-npm-04",
            kind = PipelineKind.ADDON,
            family = "npm-registry",
            topic = "stremio manifest",
            urlTemplate = "https://registry.npmjs.org/-/v1/search?text={query}&size=250&from={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 250,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-npm-05",
            kind = PipelineKind.ADDON,
            family = "npm-registry",
            topic = "stremio provider",
            urlTemplate = "https://registry.npmjs.org/-/v1/search?text={query}&size=250&from={offset}",
            parser = EngineParser.JSON_URLS,
            maxPages = 20,
            pageSize = 250,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-pypi-01",
            kind = PipelineKind.ADDON,
            family = "pypi-search",
            topic = "stremio addon",
            urlTemplate = "https://pypi.org/search/?q={query}&page={page}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-pypi-02",
            kind = PipelineKind.ADDON,
            family = "pypi-search",
            topic = "stremio catalog",
            urlTemplate = "https://pypi.org/search/?q={query}&page={page}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-pypi-03",
            kind = PipelineKind.ADDON,
            family = "pypi-search",
            topic = "stremio stream",
            urlTemplate = "https://pypi.org/search/?q={query}&page={page}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-pypi-04",
            kind = PipelineKind.ADDON,
            family = "pypi-search",
            topic = "stremio manifest",
            urlTemplate = "https://pypi.org/search/?q={query}&page={page}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-pypi-05",
            kind = PipelineKind.ADDON,
            family = "pypi-search",
            topic = "cinema metadata",
            urlTemplate = "https://pypi.org/search/?q={query}&page={page}",
            parser = EngineParser.HTML_LINKS,
            maxPages = 20,
            pageSize = 100,
            requiresQuery = true
        ),
        EngineSpec(
            id = "addon-reddit-01",
            kind = PipelineKind.ADDON,
            family = "reddit-listing",
            topic = "r/StremioAddons",
            urlTemplate = "https://www.reddit.com/r/StremioAddons/new.json?limit=100",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = false
        ),
        EngineSpec(
            id = "addon-reddit-02",
            kind = PipelineKind.ADDON,
            family = "reddit-listing",
            topic = "r/Stremio",
            urlTemplate = "https://www.reddit.com/r/Stremio/new.json?limit=100",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = false
        ),
        EngineSpec(
            id = "addon-reddit-03",
            kind = PipelineKind.ADDON,
            family = "reddit-listing",
            topic = "r/Addons4Kodi",
            urlTemplate = "https://www.reddit.com/r/Addons4Kodi/new.json?limit=100",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = false
        ),
        EngineSpec(
            id = "addon-reddit-04",
            kind = PipelineKind.ADDON,
            family = "reddit-listing",
            topic = "r/cordcutters",
            urlTemplate = "https://www.reddit.com/r/cordcutters/new.json?limit=100",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = false
        ),
        EngineSpec(
            id = "addon-reddit-05",
            kind = PipelineKind.ADDON,
            family = "reddit-listing",
            topic = "r/selfhosted",
            urlTemplate = "https://www.reddit.com/r/selfhosted/new.json?limit=100",
            parser = EngineParser.JSON_URLS,
            maxPages = 1,
            pageSize = 100,
            requiresQuery = false
        )
    )

    val BY_KIND: Map<PipelineKind, List<EngineSpec>> = ALL.groupBy { it.kind }

    init {
        check(ALL.size >= MIN_ENGINE_COUNT) { "engine registry has fewer than $MIN_ENGINE_COUNT engines" }
        check(ALL.size == ALL.distinctBy { it.id }.size) { "engine ids are not distinct" }
        check(PipelineStage.values().size >= MIN_STAGE_COUNT) { "pipeline stage count regressed" }
        check(ALL.all { it.urlTemplate.startsWith("https://") }) { "every engine must use HTTPS" }
        check(ALL.all { !it.requiresQuery || it.urlTemplate.contains("{query}") }) {
            "a query engine lost its query placeholder"
        }
        check(ALL.all { it.maxPages >= 1 && it.pageSize > 0 }) { "engine pagination is invalid" }
        check(ALL.none { it.accessControlBypass }) { "access-control bypass is not permitted" }
    }

    fun forKind(kind: PipelineKind): List<EngineSpec> = BY_KIND[kind].orEmpty()

    fun surfaceUrls(kind: PipelineKind, round: Int, pages: Int = 1): List<String> {
        val out = ArrayList<String>()
        for (engine in forKind(kind)) {
            val bounded = minOf(pages.coerceAtLeast(1), engine.maxPages)
            for (page in 0 until bounded) out += engine.request(round, page).url
        }
        return out.distinct().toList()
    }

    fun find(url: String, kind: PipelineKind? = null): EngineSpec? {
        for (engine in ALL) {
            if (kind != null && engine.kind != kind) continue
            if (engine.matches(url)) return engine
        }
        return null
    }

    /** Several endpoint families are shared by both pipelines; the runner must
     * state which kind it is fetching so an addon response is not parsed as a
     * website candidate simply because both use api.github.com. */
    fun parse(url: String, body: String, kind: PipelineKind? = null): EngineParseResult {
        val engine = find(url, kind)
            ?: return EngineParseResult("", emptyList(), "no engine owns $url")
        return EngineParseResult(engine.id, engine.parse(body))
    }

    fun isEngineUrl(url: String): Boolean = find(url) != null

    fun isSurface(url: String): Boolean = isEngineUrl(url)

    fun isJsonSurface(url: String): Boolean {
        val engine = find(url) ?: return false
        return engine.parser != EngineParser.HTML_LINKS
    }

    fun isSearchHost(host: String): Boolean {
        val clean = host.lowercase().removePrefix("www.")
        return ALL.any {
            it.host == clean && (it.family == "bing-rss" || it.family == "duckduckgo-html")
        }
    }
}
