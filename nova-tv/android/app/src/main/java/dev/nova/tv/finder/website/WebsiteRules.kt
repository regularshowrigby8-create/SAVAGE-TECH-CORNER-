package dev.nova.tv.finder.website

import dev.nova.tv.data.remote.HttpClient
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

/**
 * The ZeroPlay website features, ported from `tv/zeroplay-debrid/api/extract.js`.
 *
 * ZeroPlay ran these on a serverless function; Nova runs them on the device.
 * Everything here is pure string work -- no network -- so each rule is a JVM
 * unit test (WebsiteRulesTest), and the network half lives in [WebsiteFinder].
 *
 * Function names match the JS so the two can be compared side by side:
 * guardUrl, parseWatch, m3uPlaylistInfo, normalizeTitle, titleScore,
 * identifyTitleCandidate, explicitEpisodeInfo, parseResultLinks,
 * searchTemplatesFromHtml, commonSearchTemplates, buildSearchUrl,
 * adjustEpisodeInUrl, episodeCandidateUrls, findEpisodeUrl.
 */
object WebsiteRules {

    const val SEARCH_PLACEHOLDER = "__ZP_Q__"
    const val MAX_BYTES = 1_500_000L
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val I = RegexOption.IGNORE_CASE
    private val BLOCKED_PATH =
        Regex("""/(login|register|signup|filter|search|genre|genres|tag|tags|category|categories|type|types|year|years|status|user|profile|dmca|terms|privacy|contact|about|home)(/|\?|$)""", I)
    private val NOISE = Regex("""facebook|twitter|sharethis|googletagmanager|google\.com/recaptcha|disqus|platform-cdn""", I)
    private val MEDIA = Regex("""\.(?:m3u8|mp4|mpd|webm)(?:\?|#|$)""", I)
    private val EP_IN_URL = Regex("""(?:/(?:ep|episode)[-_/]\d+|-ep(?:isode)?-\d+|[?&](?:ep|episode|episode_id)=\d+)""", I)

    // -- url helpers ---------------------------------------------------------

    /** extract.js guardUrl + Nova's private-address guard. Null when refused. */
    fun guardUrl(raw: String?): URL? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val url = runCatching { URL(text) }.getOrNull() ?: return null
        if (url.protocol != "https" && url.protocol != "http") return null
        if (!url.host.contains('.')) return null
        if (!HttpClient.isAllowedUrl(text)) return null
        return url
    }

    fun abs(href: String, base: String): String =
        runCatching { URL(URL(base), href.trim()).toString() }.getOrDefault("")

    private fun canonicalHost(host: String) = host.lowercase().removePrefix("www.")

    fun sameSite(a: String, b: String): Boolean = runCatching {
        canonicalHost(URL(a).host) == canonicalHost(URL(b).host)
    }.getOrDefault(false)

    fun stripTags(s: String): String = s
        .replace(Regex("""<script[\s\S]*?</script>""", I), " ")
        .replace(Regex("""<style[\s\S]*?</style>""", I), " ")
        .replace(Regex("""<[^>]*>"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    fun decodeEntities(s: String): String = s
        .replace(Regex("""&#x([0-9a-f]+);""", I)) { m -> m.groupValues[1].toIntOrNull(16)?.let { cp(it) } ?: "" }
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { cp(it) } ?: "" }
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun cp(code: Int): String = runCatching { String(Character.toChars(code)) }.getOrDefault("")

    fun attr(tag: String, name: String): String {
        val m = Regex("""$name\s*=\s*"([^"]*)"""", I).find(tag) ?: Regex("""$name\s*=\s*'([^']*)'""", I).find(tag)
        return m?.let { decodeEntities(it.groupValues[1]) }.orEmpty()
    }

    fun pageTitle(html: String): String =
        Regex("""<title[^>]*>([^<]{1,220})</title>""", I).find(html)
            ?.let { decodeEntities(stripTags(it.groupValues[1])).take(160) }.orEmpty()

    // -- watch pages ---------------------------------------------------------

    data class Watch(
        val title: String,
        val embeds: List<String>,
        val direct: List<String>,
        val subtitles: List<String>,
        val servers: List<String>
    ) {
        val embed: String get() = embeds.firstOrNull().orEmpty()
    }

    fun embedScore(url: String): Int {
        var score = 0
        if (Regex("embed|player|stream|video|play|watch", I).containsMatchIn(url)) score += 30
        if (Regex("""\.(m3u8|mp4|mpd)(\?|$)""", I).containsMatchIn(url)) score -= 10
        if (Regex("vid|stream|play|tube|cloud|hide|wish|tape|moly", I).containsMatchIn(url)) score += 10
        return score
    }

    /** extract.js parseWatch: the embeds, direct media and subtitles a page carries. */
    fun parseWatch(html: String, base: String): Watch {
        val embeds = mutableListOf<String>()
        fun addEmbed(raw: String, guarded: Boolean) {
            var src = decodeEntities(raw).replace("\\/", "/").replace(Regex("""[),.;]+$"""), "")
            if (src.isEmpty() || src.equals("about:blank", true)) return
            if (src.startsWith("//")) src = "https:$src"
            src = abs(src, base)
            if (src.isEmpty() || (guarded && guardUrl(src) == null)) return
            if (NOISE.containsMatchIn(src)) return
            if (Regex("""\.(?:js|css|png|jpe?g|gif|webp|svg|ico|woff2?)(?:\?|#|$)""", I).containsMatchIn(src)) return
            if (src !in embeds) embeds += src
        }
        for (m in Regex("""<iframe\b[^>]*>""", I).findAll(html)) {
            val src = attr(m.value, "src").ifEmpty { attr(m.value, "data-src") }.ifEmpty { attr(m.value, "data-lazy-src") }
            if (src.isNotEmpty()) addEmbed(src, guarded = false)
        }
        for (m in Regex("""\bdata-(?:src|url|embed|video|link|player)\s*=\s*["']([^"']+)["']""", I).findAll(html)) {
            val candidate = abs(decodeEntities(m.groupValues[1]), base)
            if (candidate.isNotEmpty() && (embedScore(candidate) >= 30 || MEDIA.containsMatchIn(candidate))) addEmbed(candidate, true)
        }
        val normalized = html.replace("\\/", "/").replace(Regex("""\\u0026""", I), "&").replace("&amp;", "&")
        for (m in Regex("""https?://[^\s"'<>]+""", I).findAll(normalized)) {
            val c = m.value
            if (Regex("""/(?:embed|player)(?:/|\?|#|[-_])""", I).containsMatchIn(c) ||
                Regex("""[?&](?:embed|player|video)=""", I).containsMatchIn(c)
            ) addEmbed(c, true)
        }
        val sorted = embeds.filter { guardUrl(it) != null }.sortedByDescending { embedScore(it) }

        val direct = LinkedHashSet<String>()
        for (m in Regex("""<(?:video|source)\b[^>]*>""", I).findAll(html)) {
            val src = abs(attr(m.value, "src"), base)
            if (src.isNotEmpty() && MEDIA.containsMatchIn(src)) direct += src
        }
        for (m in Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>]*)?""", I).findAll(normalized)) {
            direct += m.value
            if (direct.size >= 5) break
        }
        return Watch(
            title = pageTitle(html),
            embeds = sorted.take(4),
            direct = direct.filter { guardUrl(it) != null }.take(5),
            subtitles = subtitleTracks(html, base),
            servers = serverNames(html)
        )
    }

    /** Timed-text files only: `<track>` tags and .vtt/.srt strings in player config. */
    fun subtitleTracks(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val pattern = Regex("""\.(?:vtt|srt|ass|ssa)(?:\?|#|$)""", I)
        fun push(src: String) {
            val url = abs(decodeEntities(src).replace("\\/", "/"), base)
            if (url.isNotEmpty() && pattern.containsMatchIn(url) && guardUrl(url) != null) out += url
        }
        Regex("""<track\b[^>]*>""", I).findAll(html).forEach { push(attr(it.value, "src")) }
        for (m in Regex("""["']([^"']+?\.(?:vtt|srt|ass|ssa)(?:\?[^"']*)?)["']""", I).findAll(html)) {
            push(m.groupValues[1]); if (out.size >= 12) break
        }
        return out.take(12)
    }

    fun serverNames(html: String): List<String> {
        val names = LinkedHashSet<String>()
        val looks = Regex("""(?:^|[\s_-])(?:server|servers|server[-_](?:item|btn|button|name|link|tab|option)|btn[-_]server|sv[-_]item)(?:$|[\s_-])""", I)
        val reject = Regex("""^(server|servers|choose|select|watch|episode|sub|dub|signup|login|register|forgot|settings?|auto(?:[-_ ]?.*)?)$""", I)
        for (m in Regex("""<(a|li|button|div|span|option)\b([^>]*)>([\s\S]{0,300}?)</\1>""", I).findAll(html)) {
            val tag = "<x ${m.groupValues[2]}>"
            val declaredServer = attr(tag, "data-server")
            val declared = declaredServer.ifEmpty { attr(tag, "data-name") }
            val classId = " " + (attr(tag, "class") + " " + attr(tag, "id")).lowercase() + " "
            val raw = decodeEntities(stripTags(declared.ifEmpty { m.groupValues[3] }))
            val t = raw.take(40)
            if (declaredServer.isEmpty() && raw.length > 40) continue
            if ((looks.containsMatchIn(classId) || declaredServer.isNotEmpty()) && t.isNotEmpty() && !reject.matches(t)) names += t
            if (names.size >= 12) break
        }
        return names.toList()
    }

    // -- m3u -----------------------------------------------------------------

    data class PlaylistInfo(val variants: List<String>, val segmentCount: Int, val duration: Int, val encrypted: Boolean, val live: Boolean)

    fun isM3uText(text: String): Boolean = text.trim().startsWith("#EXTM3U", ignoreCase = true)

    fun m3uPlaylistInfo(text: String, base: String): PlaylistInfo {
        val variants = LinkedHashSet<String>()
        var pendingVariant = false
        var pendingDuration = 0.0
        var segments = 0
        var duration = 0.0
        var encrypted = false
        var live = true
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF:")) { pendingVariant = true; continue }
            if (line.startsWith("#EXTINF:")) { pendingDuration = line.substring(8).substringBefore(',').toDoubleOrNull() ?: 0.0; continue }
            if (line.startsWith("#EXT-X-KEY:") && !line.contains("METHOD=NONE", ignoreCase = true)) encrypted = true
            if (line == "#EXT-X-ENDLIST") live = false
            if (line.startsWith("#")) continue
            val url = abs(line, base)
            if (url.isEmpty()) continue
            if (pendingVariant) { variants += url; pendingVariant = false; continue }
            segments++
            duration += pendingDuration
            pendingDuration = 0.0
        }
        if (segments == 0) live = false
        return PlaylistInfo(variants.toList(), segments, Math.round(duration).toInt(), encrypted, live)
    }

    // -- titles --------------------------------------------------------------

    fun normalizeTitle(s: String): String {
        val decoded = decodeEntities(s).replace(Regex("""\s*\(source test\)\s*$""", I), "")
        return Normalizer.normalize(decoded, Normalizer.Form.NFKD)
            .replace(Regex("""[\u0300-\u036f]"""), "")
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("""\b(the|a|an)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun slugTitle(s: String) = normalizeTitle(s).replace(' ', '-')

    /** Search slugs keep articles: sites index "the-bear", not "bear". */
    fun searchSlug(s: String): String =
        Normalizer.normalize(decodeEntities(s), Normalizer.Form.NFKD)
            .replace(Regex("""[\u0300-\u036f]"""), "")
            .lowercase().replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    private fun stripSeasonSuffix(n: String) =
        n.replace(Regex("""\b(season|series|part|cour)\s+\d+\b"""), " ").replace(Regex("""\s+"""), " ").trim()

    fun explicitSeason(text: String): Int? =
        Regex("""\b(?:season|series|part|cour)\s+(\d{1,3})\b""").find(normalizeTitle(text))?.groupValues?.get(1)?.toIntOrNull()

    private fun tokenSet(s: String) = normalizeTitle(s).split(' ').filter { it.isNotEmpty() }.toSet()

    data class EpisodeInfo(val season: Int?, val episode: Int?)

    fun explicitEpisodeInfo(text: String): EpisodeInfo {
        val normalized = text.replace(Regex("""[_./-]+"""), " ").lowercase()
        Regex("""\bs\s*(\d{1,3})\s*e\s*(\d{1,4})\b""").find(normalized)?.let {
            return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("""\bseason\s*(\d{1,3})\b[^\d]{0,24}\bepisode\s*(\d{1,4})\b""").find(normalized)?.let {
            return EpisodeInfo(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        val urlEp = Regex("""(?:/(?:ep|episode)[-_/]|[?&](?:ep|episode|episode_id)=|-ep(?:isode)?-)(\d{1,4})\b""", I).find(text)
        val episode = urlEp?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\b(?:episode|ep)\s*(\d{1,4})\b""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        return EpisodeInfo(explicitSeason(text), episode)
    }

    data class Candidate(val title: String, val slugTitle: String, val url: String)

    data class Query(
        val titles: List<String>,
        val type: String = "movie",
        val year: String = "",
        val season: Int = 1,
        val episode: Int = 1,
        val imdb: String = ""
    ) {
        val isSeries: Boolean get() = type == "series" || type == "tv"
    }

    private fun candidateImdb(c: Candidate): String =
        Regex("""\btt\d{6,}\b""", I).find(listOf(c.title, c.slugTitle, c.url).joinToString(" "))?.value?.lowercase().orEmpty()

    /** extract.js titleScore: 0..100, with hard zeros for the wrong id or episode. */
    fun titleScore(c: Candidate, q: Query): Int {
        val ep = explicitEpisodeInfo(listOf(c.title, c.slugTitle, c.url).joinToString(" "))
        val episodeConflict = q.isSeries && ((ep.season != null && ep.season != q.season) || (ep.episode != null && ep.episode != q.episode))
        val imdb = candidateImdb(c)
        val wanted = q.imdb.lowercase()
        if (wanted.isNotEmpty() && imdb.isNotEmpty() && wanted != imdb) return 0
        if (wanted.isNotEmpty() && imdb == wanted && !episodeConflict) return 100
        if (episodeConflict) return 0
        var best = 0
        for (rawTarget in q.titles) {
            val target = normalizeTitle(rawTarget)
            if (target.isEmpty()) continue
            val targetTokens = tokenSet(target)
            for (rawCand in listOf(c.title, c.slugTitle).filter { it.isNotBlank() }) {
                val cand = normalizeTitle(rawCand)
                if (cand.isEmpty()) continue
                var score = when {
                    cand == target -> 100
                    stripSeasonSuffix(cand) == target || cand == stripSeasonSuffix(target) -> 95
                    // A one-word title ("bear") prefixing a longer one ("bear grylls") is
                    // usually a different show, so it stays under the accept line.
                    cand.startsWith("$target ") ->
                        if (targetTokens.size < 2) 72
                        else maxOf(68, 88 - (cand.split(' ').size - target.split(' ').size) * 4)
                    target.startsWith("$cand ") -> 82
                    else -> {
                        val candTokens = tokenSet(cand)
                        val hit = targetTokens.count { it in candTokens }
                        val containment = hit.toDouble() / maxOf(1, targetTokens.size)
                        val jaccard = hit.toDouble() / maxOf(1, (targetTokens + candTokens).size)
                        var s = Math.round(containment * 58 + jaccard * 34).toInt()
                        if (hit == targetTokens.size && targetTokens.size > 1) s = maxOf(s, 84)
                        s
                    }
                }
                val season = explicitSeason(rawCand)
                if (season != null) score += if (q.season == season) 3 else -22
                best = maxOf(best, score)
            }
        }
        val years = Regex("""\b(19\d{2}|20\d{2})\b""").findAll(c.title + " " + c.url).map { it.value }.toList()
        if (q.year.isNotEmpty() && years.isNotEmpty()) best += if (q.year in years) 4 else -14
        val anime = Regex("""/anime/""", I).containsMatchIn(c.url)
        if (q.type == "movie" && Regex("""/(tv|series|season|episode)(/|-)""", I).containsMatchIn(c.url) && !anime) best -= 10
        if (q.isSeries && Regex("""/movies?(/|-)""", I).containsMatchIn(c.url) && !anime) best -= 10
        if (q.isSeries && ep.episode != null && ep.episode == q.episode) best += 3
        return best.coerceIn(0, 100)
    }

    /** extract.js identifyTitleCandidate's verdict: accepted at 80 and up. */
    fun isAccepted(c: Candidate, q: Query): Boolean = titleScore(c, q) >= 80

    fun candidateSlugTitle(url: String): String = runCatching {
        val parts = URL(url).path.split('/').filter { it.isNotEmpty() }
            .map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val useful = parts.filterNot {
            Regex("""^(anime|movie|movies|tv|series|show|watch|detail|info|play|title|video|episode|ep-\d+|\d+)$""", I).matches(it)
        }
        normalizeTitle(useful.joinToString(" ").replace(Regex("[-_]+"), " "))
    }.getOrDefault("")

    /** extract.js parseResultLinks: same-site, non-navigation links with a title. */
    fun parseResultLinks(html: String, base: String): List<Candidate> {
        val byUrl = LinkedHashMap<String, Candidate>()
        for (m in Regex("""<a\s[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]{0,1200}?)</a>""", I).findAll(html)) {
            val href = decodeEntities(m.groupValues[1])
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true)) continue
            var url = abs(href, base)
            if (url.isEmpty() || guardUrl(url) == null || !sameSite(url, base)) continue
            val parsed = runCatching { URL(url) }.getOrNull() ?: continue
            url = url.substringBefore('#')
            if (parsed.path == "/" || parsed.path.isEmpty() || BLOCKED_PATH.containsMatchIn(parsed.path + (parsed.query?.let { "?$it" } ?: ""))) continue
            val inner = m.groupValues[2]
            val img = Regex("""<img\s[^>]*>""", I).find(inner)?.value.orEmpty()
            val open = m.value.substring(0, m.value.indexOf('>') + 1)
            val title = decodeEntities(stripTags(attr(open, "title").ifEmpty { attr(img, "alt") }.ifEmpty { inner })).take(140)
            val slug = candidateSlugTitle(url)
            if (title.length < 2 && slug.length < 2) continue
            val prior = byUrl[url]
            if (prior == null || (prior.title.isEmpty() && title.isNotEmpty()) ||
                (title.isNotEmpty() && title.length < prior.title.length && normalizeTitle(title).length >= 2)
            ) byUrl[url] = Candidate(title.ifEmpty { prior?.title.orEmpty() }, slug, url)
            if (byUrl.size >= 220) break
        }
        return byUrl.values.toList()
    }

    // -- search templates ----------------------------------------------------

    private fun addTemplate(out: MutableList<String>, template: String, base: String) {
        val t = template.replace("%7B", "{", true).replace("%7D", "}", true).replace(SEARCH_PLACEHOLDER, "{q}")
        if (!t.contains("{q}") && !t.contains("{slug}")) return
        val probe = abs(t.replace("{q}", "test").replace("{slug}", "test"), base)
        if (guardUrl(probe) == null || !sameSite(probe, base)) return
        if (t !in out) out += t
    }

    /** Rebuild a URL with one query parameter set, keeping the rest. */
    private fun withParam(url: String, name: String, value: String, extra: Map<String, String> = emptyMap()): String {
        val base = url.substringBefore('#')
        val path = base.substringBefore('?')
        val existing = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .filter { it.first != name && it.first !in extra }
        val params = existing + extra.map { it.key to URLEncoder.encode(it.value, "UTF-8") } + (name to value)
        return path + "?" + params.joinToString("&") { "${it.first}=${it.second}" }
    }

    fun searchTemplatesFromHtml(html: String, base: String): List<String> {
        val out = mutableListOf<String>()
        val names = setOf("q", "query", "search", "keyword", "keywords", "s", "title", "text", "term", "name")
        for (fm in Regex("""<form\b[^>]*>[\s\S]{0,4000}?</form>""", I).findAll(html)) {
            val form = fm.value
            val open = form.substring(0, form.indexOf('>') + 1)
            if (Regex("""method\s*=\s*["']?post""", I).containsMatchIn(open)) continue
            val inputs = Regex("""<input\b[^>]*>""", I).findAll(form).map { it.value }.toList()
            val search = inputs.firstOrNull { tag ->
                attr(tag, "name").lowercase() in names || attr(tag, "type").equals("search", true) ||
                    attr(tag, "placeholder").contains("search", true)
            } ?: continue
            val name = attr(search, "name").ifEmpty { "q" }
            val action = abs(attr(open, "action").ifEmpty { base }, base)
            if (guardUrl(action) == null) continue
            val hidden = inputs.filter { attr(it, "type").equals("hidden", true) && attr(it, "name").isNotEmpty() && attr(it, "name") != name }
                .associate { attr(it, "name") to attr(it, "value") }
            addTemplate(out, withParam(action, name, SEARCH_PLACEHOLDER, hidden), base)
        }
        for (m in Regex("""(?:href|action)\s*=\s*"([^"]*(?:search|filter)[^"]*)"""", I).findAll(html)) {
            val raw = abs(decodeEntities(m.groupValues[1]), base)
            if (guardUrl(raw) == null) continue
            val query = raw.substringAfter('?', "").split('&').map { it.substringBefore('=') }
            val key = listOf("keyword", "q", "query", "search", "s", "title").firstOrNull { it in query } ?: continue
            addTemplate(out, withParam(raw, key, SEARCH_PLACEHOLDER), base)
        }
        return out.take(8)
    }

    fun commonSearchTemplates(siteUrl: String): List<String> {
        val u = guardUrl(siteUrl) ?: return emptyList()
        val origin = "${u.protocol}://${u.authority}"
        val roots = linkedSetOf(origin, abs("./", u.toString()).trimEnd('/'))
        val suffixes = listOf("/search?keyword={q}", "/search?q={q}", "/search?query={q}", "/filter?keyword={q}", "/?s={q}", "/search/{slug}", "/search/{q}")
        return roots.filter { it.isNotEmpty() }.flatMap { root -> suffixes.map { root + it } }.distinct()
    }

    fun buildSearchUrl(template: String, query: String): String =
        template.replace("{slug}", searchSlug(query))
            .replace("{q}", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))

    // -- episodes ------------------------------------------------------------

    fun adjustEpisodeInUrl(url: String, season: Int, episode: Int): String {
        var out = url
        out = out.replace(Regex("""([?&](?:ep|episode|episode_id)=)\d+""", I), "$1$episode")
        out = out.replace(Regex("""(/(?:ep|episode)[-_/])(\d+)(/?(?:[?#].*)?)$""", I), "$1$episode$3")
        out = out.replace(Regex("""(-ep(?:isode)?-)(\d+)(/?(?:[?#].*)?)$""", I), "$1$episode$3")
        out = out.replace(Regex("""(/season[-_/])(\d+)(/episode[-_/])(\d+)""", I), "$1$season$3$episode")
        if (Regex("""/(tv|series|show|anime)/""", I).containsMatchIn(out)) {
            out = out.replace(Regex("""(/)(\d{1,3})/(\d{1,4})(/?(?:[?#].*)?)$"""), "$1$season/$episode$4")
        }
        return out
    }

    fun episodeCandidateUrls(url: String, episode: Int): List<String> {
        val u = runCatching { URL(url) }.getOrNull() ?: return emptyList()
        if (EP_IN_URL.containsMatchIn(url)) return emptyList()
        val path = u.path.trimEnd('/')
        if (path.isEmpty()) return emptyList()
        val origin = "${u.protocol}://${u.authority}"
        return listOf("$origin$path/ep-$episode", "$origin$path/episode-$episode")
    }

    fun findEpisodeUrl(html: String, base: String, season: Int, episode: Int): String {
        fun strip(value: String): String = runCatching {
            val u = URL(value)
            "${u.protocol}://${u.authority}" + u.path
                .replace(Regex("""/(?:ep|episode)[-_/]\d+(?:/|$)""", I), "/")
                .replace(Regex("""-ep(?:isode)?-\d+(?:/|$)""", I), "/")
                .trimEnd('/')
        }.getOrDefault("")
        val baseKey = strip(base)
        var best = ""
        var bestScore = -1
        val patterns = listOf(
            Regex("""/(?:ep|episode)[-_/]$episode(?:[/?#]|$)""", I),
            Regex("""-ep(?:isode)?-$episode(?:[/?#]|$)""", I),
            Regex("""[?&](?:ep|episode|episode_id)=$episode(?:[&#]|$)""", I)
        )
        for (m in Regex("""<a\s[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]{0,500}?)</a>""", I).findAll(html)) {
            val url = abs(decodeEntities(m.groupValues[1]), base)
            if (url.isEmpty() || guardUrl(url) == null || !sameSite(url, base)) continue
            val open = m.value.substring(0, m.value.indexOf('>') + 1)
            val text = normalizeTitle(attr(open, "title").ifEmpty { stripTags(m.groupValues[2]) })
            val epMatches = patterns.any { it.containsMatchIn(url) }
            val textMatches = text == "$episode" || text == "episode $episode" || text == "ep $episode"
            if (!epMatches && !textMatches) continue
            var score = if (epMatches) 60 else 40
            if (baseKey.isNotEmpty() && strip(url) == baseKey) score += 25
            explicitSeason(url)?.let { score += if (it == season) 20 else -50 }
            if (score > bestScore) { bestScore = score; best = url }
        }
        return if (bestScore >= 40) best else ""
    }

    fun isEpisodeUrl(url: String) = EP_IN_URL.containsMatchIn(url)

    fun isBlockedPath(url: String): Boolean = runCatching { BLOCKED_PATH.containsMatchIn(URL(url).path) }.getOrDefault(true)
}
