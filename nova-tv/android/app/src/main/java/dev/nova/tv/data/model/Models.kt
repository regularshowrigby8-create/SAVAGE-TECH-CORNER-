package dev.nova.tv.data.model

import dev.nova.tv.finder.FinderRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    val nzbUrl: String? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val behaviorHints: StreamHints? = null,
    /** Which addon answered with this stream. Set by the repository, never parsed. */
    @Transient val sourceName: String = "",
    @Transient val sourceBase: String = ""
) {
    /**
     * True when Nova can decode this itself. The rule is the source finder's
     * (`FinderRules.classifyStream`), so the detail list, the finder and the
     * player cannot disagree about what is playable.
     *
     * The old rule only accepted URLs ending in .mp4/.m3u8/.mpd/.webm, which
     * dropped every extensionless CDN HLS link and every .mkv debrid file --
     * most real addon streams.
     */
    val isPlayable: Boolean
        get() = FinderRules.classifyStream(this) is FinderRules.Verdict.Playable

    /** "1080p", "2160p", or "" when the addon did not say. */
    val quality: String
        get() = FinderRules.guessQuality(listOfNotNull(name, title, description).joinToString(" "))

    /** Request headers the addon asked for, e.g. Referer / User-Agent. */
    val requestHeaders: Map<String, String>
        get() = behaviorHints?.proxyHeaders?.request.orEmpty()

    /** A one-line label for the source picker. */
    val label: String
        get() = listOfNotNull(
            sourceName.ifBlank { null },
            quality.ifBlank { null },
            (title ?: name)?.lineSequence()?.firstOrNull()?.take(80)
        ).distinct().joinToString("  ·  ").ifBlank { "Stream" }
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val proxyHeaders: ProxyHeaders? = null
)

@Serializable
data class ProxyHeaders(val request: Map<String, String> = emptyMap())

/** One external subtitle file from a `subtitles` addon. */
@Serializable
data class SubtitleRef(
    val id: String = "",
    val url: String = "",
    val lang: String = ""
) {
    /** ISO 639-2 codes from OpenSubtitles ("eng") shown as-is, upper-cased. */
    val label: String get() = lang.ifBlank { "Subtitle" }.uppercase()
}

@Serializable
data class SubtitleResponse(val subtitles: List<SubtitleRef> = emptyList())
