package dev.nova.tv.data.remote

import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * Where a subtitle file came from. The viewer can switch each of these off on
 * the Sources screen; the subtitles panel then only lists what is left.
 */
enum class SubtitleProvider(val label: String, val detail: String) {
    OPEN_SUBTITLES("OpenSubtitles", "Stremio index"),
    OPEN_SUBTITLES_PRO("OpenSubtitles PRO", "Ad-free index"),
    SUB_SENSE("SubSense", "10 providers"),
    OPEN_SUBTITLES_REST("OpenSubtitles REST", "Release matched"),
    ADDONS("Addon subtitles", "Installed addons")
}

/**
 * Subtitle search, with more than one engine behind it.
 *
 * Before this there was exactly one: whichever installed addon happened to
 * declare the `subtitles` resource (OpenSubtitles v3), so a title it did not
 * know about had no captions at all and the viewer had to pick a file from
 * the device.
 *
 * Four keyless engines now run in parallel and their results are merged:
 *
 *  - **OpenSubtitles v3** (the Stremio addon endpoint) returns a rich index:
 *    language, release name and file encoding.
 *  - **OpenSubtitles PRO** speaks the same `/subtitles/{type}/{id}.json`
 *    protocol against the same library, but filters the ad and spam entries
 *    that v3 still returns. It is a second host, so an outage on one leaves
 *    the other answering.
 *  - **SubSense** aggregates roughly ten providers (OpenSubtitles, Subf2m,
 *    Podnapisi, AnimeTosho, Gestdown, BetaSeries, YIFY, TVsubtitles,
 *    SubSource, SubDL) behind one Stremio-protocol endpoint, which is where
 *    subtitles for anime and for less common languages actually come from.
 *    It is skipped, rather than failed, when the viewer filters those
 *    languages out.
 *  - **OpenSubtitles REST** (`rest.opensubtitles.org`) matches by IMDb id and
 *    season/episode. It answers with release names and download counts, and
 *    its download links are gzipped `.srt` files rather than playable URLs, so
 *    the file is fetched, decompressed and cached on device -- Media3 then
 *    plays it like any other sidecar.
 *
 * Every one of those hosts was fetched and its manifest checked to declare
 * `"resources":["subtitles"]`; none needs an account or an API key. Two of them
 * (PRO, SubSense) are marked `configurationRequired` in their manifests, but
 * the unconfigured endpoint still answers -- configuration only changes which
 * languages are preferred, which Nova chooses itself through [find]'s `lang`.
 *
 * Installed addons stay in the mix through [CatalogRepository].
 */
class SubtitleFinder(private val http: HttpClient, private val cacheDir: File? = null) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun find(
        type: String,
        id: String,
        lang: String = "eng",
        providers: Set<SubtitleProvider> = SubtitleProvider.entries.toSet()
    ): List<SubtitleRef> = coroutineScope {
        val wanted = providers - SubtitleProvider.ADDONS // addons are read by the repository
        val found = wanted.map { provider ->
            async {
                runCatching {
                    when (provider) {
                        SubtitleProvider.OPEN_SUBTITLES -> stremioIndex(OPEN_SUBTITLES_V3, type, id, SubtitleProvider.OPEN_SUBTITLES)
                        SubtitleProvider.OPEN_SUBTITLES_PRO -> stremioIndex(OPEN_SUBTITLES_PRO, type, id, SubtitleProvider.OPEN_SUBTITLES_PRO)
                        SubtitleProvider.SUB_SENSE -> subSense(type, id)
                        SubtitleProvider.OPEN_SUBTITLES_REST -> openSubtitlesRest(type, id, lang)
                        SubtitleProvider.ADDONS -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        order(found.distinctBy { it.url }, lang)
    }

    /**
     * A Stremio-protocol subtitle index: id, language, release and encoding.
     *
     * v3 and PRO answer the same shape from the same library, so they share
     * this parser and differ only in [base] and the label recorded on each
     * result -- which is what lets the viewer tell two identical-looking rows
     * apart in the subtitles panel.
     */
    private suspend fun stremioIndex(
        base: String,
        type: String,
        id: String,
        provider: SubtitleProvider
    ): List<SubtitleRef> {
        val raw = http.get(
            "$base/subtitles/$type/${FinderRules.streamSegment(id)}.json",
            headers = mapOf("Accept" to "application/json"),
            attempts = 1,
            limitBytes = MAX_JSON_BYTES
        )
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val items = root["subtitles"] as? JsonArray ?: return emptyList()
        return items.filterIsInstance<JsonObject>().mapNotNull { item ->
            val url = item.str("url")
            if (url.isEmpty() || !HttpClient.isAllowedUrl(url)) return@mapNotNull null
            SubtitleRef(
                id = item.str("id"),
                url = url,
                lang = item.str("lang"),
                source = provider.label,
                release = item.str("subtitleFileName").ifBlank { item.str("movieReleaseName") },
                encoding = item.str("SubEncoding")
            )
        }.take(MAX_RESULTS)
    }

    /**
     * SubSense: the same protocol, but the index behind it is an aggregator.
     *
     * It returns absolute `url` values to the source archives (Subf2m, SubDL,
     * Podnapisi ...) rather than to itself, and it omits `SubEncoding`, so the
     * encoding is left blank and the cache decodes as UTF-8 with a BOM strip.
     * A `download` field, when present, is used for the download count so the
     * best-seeded result sorts first.
     */
    private suspend fun subSense(type: String, id: String): List<SubtitleRef> {
        val raw = http.get(
            "$SUB_SENSE/subtitles/$type/${FinderRules.streamSegment(id)}.json",
            headers = mapOf("Accept" to "application/json"),
            attempts = 1,
            limitBytes = MAX_JSON_BYTES
        )
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val items = root["subtitles"] as? JsonArray ?: return emptyList()
        return items.filterIsInstance<JsonObject>().mapNotNull { item ->
            val url = item.str("url")
            if (url.isEmpty() || !HttpClient.isAllowedUrl(url)) return@mapNotNull null
            SubtitleRef(
                id = item.str("id"),
                url = url,
                lang = item.str("lang").ifBlank { item.str("language") },
                source = SubtitleProvider.SUB_SENSE.label,
                release = item.str("subtitleFileName").ifBlank { item.str("release") },
                encoding = item.str("SubEncoding"),
                downloads = item.str("downloads").toIntOrNull() ?: item.str("download").toIntOrNull() ?: 0
            )
        }.take(MAX_RESULTS)
    }

    /**
     * The legacy REST search. Its download links are gzip archives, so the best
     * few are unpacked into the subtitle cache and handed to the player as
     * ordinary `.srt` files.
     */
    private suspend fun openSubtitlesRest(type: String, id: String, lang: String): List<SubtitleRef> {
        val url = restUrl(type, id, lang) ?: return emptyList()
        val raw = http.get(
            url,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to LEGACY_UA,
                "X-User-Agent" to LEGACY_UA
            ),
            attempts = 1,
            limitBytes = MAX_JSON_BYTES
        )
        val items = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        val refs = items.filterIsInstance<JsonObject>().mapNotNull { item ->
            val link = item.str("SubDownloadLink")
            if (link.isEmpty() || !HttpClient.isAllowedUrl(link)) return@mapNotNull null
            SubtitleRef(
                id = item.str("IDSubtitleFile"),
                url = link,
                lang = item.str("ISO639"),
                source = SubtitleProvider.OPEN_SUBTITLES_REST.label,
                release = item.str("SubFileName"),
                encoding = item.str("SubEncoding"),
                downloads = item.str("SubDownloadsCnt").toIntOrNull() ?: 0
            )
        }.sortedByDescending { it.downloads }.take(MAX_REST)
        return materialise(refs)
    }

    /** `imdbid-111161` or `imdbid-946653/season-1/episode-1`. Null without an IMDb id. */
    internal fun restUrl(type: String, id: String, lang: String = "eng"): String? {
        val parts = id.split(':')
        val imdb = parts.getOrNull(0).orEmpty()
        if (!imdb.startsWith("tt")) return null
        val number = imdb.removePrefix("tt").trimStart('0').ifEmpty { "0" }
        val series = if (type == "series" && parts.size >= 3) {
            "/season-${parts[1].toIntOrNull() ?: 1}/episode-${parts[2].toIntOrNull() ?: 1}"
        } else {
            ""
        }
        return "$OPEN_SUBTITLES_REST/search/imdbid-$number$series/sublanguageid-${legacyLanguage(lang)}"
    }

    /** "en"/"eng"/"English" -> "eng"; the legacy index only speaks ISO 639-2. */
    private fun legacyLanguage(lang: String): String = when (lang.trim().lowercase().substringBefore('-')) {
        "en", "eng", "english" -> "eng"
        "es", "spa", "spanish" -> "spa"
        "fr", "fre", "fra", "french" -> "fre"
        "de", "ger", "deu", "german" -> "ger"
        "pt", "por", "portuguese" -> "por"
        "it", "ita", "italian" -> "ita"
        "ar", "ara", "arabic" -> "ara"
        else -> "eng"
    }

    /** Download, decompress and cache, so Media3 gets a real subtitle file. */
    private suspend fun materialise(refs: List<SubtitleRef>): List<SubtitleRef> = coroutineScope {
        refs.map { ref -> async { cache(ref) } }.awaitAll().filterNotNull()
    }

    private suspend fun cache(ref: SubtitleRef): SubtitleRef? = withContext(Dispatchers.IO) {
        val dir = cacheDir ?: return@withContext null
        val name = "sub-" + (ref.id.ifBlank { ref.url.hashCode().toString() }) + ".srt"
        val file = File(dir, name)
        if (file.exists() && file.length() > 0L) return@withContext ref.copy(url = file.toURI().toString())
        val bytes = download(ref.url) ?: return@withContext null
        val text = gunzip(bytes, ref.encoding) ?: return@withContext null
        // Anything without cue timing is not a subtitle file; a "rate limited"
        // HTML page used to reach the player and show as an empty track.
        if (!text.contains("-->")) return@withContext null
        val written = runCatching {
            dir.mkdirs()
            file.writeText(text)
        }.isSuccess && file.length() > 0L
        if (!written) return@withContext null
        ref.copy(url = file.toURI().toString())
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", LEGACY_UA).build()
        http.okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }.getOrNull()

    /** Unpack the archive and decode it with the charset the index declared. */
    private fun gunzip(bytes: ByteArray, encoding: String = ""): String? {
        val gzipped = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return runCatching {
            val raw = if (gzipped) {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
            } else {
                bytes
            }
            val charset = when (encoding.trim().uppercase()) {
                "CP1252", "WINDOWS-1252" -> Charset.forName("windows-1252")
                "ISO-8859-1", "LATIN1" -> Charsets.ISO_8859_1
                else -> Charsets.UTF_8
            }
            String(raw, charset).removePrefix("\uFEFF")
        }.getOrNull()
    }

    private fun order(refs: List<SubtitleRef>, lang: String): List<SubtitleRef> = refs.sortedWith(
        compareByDescending<SubtitleRef> { it.matchesLanguage(lang) }
            .thenByDescending { it.downloads }
            .thenBy { it.label }
            .thenBy { it.source }
    )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private const val OPEN_SUBTITLES_V3 = "https://opensubtitles-v3.strem.io"
        private const val OPEN_SUBTITLES_PRO = "https://opensubtitlesv3-pro.dexter21767.com"
        private const val SUB_SENSE = "https://subsense.nepiraw.com"
        private const val OPEN_SUBTITLES_REST = "https://rest.opensubtitles.org"
        private const val LEGACY_UA = "TemporaryUserAgent"
        private const val MAX_JSON_BYTES = 3_000_000L
        private const val MAX_RESULTS = 60
        /** Files actually unpacked per title; every one is a download. */
        private const val MAX_REST = 8
    }
}
