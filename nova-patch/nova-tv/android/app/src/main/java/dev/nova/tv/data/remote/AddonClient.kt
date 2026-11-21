package dev.nova.tv.data.remote

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The raw count matters for skip: bad records must not move the cursor backwards. */
data class CatalogBatch(val items: List<MetaPreview>, val consumed: Int)

/** Network boundary, also used by offline repository tests. */
interface AddonApi {
    suspend fun loadAddon(baseUrl: String): NovaAddon
    suspend fun fetchCatalogPage(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String> = emptyMap()): CatalogBatch
    suspend fun fetchMeta(addon: NovaAddon, type: String, id: String): MetaPreview?
    suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream>
    suspend fun fetchSubtitles(addon: NovaAddon, type: String, id: String): List<SubtitleRef>
}

/** Reads Stremio manifests, catalogues, metadata, streams and subtitles. */
class AddonClient(private val http: HttpClient) : AddonApi {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun loadManifest(baseUrl: String): Manifest {
        val url = normalise(baseUrl) + "/manifest.json"
        require(HttpClient.isAllowedUrl(url)) { "That address is not allowed." }
        val raw = http.get(url, attempts = 1, limitBytes = FinderRules.MAX_MANIFEST_BYTES)
        return withContext(Dispatchers.Default) { decodeManifest(raw) }
    }

    /**
     * Stremio permits both "catalog" and {"name":"catalog", ...}. Manifest's
     * resources are ResourceDef objects; converting them all into strings made
     * even Cinemeta's normal manifest fail deserialization. Preserve object
     * types/prefixes and wrap only the string form.
     */
    internal fun decodeManifest(raw: String): Manifest {
        val obj = json.parseToJsonElement(raw).jsonObject
        val resources = (obj["resources"] as? JsonArray).orEmpty().mapNotNull { entry ->
            when (entry) {
                is JsonObject -> entry.takeIf { (it["name"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
                is JsonPrimitive -> entry.contentOrNull?.takeIf { entry.isString && it.isNotBlank() }
                    ?.let { JsonObject(mapOf("name" to JsonPrimitive(it))) }
                else -> null
            }
        }
        val normalized = obj.toMutableMap().apply { put("resources", JsonArray(resources)) }
        return json.decodeFromJsonElement(Manifest.serializer(), JsonObject(normalized))
    }

    override suspend fun loadAddon(baseUrl: String): NovaAddon =
        NovaAddon(baseUrl = normalise(baseUrl), manifest = loadManifest(baseUrl))

    override suspend fun fetchCatalogPage(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String>): CatalogBatch {
        val suffix = if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") {
            "${encodeExtra(it.key)}=${encodeExtra(it.value)}"
        }
        val type = FinderRules.streamSegment(catalog.type)
        val id = FinderRules.streamSegment(catalog.id)
        val url = normalise(addon.baseUrl) + "/catalog/$type/$id$suffix.json"
        try {
            return readCatalog(url, catalog.type)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (primary: Exception) {
            if (!isCinemeta(addon.baseUrl)) throw primary
            // Cinemeta currently redirects here; use the direct route only when
            // the original request failed, not when it legitimately returned [].
            val direct = "$CINEMETA_CATALOGS/$id/catalog/$type/$id$suffix.json"
            try {
                return readCatalog(direct, catalog.type)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (secondary: Exception) {
                throw IllegalStateException("Cinemeta and its catalogue host could not be reached", secondary)
            }
        }
    }

    suspend fun fetchCatalog(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String> = emptyMap()): List<MetaPreview> =
        fetchCatalogPage(addon, catalog, extra).items

    private suspend fun readCatalog(url: String, defaultType: String): CatalogBatch {
        val raw = http.get(url, attempts = 1)
        return withContext(Dispatchers.Default) { decodeCatalog(raw, defaultType) }
    }

    /** One malformed preview cannot discard all other titles in the page. */
    internal fun decodeCatalog(raw: String, defaultType: String): CatalogBatch {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: error("Catalogue response is not an object")
        val rows = root["metas"] as? JsonArray ?: error("Catalogue response has no metas array")
        val items = rows.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            decodePreview(obj, defaultType).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
        }.distinctBy { it.id }
        return CatalogBatch(items, rows.size)
    }

    /** Decode independently: one bad episode or optional field must not lose the title. */
    private fun decodePreview(obj: JsonObject, defaultType: String): MetaPreview {
        val normalized = obj.toMutableMap()
        if ((normalized["id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) {
            (normalized["imdb_id"] as? JsonPrimitive)?.let { normalized["id"] = it }
        }
        if ((normalized["type"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) normalized["type"] = JsonPrimitive(defaultType)
        for (key in listOf("year", "imdbRating", "releaseInfo", "runtime")) {
            val value = normalized[key] as? JsonPrimitive
            if (value != null && value !is JsonNull) normalized[key] = JsonPrimitive(value.content)
        }
        val videos = (normalized.remove("videos") as? JsonArray).orEmpty().mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement(dev.nova.tv.data.model.Video.serializer(), entry) }.getOrNull()
        }
        val preview = runCatching { json.decodeFromJsonElement(MetaPreview.serializer(), JsonObject(normalized)) }
            .getOrElse { minimalPreview(JsonObject(normalized), defaultType) }
        return preview.copy(videos = videos)
    }

    private fun minimalPreview(obj: JsonObject, defaultType: String): MetaPreview {
        fun text(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
        val genreRows = (obj["genres"] as? JsonArray).orEmpty()
        return MetaPreview(
            id = text("id") ?: text("imdb_id").orEmpty(),
            name = text("name").orEmpty(),
            type = text("type") ?: defaultType,
            poster = text("poster"), background = text("background"), logo = text("logo"),
            year = text("year"), releaseInfo = text("releaseInfo"), runtime = text("runtime"),
            description = text("description").orEmpty(), country = text("country"), language = text("language"),
            imdbRating = text("imdbRating"), imdbId = text("imdb_id"), moviedbId = text("moviedb_id")?.toLongOrNull(),
            genres = genreRows.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        )
    }

    private fun isCinemeta(base: String): Boolean =
        runCatching { java.net.URI(normalise(base)).host }.getOrNull()
            ?.equals(CINEMETA_HOST, ignoreCase = true) == true

    override suspend fun fetchMeta(addon: NovaAddon, type: String, id: String): MetaPreview? {
        val url = normalise(addon.baseUrl) + "/meta/${FinderRules.streamSegment(type)}/${FinderRules.streamSegment(id)}.json"
        val raw = try { http.get(url, attempts = 2) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return null }
        return withContext(Dispatchers.Default) {
            runCatching {
                val obj = (json.parseToJsonElement(raw) as? JsonObject)?.get("meta") as? JsonObject
                obj?.let { decodePreview(it, type) }?.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }.getOrNull()
        }
    }

    override suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream> {
        val url = normalise(addon.baseUrl) + "/stream/${FinderRules.streamSegment(type)}/${FinderRules.streamSegment(id)}.json"
        val raw = try { http.get(url, attempts = 2) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return emptyList() }
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val array = obj["streams"] as? JsonArray ?: return emptyList()
            array.mapNotNull { entry ->
                runCatching { json.decodeFromJsonElement(Stream.serializer(), entry.jsonObject) }.getOrNull()
                    ?.copy(sourceName = addon.name, sourceBase = addon.baseUrl)
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun fetchSubtitles(addon: NovaAddon, type: String, id: String): List<SubtitleRef> {
        val url = normalise(addon.baseUrl) + "/subtitles/${FinderRules.streamSegment(type)}/${FinderRules.streamSegment(id)}.json"
        val raw = http.get(url, attempts = 1)
        return json.decodeFromString(dev.nova.tv.data.model.SubtitleResponse.serializer(), raw)
            .subtitles.filter { HttpClient.isAllowedUrl(it.url) }.take(40)
    }

    suspend fun searchCatalog(addon: NovaAddon, catalog: CatalogDef, query: String): List<MetaPreview> =
        fetchCatalog(addon, catalog, mapOf("search" to query.trim()))

    private fun encodeExtra(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun normalise(base: String): String =
        base.trim().trimEnd('/').replace(Regex("""/manifest\.json$""", RegexOption.IGNORE_CASE), "")

    companion object {
        private const val CINEMETA_HOST = "v3-cinemeta.strem.io"
        private const val CINEMETA_CATALOGS = "https://cinemeta-catalogs.strem.io"

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
