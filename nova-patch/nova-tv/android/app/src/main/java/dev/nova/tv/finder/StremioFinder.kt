package dev.nova.tv.finder

import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Stremio discovery uses the same six distinct feeds as the fast finder,
 * but follows more directory pages and declared addon-catalogue links. It shares
 * its request gates with the other finder when constructed by NovaApplication.
 * An index or a manifest is not evidence that a stream plays.
 */
class StremioFinder(http: HttpClient, private val discovery: SourceFinder = SourceFinder(http, AddonClient(http))) {
    suspend fun find(exclude: Set<String> = emptySet()): FinderReport {
        val report = discovery.find(maxPerSource = 24, exclude = exclude, deep = true)
        return report.copy(candidates = report.candidates.map { candidate ->
            candidate.copy(from = candidate.from.replaceFirst("Server Finder", "Stremio"))
        })
    }

    companion object {
        const val DIRECTORY_PAGES = 5
        const val GRAPH_DEPTH = 2

        /** Only follow catalogue resources and IDs explicitly declared by a manifest. */
        fun addonCatalogEndpoints(entry: JsonObject): List<String> {
            val manifest = entry["manifest"] as? JsonObject ?: return emptyList()
            val resources = (manifest["resources"] as? JsonArray).orEmpty().mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> (it["name"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }
            if ("addon_catalog" !in resources) return emptyList()
            val transport = (entry["transportUrl"] as? JsonPrimitive)?.contentOrNull
                ?: (entry["manifestUrl"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
            val base = FinderRules.baseFromManifestUrl(transport)
            if (base.isEmpty()) return emptyList()
            val catalogs = (manifest["addonCatalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            return catalogs.mapNotNull { c ->
                val type = (c["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (!Regex("^[A-Za-z0-9_.-]{1,60}$").matches(type) || !Regex("^[A-Za-z0-9_.-]{1,60}$").matches(id)) return@mapNotNull null
                "$base/addon_catalog/$type/$id.json"
            }.take(4)
        }
    }
}
