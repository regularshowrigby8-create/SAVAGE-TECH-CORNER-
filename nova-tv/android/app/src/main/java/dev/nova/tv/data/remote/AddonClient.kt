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
        val raw = http.get(normalise(baseUrl) + "/manifest.json")
        val element = json.parseToJsonElement(raw)
        val obj = element.jsonObject
        // `resources` is either ["catalog","stream"] or [{"name":"stream"}].
        val resources: List<String> = (obj["resources"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { entry ->
                runCatching { entry.jsonObject["name"]?.toString()?.trim('"') }.getOrNull()
                    ?: runCatching { entry.toString().trim('"') }.getOrNull()
            }
        val withResources = obj.toMutableMap().apply {
            put(
                "resources",
                JsonArray(resources.map { kotlinx.serialization.json.JsonPrimitive(it) })
            )
        }
        return json.decodeFromJsonElement(Manifest.serializer(), JsonObject(withResources))
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
            json.decodeFromString(CatalogResponse.serializer(), raw).metas
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
