package dev.nova.tv.finder

import dev.nova.tv.data.model.Stream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * The rules of the ZeroPlay source finder, ported line for line to Kotlin.
 *
 * Origin, same repository:
 *  - `tv/zeroplay-debrid/lib/server-finder/core.js` (branch arena/acbde91b):
 *    [publicHttpsUrl], [parseManifest], [extractManifestUrls], [entryUrl],
 *    [baseFromManifestUrl], MAX_PER_SOURCE / MAX_MANIFEST_BYTES.
 *  - `tv/zeroplay-debrid/lib/global-resolver/sources.js` (this branch):
 *    [catalogueId], [streamSegment], [manifestSupports], [classifyStream],
 *    [guessQuality], [DEFAULT_SOURCES].
 *
 * Everything here is pure (no Android, no network) so it runs as a JVM unit
 * test, and the JS test cases can be replayed against it.
 */
object FinderRules {

    const val MAX_PER_SOURCE = 24
    const val MAX_CANDIDATES = 6000
    const val MAX_MANIFEST_BYTES = 160_000L
    const val MAX_JSON_BYTES = 1_200_000L

    /**
     * The file-size cap: nothing larger than 5 GB is played or even offered.
     * GiB, because that is what addons print ("4.98 GB" is 4.98 GiB).
     */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024 * 1024

    /**
     * The ZeroPlay finder's directories.
     *
     * `stremio-addons` and `catalog-graph` were removed: they are Stremio
     * directories and [StremioFinder] already reads them, so every host they
     * yielded arrived twice. `web` is the open-web crawler ([WebCrawler]) that
     * replaced the fixed script sequence, and `github` stays as its
     * highest-yield seed.
     */
    val SOURCE_IDS = listOf("web", "github")

    /**
     * The catalogues Nova ships with.
     *
     * Cinemeta is the backbone (it is the only addon that can answer for any
     * `tt` id), but relying on it alone meant one slow manifest produced an
     * empty home screen and no search results. These are twelve further
     * catalogue addons, each checked live: every URL below returned a manifest
     * with a `catalog` resource and at least one non-empty catalogue row.
     * OpenSubtitles stays for subtitles.
     *
     * Order matters only for the fallback row: the first addon that declares a
     * catalogue wins, so Cinemeta is first.
     */
    val DEFAULT_SOURCES = listOf(
        // Backbone: meta for every tt id, plus Popular / Featured / New.
        "https://v3-cinemeta.strem.io",
        // The Movie Database: the "tmdb" catalogue that had gone missing.
        "https://94c8cb9f702d-tmdb-addon.baby-beamup.club",
        // Streaming Catalogs: per-service rows (Netflix, Prime, Disney+, ...).
        "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club",
        // Streaming Catalogs Plus: 46 curated rows.
        "https://scplus.duckdns.org",
        // Anime.
        "https://anime-kitsu.strem.fun",
        // Film / TV libraries.
        "https://cinema.debrid.in",
        "https://horror.debrid.in",
        "https://addon-marvel.gonp.deno.net",
        // Live TV.
        "https://tvvoo.stremio-italia.eu/cfg-it-uk-fr",
        "https://sports.highfly.dev",
        // YouTube channels.
        "https://v3-channels.strem.io",
        // Broad series / film rows.
        "https://addon.notorrent2.workers.dev",
        "https://27a5b2bfe3c0-stremio-brazilian-addon.baby-beamup.club",
        // Subtitles.
        "https://opensubtitles-v3.strem.io"
    )

    /** Addons that only carry subtitles; they must never be asked for streams. */
    val SUBTITLE_ONLY_SOURCES = setOf("https://opensubtitles-v3.strem.io")

    /**
     * core.js publicHttpsUrl: https only, no credentials in the URL, no IP
     * literal, no localhost / .local / .internal. Returns "" when refused, so
     * callers can treat the result as a value rather than a nullable.
     */
    fun publicHttpsUrl(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val uri = runCatching { URI(text) }.getOrNull() ?: return ""
        if (!uri.scheme.equals("https", ignoreCase = true)) return ""
        if (uri.rawUserInfo != null) return ""
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return ""
        if (host.isEmpty() || isIpLiteral(host)) return ""
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return ""
        return uri.toString().take(1800)
    }

    internal fun isIpLiteral(host: String): Boolean =
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.contains(':')

    /** What a manifest says about itself, after the finder's checks. */
    data class ManifestInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val resources: List<String>,
        val types: List<String>,
        val idPrefixes: List<String>,
        val searchable: Boolean
    )

    /**
     * core.js parseManifest: a manifest is only a source if it declares a
     * `stream` resource and at least one of movie / series / tv.
     */
    fun parseManifest(raw: JsonElement?): ManifestInfo? {
        val obj = raw as? JsonObject ?: return null
        val resourceRows = obj["resources"] as? JsonArray ?: JsonArray(emptyList())
        val resources = resourceRows.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> (item["name"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct()
        if ("stream" !in resources) return null
        // A manifest that declares itself peer-to-peer serves torrents.
        val hints = obj["behaviorHints"] as? JsonObject
        if ((hints?.get("p2p") as? JsonPrimitive)?.contentOrNull == "true") return null
        val types = (obj["types"] as? JsonArray).strings().map { it.lowercase() }.distinct()
        if (types.none { it == "movie" || it == "series" || it == "tv" }) return null
        if (isRedundantSource(obj.string("name"))) return null
        val prefixPattern = Regex("^[a-z0-9_-]{1,24}$")
        val prefixes = ((obj["idPrefixes"] as? JsonArray).strings() +
            resourceRows.filterIsInstance<JsonObject>().flatMap { (it["idPrefixes"] as? JsonArray).strings() })
            .map { it.lowercase() }
            .filter { prefixPattern.matches(it) }
            .distinct()
        val catalogs = (obj["catalogs"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val searchable = catalogs.any { catalog ->
            (catalog["extra"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.any { (it["name"] as? JsonPrimitive)?.contentOrNull == "search" } == true
        }
        val id = obj.string("id")
        return ManifestInfo(
            id = clean(id, 120),
            name = clean(obj.string("name").ifBlank { id.ifBlank { "Streaming addon" } }, 80),
            version = clean(obj.string("version"), 40),
            description = clean(obj.string("description"), 240),
            resources = resources,
            types = types.map { if (it == "tv") "series" else it }.distinct(),
            idPrefixes = prefixes,
            searchable = searchable
        )
    }

    /** core.js providerRecord's base: strip /manifest.json and the trailing slash. */
    fun baseFromManifestUrl(manifestUrl: String): String {
        val safe = publicHttpsUrl(manifestUrl)
        if (safe.isEmpty()) return ""
        return safe.replace(Regex("""/manifest\.json(?:[?#].*)?$""", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')
    }

    /** core.js extractManifestUrls: at most 12 public manifest links from free text. */
    fun extractManifestUrls(text: String?): List<String> {
        val urls = LinkedHashSet<String>()
        val pattern = Regex("""https?://[^\s"'<>()\[\]{}]+""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(text.orEmpty())) {
            val raw = match.value.trimEnd('.', ',', ';', '!', '?').replace("&amp;", "&")
            if (!Regex("""manifest\.json(?:[?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) continue
            val url = publicHttpsUrl(raw)
            if (url.isNotEmpty()) urls += url
            if (urls.size >= 12) break
        }
        return urls.toList()
    }

    /** core.js entryUrl: the manifest a directory entry points at. */
    fun entryUrl(entry: JsonObject): String {
        val transport = (entry["transport"] as? JsonObject)?.string("url").orEmpty()
        val raw = listOf(entry.string("manifestUrl"), entry.string("transportUrl"), transport, entry.string("url"))
            .firstOrNull { it.isNotBlank() }
        return publicHttpsUrl(raw)
    }

    // -- stream rules (global-resolver/sources.js) --------------------------

    /**
     * Providers Nova deliberately does not use: torrent indexes (no torrents
     * here) and VOD addons that only repeat the same catalogue under another
     * name (penguplay). Matched against the addon name and its host, never its
     * description, so a legitimate provider cannot be dropped by prose.
     */
    /**
     * Sources that must never appear, even if their manifest looks valid.
     *
     * Three groups:
     *  - torrent indexes (Nova plays direct HTTP only, by design);
     *  - VOD/server front-ends that need credentials, an account, a self-hosted
     *    instance or a setup wizard before they return a single playable link
     *    (penguplay, jacketio/jackett, IPTV/xtream panels). They show up as
     *    "sources" but every stream is a dead end, which is worse than nothing;
     *  - the duplicate VOD aggregators that simply re-list the same hosts.
     */
    private val REDUNDANT_NAMES = listOf(
        // Torrent indexes.
        "torrentio", "torrent", "thepiratebay", "pirate bay", "1337x", "rarbg",
        "yts", "nyaa", "eztv", "kickass", "limetorrents", "magnetdl", "bitsearch",
        // Internet Archive: removed from the finder by request. Its catalogue
        // is public-domain material, so it answered for every id with a film
        // that was not the one asked for -- the exact "it plays something
        // completely different" failure. Matched on name and host.
        "internet archive", "internetarchive", "archive.org", "archive org",
        "archive-org", "ia-archive", "archiveorg",
        // Setup-required VOD / server front-ends.
        "penguplay", "pengu play", "pengu-play", "pengu",
        "jacketio", "jackett", "jackettio",
        "iptv", "xtream", "xstream", "ssiptv", "stbemu", "stb emu", "vavoo",
        "ott navigator", "tivimate", "smart iptv", "m3u list", "playlist loader",
        "requires setup", "setup required", "configure first", "self-hosted",
        "self hosted", "login required", "account required"
    )

    fun isRedundantSource(name: String?, baseUrl: String? = null): Boolean {
        val haystack = (name.orEmpty() + " " + baseUrl.orEmpty()).lowercase()
        if (haystack.isBlank()) return false
        return REDUNDANT_NAMES.any { it in haystack }
    }

    /**
     * sources.js catalogueId: `tt0903747` for a film, `tt0903747:1:2` for an
     * episode. Colons stay literal; encoding them is the 404 the feed layer
     * already hit once.
     */
    fun catalogueId(id: String, isSeries: Boolean, season: Int = 1, episode: Int = 1): String {
        val base = id.substringBefore(':')
        if (!isSeries) return base
        return "$base:${season.coerceAtLeast(1)}:${episode.coerceAtLeast(1)}"
    }

    /** sources.js streamSegment: URL-encode everything except the protocol's colons. */
    fun streamSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%3A", ":", ignoreCase = true)

    /** sources.js manifestSupports. */
    fun manifestSupports(resources: List<String>, types: List<String>, type: String): Boolean {
        if (resources.none { it.equals("stream", ignoreCase = true) }) return false
        val declared = types.ifEmpty { listOf("movie", "series") }.map { it.lowercase() }
        val wanted = if (type == "tv" || type == "series") setOf("series", "tv") else setOf("movie")
        return declared.any { it in wanted }
    }

    enum class StreamKind { HLS, DASH, DIRECT }

    /** Either a kind Nova can play, or the reason it cannot. */
    sealed interface Verdict {
        data class Playable(val kind: StreamKind) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * sources.js streamEntryToResult, minus one rule: the web player had to
     * refuse `proxyHeaders` because a browser cannot set them on a <video>.
     * ExoPlayer can, so on TV those streams are kept and the headers are sent.
     */
    fun classifyStream(stream: Stream): Verdict {
        if (!stream.infoHash.isNullOrBlank()) return Verdict.Rejected("torrent-only")
        if (!stream.nzbUrl.isNullOrBlank()) return Verdict.Rejected("usenet-only")
        if (!stream.ytId.isNullOrBlank()) return Verdict.Rejected("youtube-only")
        if (!stream.externalUrl.isNullOrBlank() && stream.url.isNullOrBlank()) return Verdict.Rejected("external-page")
        val url = stream.url?.trim().orEmpty()
        if (url.isEmpty()) return Verdict.Rejected("no-url")
        if (Regex("^(magnet:|ed2k:|thunder:)", RegexOption.IGNORE_CASE).containsMatchIn(url)) return Verdict.Rejected("magnet")
        if (Regex("""\.torrent(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return Verdict.Rejected("torrent-file")
        if (stream.behaviorHints?.notWebReady == true) return Verdict.Rejected("not-web-ready")
        if (stream.sizeBytes > MAX_FILE_BYTES) return Verdict.Rejected("oversize")
        // ExoPlayer only speaks http(s); anything else is not a stream here.
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return Verdict.Rejected("unsupported-scheme")
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            Regex("""\.m3u8?$""").containsMatchIn(path) -> Verdict.Playable(StreamKind.HLS)
            path.endsWith(".mpd") -> Verdict.Playable(StreamKind.DASH)
            Regex("""\.(mp4|m4v|mov|webm|mkv|avi|ts)$""").containsMatchIn(path) -> Verdict.Playable(StreamKind.DIRECT)
            // Extensionless is fine for a CDN; a page is not a stream.
            Regex("""/(watch|embed|player|page)/""").containsMatchIn(path) -> Verdict.Rejected("page-not-stream")
            else -> Verdict.Playable(StreamKind.DIRECT)
        }
    }

    /** sources.js guessQuality: "2160p", "1080p", ... or "". */
    fun guessQuality(text: String?): String {
        val match = Regex("""(\d{3,4})p|4k|2160p|1080p|720p""", RegexOption.IGNORE_CASE).find(text.orEmpty())
            ?: return ""
        val found = (match.groupValues[1].ifEmpty { match.value }).lowercase()
        if (found == "4k") return "2160p"
        return if (found.endsWith("p")) found else found + "p"
    }

    /**
     * "2.5 GB", "800 MB", "5,71 GB" -> bytes; 0 when no size is printed.
     * Ported from ARVIO's StreamRepository.parseSizeToBytes (Apache-2.0).
     */
    fun parseSizeBytes(text: String?): Long {
        val match = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|GiB|MiB|TiB)\b""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty()) ?: return 0L
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0L
        val unit = when (match.groupValues[2].uppercase().replace("I", "")) {
            "TB" -> 1024.0 * 1024 * 1024 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            "MB" -> 1024.0 * 1024
            else -> 0.0
        }
        return (value * unit).toLong()
    }

    /** 2254857830 -> "2.1 GB"; "" for unknown. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024 * 1024))
        else -> "%d MB".format(java.util.Locale.US, bytes / (1024 * 1024))
    }

    /** Ranking weight for a quality label, highest first. */
    fun qualityRank(quality: String): Int = quality.removeSuffix("p").toIntOrNull() ?: 0

    // -- identity: does this stream really belong to this title? -----------

    /**
     * Words that appear in stream labels without saying anything about *which*
     * title they carry: quality, codecs, release groups, sizes, languages.
     * A label made only of these is "unknown", not "wrong".
     */
    private val GENERIC_TOKENS = setOf(
        "4k", "2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "hd", "fhd", "uhd", "sd",
        "hdr", "hdr10", "dv", "dolby", "vision", "atmos", "sdr", "10bit", "8bit",
        "web", "webdl", "webrip", "web-dl", "bluray", "blu-ray", "brrip", "bdrip", "dvdrip", "hdtv",
        "hdrip", "remux", "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "aac", "ac3", "dts",
        "ddp", "dd", "5", "1", "7", "2", "0", "truehd", "flac", "mp3", "opus",
        "multi", "dual", "audio", "sub", "subs", "subtitle", "subtitles", "dubbed", "dub", "esub",
        "eng", "english", "spa", "spanish", "fre", "french", "ger", "german", "ita", "italian",
        "por", "portuguese", "hin", "hindi", "jpn", "japanese", "kor", "korean", "rus", "arabic",
        "gb", "mb", "tb", "gib", "mib", "size", "file", "stream", "source", "server", "direct",
        "download", "play", "online", "free", "full", "movie", "film", "series", "season", "episode",
        "complete", "uncut", "extended", "remastered", "proper", "repack", "internal", "limited",
        "amzn", "nf", "dsnp", "hmax", "atvp", "pcok", "itunes", "ma", "hulu", "criterion",
        "s", "e", "x", "the", "a", "an", "and", "of", "in", "on", "to", "part"
    )

    /** Lower-case word tokens, punctuation and release dots removed. */
    fun titleTokens(text: String?): List<String> =
        text.orEmpty().lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }

    /** Tokens that actually identify a title (generic noise removed). */
    private fun identityTokens(text: String?): List<String> =
        titleTokens(text).filter { it !in GENERIC_TOKENS && it.length > 1 }

    private val EPISODE_PATTERN = Regex("""\bs(\d{1,2})\s*[._-]?\s*e(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val SEASON_PATTERN = Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val YEAR_PATTERN = Regex("""\b(19\d{2}|20\d{2})\b""")

    /** "S02E05" / "Season 2" markers found in a label, if any. */
    fun episodeMarkers(text: String?): Pair<Int, Int>? {
        val raw = text.orEmpty()
        EPISODE_PATTERN.find(raw)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
        }
        SEASON_PATTERN.find(raw)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0) to 0
        }
        return null
    }

    /**
     * The guard against a source that answers with the wrong film.
     *
     * Addons are asked for one id and are trusted to answer for that id, but
     * several (archive-style catalogues, scrapers that fall back to a default
     * title, mis-keyed debrid caches) answer with *something* instead. The
     * symptom the viewer sees is "I picked Dune and it played a 1950s western".
     *
     * The rule is deliberately asymmetric: only reject on positive evidence of
     * a different title, never on absence of evidence. A label that says
     * nothing but "1080p WEB-DL 2.1 GB" is kept, because most real addon
     * streams look exactly like that.
     *
     * Reject when:
     *  - the label names an episode/season and it is not the one requested;
     *  - the label prints a year that is more than a year away from the title's;
     *  - the label carries two or more identity words and none of them is in
     *    the title (i.e. it names a different title).
     */
    fun streamMatchesTitle(
        stream: Stream,
        title: String,
        year: String? = null,
        season: Int = 0,
        episode: Int = 0
    ): Boolean {
        val label = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
            .joinToString(" ")
            .ifBlank { stream.url.orEmpty().substringAfterLast('/').substringBefore('?') }
        if (label.isBlank()) return true

        // 1. Wrong episode of the right series is still the wrong thing to play.
        if (season > 0) {
            val marker = episodeMarkers(label)
            if (marker != null) {
                val (s, e) = marker
                if (s > 0 && s != season) return false
                if (e > 0 && episode > 0 && e != episode) return false
            }
        }

        // 2. A year that cannot be this title's.
        val wantedYear = year.orEmpty().take(4).toIntOrNull()
        if (wantedYear != null) {
            val printed = YEAR_PATTERN.findAll(label).mapNotNull { it.value.toIntOrNull() }.toList()
            if (printed.isNotEmpty() && printed.none { kotlin.math.abs(it - wantedYear) <= 1 }) return false
        }

        // 3. A label that clearly names another title.
        val wanted = identityTokens(title)
        if (wanted.isEmpty()) return true
        val printed = identityTokens(label)
        if (printed.isEmpty()) return true
        val overlap = printed.count { it in wanted }
        if (overlap > 0) return true
        // Two or more identifying words, none of them ours: a different title.
        return printed.size < 2
    }

    /** True when a candidate's name/host is the Internet Archive. */
    fun isInternetArchive(name: String?, baseUrl: String? = null): Boolean {
        val haystack = (name.orEmpty() + " " + baseUrl.orEmpty()).lowercase()
        return "archive.org" in haystack || "internet archive" in haystack ||
            "internetarchive" in haystack || "archive-org" in haystack
    }

    // -- helpers -----------------------------------------------------------

    internal fun clean(value: String?, max: Int = 240): String =
        value.orEmpty().replace(Regex("\\s+"), " ").trim().take(max)

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    internal fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull.orEmpty()
}
