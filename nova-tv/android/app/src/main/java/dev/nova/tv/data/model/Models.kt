package dev.nova.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Stremio addon protocol is what Stremio, WuPlay, Nuvio and ARVIO all speak,
 * so it is the contract Nova is built against rather than any one vendor's API.
 * Every protocol field is optional, so every field here carries a default: a
 * malformed feed must not be able to crash the app.
 */

@Serializable
data class Manifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val logo: String? = null,
    val catalogs: List<CatalogDef> = emptyList(),
    val resources: List<ResourceDef> = emptyList(),
    val types: List<String> = listOf("movie", "series"),
    @SerialName("idPrefixes") val idPrefixes: List<String>? = null,
    val behaviorHints: BehaviorHints? = null
)

@Serializable
data class BehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false
)

@Serializable
data class CatalogDef(
    val type: String = "movie",
    val id: String = "",
    val name: String = "",
    val extra: List<ExtraDef> = emptyList()
)

@Serializable
data class ExtraDef(
    val name: String = "",
    @SerialName("isRequired") val isRequired: Boolean = false,
    val options: List<String> = emptyList()
)

@Serializable
data class ResourceDef(
    val name: String = "",
    val types: List<String> = emptyList(),
    @SerialName("idPrefixes") val idPrefixes: List<String>? = null
)

@Serializable
data class MetaPreview(
    val id: String = "",
    val type: String = "movie",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val year: String? = null,
    val description: String = "",
    val genres: List<String> = emptyList(),
    @SerialName("imdbRating") val imdbRating: String? = null,
    @SerialName("moviedb_id") val moviedbId: Long? = null,
    @SerialName("trailerStreams") val trailerStreams: List<Trailer> = emptyList()
) {
    val rating: Double get() = imdbRating?.toDoubleOrNull() ?: 0.0
    val isSeries: Boolean get() = type == "series" || type == "tv"
}

@Serializable
data class Trailer(val ytId: String = "", val title: String = "")

@Serializable
data class CatalogResponse(
    val metas: List<MetaPreview> = emptyList()
)

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val behaviorHints: StreamHints? = null
) {
    /**
     * True when Nova can decode this itself. A page embed is not a stream, and
     * framing someone else's web player is exactly what this app does not do.
     */
    val isPlayable: Boolean
        get() {
            val target = url ?: return false
            if (target.isBlank()) return false
            val lowered = target.lowercase()
            return lowered.endsWith(".mp4") || lowered.endsWith(".m3u8") ||
                lowered.endsWith(".mpd") || lowered.endsWith(".webm") ||
                lowered.contains("format=mp4") || lowered.contains("type=video")
        }
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val proxyHeaders: ProxyHeaders? = null
)

@Serializable
data class ProxyHeaders(val request: Map<String, String> = emptyMap())
