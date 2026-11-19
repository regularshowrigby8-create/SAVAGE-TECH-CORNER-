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

    const val MAX_PER_SOURCE = 8
    const val MAX_CANDIDATES = 1000
    const val MAX_MANIFEST_BYTES = 160_000L
    const val MAX_JSON_BYTES = 1_200_000L

    /** core.js SOURCE_IDS. */
    val SOURCE_IDS = listOf("stremio-addons", "catalog-graph", "github")

    /**
     * sources.js DEFAULT_ADDON_SOURCES minus the `skip` entries: Torrentio is
     * stream-only torrents, which the guard refuses anyway, so it is not
     * registered at all.
     */
    val DEFAULT_SOURCES = listOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io"
    )

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
        val types = (obj["types"] as? JsonArray).strings().map { it.lowercase() }.distinct()
        if (types.none { it == "movie" || it == "series" || it == "tv" }) return null
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

    /** Ranking weight for a quality label, highest first. */
    fun qualityRank(quality: String): Int = quality.removeSuffix("p").toIntOrNull() ?: 0

    // -- helpers -----------------------------------------------------------

    internal fun clean(value: String?, max: Int = 240): String =
        value.orEmpty().replace(Regex("\\s+"), " ").trim().take(max)

    private fun JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    internal fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull.orEmpty()
}
