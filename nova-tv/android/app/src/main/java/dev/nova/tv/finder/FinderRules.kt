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

    /**
     * How many manifests one directory may yield in a single round.
     *
     * This used to be 8, which is why the 3000-slot pool stalled a few hundred
     * entries in: the directories were read eight manifests deep and then
     * declared exhausted. A community catalogue has hundreds of addons, so the
     * cap is now high enough for one round to fill a large slice of the pool.
     */
    const val MAX_PER_SOURCE = 96
    const val MAX_CANDIDATES = 20_000
    const val MAX_MANIFEST_BYTES = 160_000L
    const val MAX_JSON_BYTES = 1_200_000L

    /** core.js kept 30 rows per provider; the same ceiling applies here. */
    const val MAX_CATALOG_DEFS = 30
    const val MAX_ADDON_CATALOGS = 30

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
        // -- broader catalogue: twelve more rows ---------------------------
        // Cinemeta's own secondary catalogue host (different rows from v3).
        "https://cinemeta-catalogs.strem.io",
        // Official WatchHub: which legal service carries a title, per country.
        "https://watchhub.strem.io",
        // Public-domain film library; a real catalogue, not a search stub.
        "https://v3-publicdomainmovies.strem.io",
        // YouTube catalogue: documentaries, music, full-length uploads.
        "https://v3-youtube.strem.io",
        // Anime, second key (Kitsu and AniList are separate id spaces).
        "https://anime-kitsu.strem.io",
        "https://kitsu.strem.fun",
        // Genre / franchise rows that Cinemeta does not carry.
        "https://v3-marvel.strem.io",
        "https://v3-dc.strem.io",
        "https://v3-starwars.strem.io",
        // Per-service rows (Netflix / Prime / Disney+ catalogues).
        "https://v3-netflix.strem.io",
        "https://v3-prime.strem.io",
        "https://v3-disney.strem.io",
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

    /**
     * What an addon can actually answer, derived once at parse time.
     *
     * Imported from core.js `providerRecord().identification.supports`. ZeroPlay
     * recorded this per provider so the UI could grey out a source that cannot
     * serve the current request instead of firing a doomed network call at it.
     * Nova previously only kept `searchable`, so every candidate was asked for
     * everything.
     */
    data class Capabilities(
        val movie: Boolean,
        val series: Boolean,
        val streams: Boolean,
        val catalog: Boolean,
        val meta: Boolean,
        val subtitles: Boolean,
        val addonCatalog: Boolean,
        val search: Boolean,
        val episodeLookup: Boolean,
        /** core.js identifierModes: "tt" becomes "imdb-id". */
        val identifierModes: List<String>,
        /** core.js confidence: 88 for a manifest-verified provider. */
        val confidence: Int = CONFIDENCE,
        val checks: List<String> = DEFAULT_CHECKS
    ) {
        /** Can this addon answer a stream request for [type] at all? */
        fun serves(type: String): Boolean = streams && when (type) {
            "series" -> series
            "movie" -> movie
            else -> true
        }

        companion object {
            const val CONFIDENCE = 88
            val DEFAULT_CHECKS = listOf(
                "public HTTPS manifest",
                "stream resource and movie/series types confirmed",
                "candidate requires title-scoped identity-verified playback match"
            )
        }
    }

    /** What a manifest says about itself, after the finder's checks. */
    data class ManifestInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val resources: List<String>,
        val types: List<String>,
        val idPrefixes: List<String>,
        val searchable: Boolean,
        /** core.js catalogDefs: rows this addon can list. */
        val catalogDefs: List<String> = emptyList(),
        /** core.js addonCatalogs: addons this one can recommend. */
        val addonCatalogs: List<String> = emptyList(),
        val capabilities: Capabilities = Capabilities(
            movie = false, series = false, streams = false, catalog = false,
            meta = false, subtitles = false, addonCatalog = false,
            search = false, episodeLookup = false, identifierModes = emptyList()
        )
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
        // core.js catalogDefs / addonCatalogs: keep the row identities, not just
        // a boolean, so the catalogue screen can name a source's own rows.
        val catalogDefs = catalogs.mapNotNull { catalog ->
            val type = catalog.string("type").orEmpty()
            val id = catalog.string("id").orEmpty()
            if (type.isBlank() || id.isBlank()) null else "$type/$id"
        }.distinct().take(MAX_CATALOG_DEFS)
        val addonCatalogs = (obj["addonCatalogs"] as? JsonArray)?.filterIsInstance<JsonObject>()
            .orEmpty()
            .mapNotNull { it.string("url").ifBlank { null } }
            .map { publicHttpsUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ADDON_CATALOGS)
        val normalisedTypes = types.map { if (it == "tv") "series" else it }.distinct()
        val capabilities = Capabilities(
            movie = "movie" in normalisedTypes,
            series = "series" in normalisedTypes,
            streams = "stream" in resources,
            catalog = "catalog" in resources,
            meta = "meta" in resources,
            subtitles = "subtitles" in resources,
            addonCatalog = "addon_catalog" in resources,
            search = searchable,
            episodeLookup = "series" in normalisedTypes,
            identifierModes = prefixes.map { if (it == "tt") "imdb-id" else it }
        )
        val id = obj.string("id")
        return ManifestInfo(
            id = clean(id, 120),
            name = clean(obj.string("name").ifBlank { id.ifBlank { "Streaming addon" } }, 80),
            version = clean(obj.string("version"), 40),
            description = clean(obj.string("description"), 240),
            resources = resources,
            types = normalisedTypes,
            idPrefixes = prefixes,
            searchable = searchable,
            catalogDefs = catalogDefs,
            addonCatalogs = addonCatalogs,
            capabilities = capabilities
        )
    }

    /** core.js providerRecord's base: strip /manifest.json and the trailing slash. */
    fun baseFromManifestUrl(manifestUrl: String): String {
        val safe = publicHttpsUrl(manifestUrl)
        if (safe.isEmpty()) return ""
        return safe.replace(Regex("""/manifest\.json(?:[?#].*)?$""", RegexOption.IGNORE_CASE), "")
            .trimEnd('/')
    }

    /**
     * core.js extractManifestUrls: every public manifest link in free text.
     *
     * The JS stopped at 12 because a README never holds more. The directory
     * graph (addon_catalog / stremio-addons.net) does, and stopping at 12 was
     * one of the reasons the 3000-slot pool never filled, so the cap is now a
     * parameter and the default is large.
     */
    fun extractManifestUrls(text: String?, limit: Int = 600): List<String> {
        val urls = LinkedHashSet<String>()
        val pattern = Regex("""https?://[^\s"'<>()\[\]{}]+""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(text.orEmpty())) {
            val raw = match.value.trimEnd('.', ',', ';', '!', '?').replace("&amp;", "&")
            if (!Regex("""manifest\.json(?:[?#].*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) continue
            val url = publicHttpsUrl(raw)
            if (url.isNotEmpty()) urls += url
            if (urls.size >= limit) break
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
     * Hosts and names that only work after the viewer creates an account,
     * buys a subscription, or configures a panel. They advertise a `stream`
     * resource, so every manifest check passes, and then every single request
     * is a dead end -- exactly the "redundant VOD servers that require signing
     * up" the pool must not store.
     *
     * Matched against the addon name and its host only. Descriptions are never
     * consulted: a legitimate provider that mentions "no account required"
     * must not be dropped by its own prose.
     */
    private val SIGNUP_NAMES = listOf(
        "real-debrid", "realdebrid", "alldebrid", "premiumize", "debrid-link", "debridlink",
        "put.io", "putio", "easynews", "usenet", "newshosting", "torbox",
        "trakt", "simkl", "letterboxd", "myanimelist", "anilist account",
        "netflix login", "prime login", "disney login", "hulu login", "hbo login",
        "sign in", "signin", "sign-in", "log in", "login", "subscribe", "subscription",
        "create account", "register", "membership", "premium only", "patreon",
        "plex", "emby", "jellyfin", "tautulli", "overseerr", "sonarr", "radarr",
        "nzbget", "sabnzbd", "transmission", "qbittorrent", "deluge"
    )

    /** Host fragments that are account walls, not stream servers. */
    private val SIGNUP_HOSTS = listOf(
        "real-debrid.com", "alldebrid.com", "premiumize.me", "debrid-link.com",
        "put.io", "easynews.com", "torbox.app", "trakt.tv", "simkl.com",
        "patreon.com", "plex.tv", "emby.media", "jellyfin.org",
        "netflix.com", "primevideo.com", "disneyplus.com", "hulu.com", "max.com",
        "hbomax.com", "paramountplus.com", "peacocktv.com", "crunchyroll.com"
    )

    /** True when a source is an account wall / setup wizard rather than a server. */
    fun requiresSignup(name: String?, baseUrl: String? = null): Boolean {
        val label = name.orEmpty().lowercase()
        val host = baseUrl.orEmpty().lowercase()
        if (label.isBlank() && host.isBlank()) return false
        if (SIGNUP_HOSTS.any { it in host }) return true
        return SIGNUP_NAMES.any { it in label }
    }

    /**
     * The single gate every discovered server passes before it may be stored:
     * no torrents, no Internet Archive, no duplicate VOD front-end, no account
     * wall. [ServerTester] adds the liveness check on top of this.
     */
    fun isStorable(name: String?, baseUrl: String? = null): Boolean {
        if (baseUrl.isNullOrBlank()) return false
        if (isInternetArchive(name, baseUrl)) return false
        if (isRedundantSource(name, baseUrl)) return false
        if (requiresSignup(name, baseUrl)) return false
        return true
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

    /**
     * A validated title identity, the Kotlin form of core.js `normalizeIdentity`.
     *
     * ZeroPlay refused to search with a title shorter than two characters, and
     * clamped season/episode into sane ranges, so a malformed request could not
     * turn into a hundred junk queries. [TitleWebSearch] builds every query from
     * this object instead of from raw UI strings.
     */
    data class TitleIdentity(
        val title: String,
        /** Alternative titles, at most [MAX_TITLES], used for fallback queries. */
        val titles: List<String>,
        val type: String,
        val year: Int?,
        val imdbId: String,
        val tmdbId: Int?,
        val season: Int,
        val episode: Int
    ) {
        val isSeries: Boolean get() = type == "series"

        /** "tt0903747:1:1" for a series, the bare id for a film. */
        fun streamId(): String = if (isSeries) "$imdbId:$season:$episode" else imdbId

        /** The primary query: title, year, and SxxExx when episodic. */
        fun query(): String = buildString {
            append(title)
            year?.let { append(' ').append(it) }
            if (isSeries) append(' ').append("S%02dE%02d".format(season, episode))
        }

        companion object {
            const val MAX_TITLES = 5
        }
    }

    /**
     * core.js normalizeIdentity: reject what cannot be searched, clamp what can.
     * Returns null when there is no usable title, which callers treat as "do not
     * spend a single request".
     */
    fun normalizeIdentity(
        title: String?,
        year: Int? = null,
        imdbId: String? = null,
        tmdbId: Int? = null,
        type: String? = null,
        season: Int? = null,
        episode: Int? = null,
        titles: List<String> = emptyList()
    ): TitleIdentity? {
        val primary = clean(title, 160)
        if (primary.length < 2) return null
        val allTitles = (listOf(primary) + titles)
            .map { clean(it, 160) }
            .filter { it.length >= 2 }
            .distinct()
            .take(TitleIdentity.MAX_TITLES)
        val resolvedType = if (type == "tv" || type == "series") "series" else "movie"
        val cleanYear = year?.takeIf { it in 1870..2200 }
        val cleanImdb = imdbId.orEmpty().trim().lowercase()
            .takeIf { Regex("""^tt\d{5,12}$""").matches(it) }.orEmpty()
        val cleanTmdb = tmdbId?.takeIf { it in 1..999_999_999 }
        val cleanSeason = season?.takeIf { it in 1..999 } ?: 1
        val cleanEpisode = episode?.takeIf { it in 1..9999 } ?: 1
        return TitleIdentity(
            title = primary,
            titles = allTitles,
            type = resolvedType,
            year = cleanYear,
            imdbId = cleanImdb,
            tmdbId = cleanTmdb,
            season = cleanSeason,
            episode = cleanEpisode
        )
    }

    /**
     * core.js stableFingerprint: a short, deterministic hash of an identity.
     *
     * Used as a cache key so a repeated title request reuses the previous crawl
     * instead of hitting the directories again, and so two addons that differ
     * only by trailing slash or case collapse to one pool entry.
     */
    fun stableFingerprint(vararg parts: String?): String {
        val material = parts.joinToString("\u0000") { it.orEmpty().trim().lowercase() }
        // FNV-1a, 64 bit: stable across JVM versions, unlike String.hashCode
        // which is specified but 32 bit and collides too readily for a cache key.
        var hash = -0x340d631b7bdddcdbL
        for (byte in material.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }

    // -- helpers -----------------------------------------------------------

    internal fun clean(value: String?, max: Int = 240): String =
        value.orEmpty().replace(Regex("\\s+"), " ").trim().take(max)

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    internal fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull.orEmpty()
}
