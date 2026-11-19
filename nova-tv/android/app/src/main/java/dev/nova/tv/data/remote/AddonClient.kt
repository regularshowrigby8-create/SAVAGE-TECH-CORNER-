package dev.nova.tv.data.remote

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.CatalogResponse
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.finder.FinderRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads the Stremio addon protocol: manifest.json, /catalog/{type}/{id}.json and
 * /stream/{type}/{id}.json.
 *
 * The protocol is lenient in practice — some addons return `resources` as objects
 * and some as bare strings, and `streams` may be absent entirely. Parsing here is
 * defensive so one odd addon cannot break the whole list.
 */
class AddonClient(private val http: HttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun loadManifest(baseUrl: String): Manifest {
        require(HttpClient.isAllowedUrl(normalise(baseUrl) + "/manifest.json")) { "That address is not allowed." }
        return parseManifest(http.get(normalise(baseUrl) + "/manifest.json"))
    }

    suspend fun loadAddon(baseUrl: String): NovaAddon =
        NovaAddon(baseUrl = normalise(baseUrl), manifest = loadManifest(baseUrl))

    suspend fun fetchCatalog(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String> = emptyMap()): List<MetaPreview> {
        val suffix = if (extra.isEmpty()) "" else {
            "/" + extra.entries.joinToString("&") { "${it.key}=${encodeExtra(it.value)}" }
        }
        val url = normalise(addon.baseUrl) + "/catalog/${catalog.type}/${catalog.id}$suffix.json"
        val raw = http.get(url)
        return runCatching {
            json.decodeFromString(CatalogResponse.serializer(), raw).metas.map { preferImdbId(it) }
        }.getOrElse { emptyList() }
    }

    /** `/meta/{type}/{id}.json`: full details, including the episode list. */
    suspend fun fetchMeta(addon: NovaAddon, type: String, id: String): MetaPreview? {
        val url = normalise(addon.baseUrl) + "/meta/$type/${FinderRules.streamSegment(id)}.json"
        val raw = runCatching { http.get(url, attempts = 2) }.getOrElse { return null }
        return runCatching { json.decodeFromString(dev.nova.tv.data.model.MetaResponse.serializer(), raw).meta }.getOrNull()
    }

    suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream> {
        // The id goes through streamSegment: `tt0903747:1:2` must keep its
        // colons, but anything else odd in an id is still encoded.
        val url = normalise(addon.baseUrl) + "/stream/$type/${FinderRules.streamSegment(id)}.json"
        val raw = runCatching { http.get(url, attempts = 2) }.getOrElse { return emptyList() }
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val array = obj["streams"] as? JsonArray ?: return emptyList()
            array.mapNotNull { entry ->
                runCatching { json.decodeFromJsonElement(Stream.serializer(), entry.jsonObject) }.getOrNull()
                    ?.copy(sourceName = addon.name, sourceBase = addon.baseUrl)
            }
        }.getOrElse { emptyList() }
    }

    suspend fun fetchSubtitles(addon: NovaAddon, type: String, id: String): List<dev.nova.tv.data.model.SubtitleRef> {
        val url = normalise(addon.baseUrl) + "/subtitles/$type/${FinderRules.streamSegment(id)}.json"
        val raw = http.get(url, attempts = 1)
        return json.decodeFromString(dev.nova.tv.data.model.SubtitleResponse.serializer(), raw)
            .subtitles.filter { HttpClient.isAllowedUrl(it.url) }
            .take(40)
    }

    /**
     * Server-side search, for addons whose catalogue declares a `search`
     * extra. Before this, Search could only filter the first page of each
     * catalogue already in memory.
     */
    suspend fun searchCatalog(addon: NovaAddon, catalog: CatalogDef, query: String): List<MetaPreview> {
        return fetchCatalog(addon, catalog, mapOf("search" to query.trim()))
    }

    /** Extra values are path segments: "Sci-Fi" stays, "a b" becomes "a%20b". */
    private fun encodeExtra(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun normalise(base: String): String =
        base.trim().trimEnd('/').replace(Regex("""/manifest\.json$""", RegexOption.IGNORE_CASE), "")

    companion object {
        private val manifestJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Parse a manifest. `resources` is either `["catalog","stream"]` or
         * `[{"name":"stream","idPrefixes":["tt"]}]`, and both forms are common.
         *
         * Bare strings are wrapped as `{"name": ...}` so they decode into
         * [dev.nova.tv.data.model.ResourceDef]. They used to be flattened to
         * strings instead, which cannot decode into an object: every manifest
         * that used the string form -- Cinemeta included -- threw here, no
         * addon was installed, and the home screen stayed empty. Object
         * entries are kept whole so their per-resource idPrefixes survive.
         */
        internal fun parseManifest(raw: String): Manifest {
            val obj = manifestJson.parseToJsonElement(raw).jsonObject
            val resources = (obj["resources"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { entry ->
                when (entry) {
                    is JsonObject -> entry.takeIf { (it["name"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
                    is JsonPrimitive -> entry.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { JsonObject(mapOf("name" to JsonPrimitive(it))) }
                    else -> null
                }
            }
            val withResources = obj.toMutableMap().apply { put("resources", JsonArray(resources)) }
            return manifestJson.decodeFromJsonElement(Manifest.serializer(), JsonObject(withResources))
        }

        /**
         * TMDB catalogues name titles `tmdb:123` but also carry the IMDb id.
         * Stream addons and Cinemeta are keyed by IMDb ids, so a TMDB row is
         * switched to `tt...` when one is given; otherwise its titles would
         * open without episodes or sources.
         */
        internal fun preferImdbId(meta: MetaPreview): MetaPreview {
            val imdb = meta.imdbId?.trim().orEmpty()
            return if (meta.id.startsWith("tmdb:", ignoreCase = true) && imdb.startsWith("tt")) meta.copy(id = imdb) else meta
        }

        /**
         * Rank streams: playable only, best quality first, and one entry per
         * request (same URL + headers) so two addons mirroring a file do not
         * show the same link twice.
         */
        fun rank(streams: List<Stream>): List<Stream> =
            streams.filter { it.isPlayable }
                .distinctBy { it.url.orEmpty().trim() to it.requestHeaders }
                .sortedWith(
                    compareByDescending<Stream> { FinderRules.qualityRank(it.quality) }
                        .thenByDescending { if (it.requestHeaders.isEmpty()) 1 else 0 }
                        .thenByDescending { if ((it.title ?: it.name).isNullOrBlank()) 0 else 1 }
                )
    }
}
