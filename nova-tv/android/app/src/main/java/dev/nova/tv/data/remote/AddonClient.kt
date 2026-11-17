package dev.nova.tv.data.remote

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.CatalogResponse
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
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
            "/" + extra.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
        val url = normalise(addon.baseUrl) + "/catalog/${catalog.type}/${catalog.id}$suffix.json"
        val raw = http.get(url)
        return runCatching {
            json.decodeFromString(CatalogResponse.serializer(), raw).metas
        }.getOrElse { emptyList() }
    }

    suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream> {
        val url = normalise(addon.baseUrl) + "/stream/$type/$id.json"
        val raw = runCatching { http.get(url) }.getOrElse { return emptyList() }
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val array = obj["streams"] as? JsonArray ?: return emptyList()
            array.mapNotNull { entry ->
                runCatching { json.decodeFromJsonElement(Stream.serializer(), entry.jsonObject) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun normalise(base: String): String = base.trim().trimEnd('/')

    companion object {
        /** Rank streams so direct, playable files come first and dead entries last. */
        fun rank(streams: List<Stream>): List<Stream> =
            streams.filter { it.isPlayable }
                .sortedByDescending { stream ->
                    val source = stream.title ?: stream.name ?: ""
                    when {
                        source.contains("1080", true) -> 4
                        source.contains("720", true) -> 3
                        source.contains("480", true) -> 2
                        source.isNotBlank() -> 1
                        else -> 0
                    }
                }
    }
}
