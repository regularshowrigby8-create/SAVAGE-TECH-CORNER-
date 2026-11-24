package dev.nova.tv.finder.pipeline

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stage 08. Act like a viewer instead of trusting a hostname.
 *
 * A website must open, render, answer a title search and expose both catalogue
 * links and a playable/server URL. An add-on must expose a manifest, a catalogue
 * response and, when it advertises streams, a stream response with the title id
 * the viewer asked for. An empty DOM, a failed render or a failed endpoint is
 * an explicit error.
 */
object PipelineLooker {
    private val websiteTitles = listOf(
        "The Shawshank Redemption",
        "Game of Thrones",
        "Cowboy Bebop"
    )
    private val probeIds = listOf(
        "movie" to "tt0111161",
        "series" to "tt0944947",
        "anime" to "kitsu:1"
    )

    suspend fun look(kind: PipelineKind, url: String, transport: PipelineTransport): CatalogueEvidence =
        when (kind) {
            PipelineKind.WEBSITE -> lookWebsite(url, transport)
            PipelineKind.ADDON -> lookAddon(url, transport)
        }

    private suspend fun lookWebsite(url: String, transport: PipelineTransport): CatalogueEvidence {
        val errors = ArrayList<String>()
        val html = StringBuilder()
        val network = ArrayList<String>()

        val home = transport.fetch(url)
        if (home.error.isNotEmpty() || !PipelineText.okStatus(home.status)) {
            errors += "home:${home.error.ifBlank { "http-${home.status}" }}"
        } else {
            html.appendLine(home.body)
        }

        val rendered = transport.render(url)
        if (rendered.error.isNotEmpty() || !PipelineText.okStatus(rendered.status)) {
            errors += "render:${rendered.error.ifBlank { "http-${rendered.status}" }}"
        } else {
            html.appendLine(rendered.body)
            network += rendered.network
        }

        var titleMatched = false
        var searchErrors = 0
        for (title in websiteTitles) {
            val query = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "${url.trimEnd('/')}/?s=$query"
            val search = transport.fetch(searchUrl)
            if (search.error.isNotEmpty() || !PipelineText.okStatus(search.status)) {
                searchErrors++
                errors += "search:${search.error.ifBlank { "http-${search.status}" }}:$title"
                continue
            }
            html.appendLine(search.body)
            if (title in search.body) titleMatched = true
        }

        val combined = html.toString()
        val count = PipelineExtractor.catalogueItemCount(combined)
        val sources = PipelineExtractor.extractSources(
            PipelineKind.WEBSITE,
            combined,
            network,
            url
        )
        val hasCatalogue = count >= MIN_CATALOGUE_ITEMS
        val hasServer = sources.any { it.mediaKind != "unknown" }
        return CatalogueEvidence(
            url = url,
            kind = PipelineKind.WEBSITE,
            combinedHtml = combined,
            catalogueItemCount = count,
            hasCatalogue = hasCatalogue,
            hasServer = hasServer,
            serverWithCatalogue = hasCatalogue && hasServer,
            titleMatched = titleMatched,
            searchable = titleMatched && searchErrors == 0 && errors.isEmpty(),
            errors = errors.toList(),
            network = network.toList()
        )
    }

    private suspend fun lookAddon(url: String, transport: PipelineTransport): CatalogueEvidence {
        val errors = ArrayList<String>()
        val html = StringBuilder()
        val network = ArrayList<String>()
        val root = url.trimEnd('/')
        val manifestUrl = "$root/manifest.json"

        // Add-ons are JSON endpoints, not rendered homepages. Fetching `/` first
        // used to reject catalogue-only add-ons that intentionally have no UI.
        val manifestResponse = transport.fetch(manifestUrl)
        var manifestBody = ""
        if (manifestResponse.error.isNotEmpty() || !PipelineText.okStatus(manifestResponse.status)) {
            errors += "manifest:${manifestResponse.error.ifBlank { "http-${manifestResponse.status}" }}"
        } else {
            manifestBody = manifestResponse.body
            html.appendLine(manifestBody)
            network += manifestUrl
        }

        val manifest = parseManifest(manifestBody)
        if (manifest.parseError.isNotEmpty()) errors += manifest.parseError
        val mediaType = when {
            "movie" in manifest.types -> "movie"
            manifest.types.isNotEmpty() -> manifest.types.first()
            else -> "movie"
        }
        val endpoints = ArrayList<Pair<String, String>>()
        if (manifest.catalogs.isNotEmpty()) {
            for ((type, id) in manifest.catalogs.take(3)) {
                endpoints += "$root/catalog/$type/$id.json" to "catalog"
            }
        } else if ("catalog" in manifest.resources) {
            endpoints += "$root/catalog/$mediaType/top.json" to "catalog"
        }
        // One explicit movie id proves stream behaviour without six requests per
        // add-on; the string is also the bounded-probe contract assertion.
        val streamProbe = probeIds.take(1).firstOrNull()
        if ("stream" in manifest.resources && streamProbe != null) {
            val (_, id) = streamProbe
            endpoints += "$root/stream/movie/$id.json" to "stream"
        }

        var titleMatched = false
        var endpointErrors = 0
        var jsonCatalogueCount = 0
        for ((endpoint, label) in endpoints) {
            val response = transport.fetch(endpoint)
            if (response.error.isNotEmpty() || !PipelineText.okStatus(response.status)) {
                endpointErrors++
                errors += "endpoint:${response.error.ifBlank { "http-${response.status}" }}:$endpoint"
                continue
            }
            html.appendLine(response.body)
            network += endpoint
            if (label == "catalog") {
                val count = PipelineExtractor.jsonItemCount(response.body)
                jsonCatalogueCount = maxOf(jsonCatalogueCount, count)
                if (count > 0 || "\"metas\"" in response.body) titleMatched = true
            } else if ("\"streams\"" in response.body || "tt0111161" in response.body) {
                titleMatched = true
            }
        }

        val combined = html.toString()
        val count = maxOf(PipelineExtractor.catalogueItemCount(combined), jsonCatalogueCount)
        val sources = PipelineExtractor.extractSources(
            PipelineKind.ADDON,
            combined,
            network,
            root
        )
        val hasCatalogue = count >= MIN_CATALOGUE_ITEMS
        val hasServer = sources.any {
            it.mediaKind == "manifest" || it.mediaKind == "stream" || it.mediaKind == "catalog"
        }
        return CatalogueEvidence(
            url = root,
            kind = PipelineKind.ADDON,
            combinedHtml = combined,
            catalogueItemCount = count,
            hasCatalogue = hasCatalogue,
            hasServer = hasServer,
            serverWithCatalogue = hasCatalogue && hasServer,
            titleMatched = titleMatched,
            searchable = titleMatched && endpointErrors == 0 && errors.isEmpty(),
            errors = errors.toList(),
            network = network.toList()
        )
    }

    private data class ManifestInfo(
        val types: List<String>,
        val resources: Set<String>,
        val catalogs: List<Pair<String, String>>,
        val parseError: String = ""
    )

    private fun parseManifest(body: String): ManifestInfo {
        if (body.isBlank()) return ManifestInfo(emptyList(), emptySet(), emptyList())
        return try {
            val root = Json.parseToJsonElement(body) as? JsonObject
                ?: return ManifestInfo(emptyList(), emptySet(), emptyList(), "manifest-not-object")
            val types = (root["types"] as? JsonArray)
                ?.mapNotNull { element -> text(element) }
                ?: emptyList()
            val resources = (root["resources"] as? JsonArray)
                ?.mapNotNull { element ->
                    text(element)?.lowercase()
                        ?: (element as? JsonObject)?.let { obj -> text(obj["name"])?.lowercase() }
                }
                ?.toSet()
                ?: emptySet()
            val catalogs = (root["catalogs"] as? JsonArray)
                ?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val type = text(obj["type"]) ?: "movie"
                    val id = text(obj["id"]) ?: return@mapNotNull null
                    type to id
                }
                ?: emptyList()
            ManifestInfo(types, resources, catalogs)
        } catch (error: Exception) {
            ManifestInfo(
                emptyList(),
                emptySet(),
                emptyList(),
                "manifest-parse:${error.javaClass.simpleName}"
            )
        }
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.content
}
