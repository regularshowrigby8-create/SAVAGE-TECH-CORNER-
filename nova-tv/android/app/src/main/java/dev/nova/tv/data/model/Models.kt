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
    /** The IMDb id, from catalogues that key titles another way (TMDB's `tmdb:123`). */
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("trailerStreams") val trailerStreams: List<Trailer> = emptyList(),
    /** "139 min". Used to estimate a file's bitrate from its size. */
    val runtime: String? = null,
    val releaseInfo: String? = null,
    /** "Spain, Argentina": production countries, first is the main one (Dual Audio). */
    val country: String? = null,
    /** "Spanish, English": full metadata only; first is the original language. */
    val language: String? = null,
    /** Episodes, from the `meta` resource only (catalogue previews omit it). */
    val videos: List<Video> = emptyList()
) {
    val rating: Double get() = imdbRating?.toDoubleOrNull() ?: 0.0
    val isSeries: Boolean get() = type == "series" || type == "tv"

    /** Runtime in seconds, or 0 when the catalogue did not say. */
    val runtimeSeconds: Int
        get() = Regex("""(\d{1,4})\s*min""").find(runtime.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.times(60) ?: 0

    /** Seasons that have at least one episode, specials (season 0) last. */
    val seasons: List<Int>
        get() = videos.map { it.season }.distinct().sortedWith(compareBy({ it == 0 }, { it }))

    fun episodes(season: Int): List<Video> = videos.filter { it.season == season }.sortedBy { it.episode }

    /** One short line: "2008 · 49 min" -- the only metadata a browse card shows. */
    val shortInfo: String
        get() = listOfNotNull(
            (releaseInfo ?: year)?.takeIf { it.isNotBlank() },
            runtime?.takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
}

/** One episode of a series (`meta.videos[]`). */
@Serializable
data class Video(
    val id: String = "",
    val name: String? = null,
    val title: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val number: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null
) {
    val label: String get() = (name ?: title)?.takeIf { it.isNotBlank() } ?: "Episode $episode"
}

@Serializable
data class MetaResponse(val meta: MetaPreview? = null)

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

    /**
     * File size in bytes, or 0 when unknown: the addon's `videoSize`, else the
     * "4.2 GB" most addons print in the title (ARVIO parseSizeToBytes).
     */
    val sizeBytes: Long
        get() = behaviorHints?.videoSize?.takeIf { it > 0 }
            ?: FinderRules.parseSizeBytes(listOfNotNull(title, name, description, behaviorHints?.filename).joinToString(" "))

    /** Request headers the addon asked for, e.g. Referer / User-Agent. */
    val requestHeaders: Map<String, String>
        get() = behaviorHints?.proxyHeaders?.request.orEmpty()

    /** A one-line label for the source picker: "Source · 1080p · 2.1 GB". */
    val label: String
        get() = listOfNotNull(
            sourceName.ifBlank { null } ?: name?.lineSequence()?.firstOrNull()?.take(32),
            quality.ifBlank { null },
            "Dual audio".takeIf { dev.nova.tv.finder.DualAudio.hasEnglishPlusOther(this) },
            FinderRules.formatSize(sizeBytes).ifBlank { null }
        ).distinct().joinToString("  ·  ").ifBlank { "Stream" }
}

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val proxyHeaders: ProxyHeaders? = null,
    /** Bytes, when the addon knows (Stremio SDK `behaviorHints.videoSize`). */
    val videoSize: Long? = null,
    val filename: String? = null,
    /** Same group = same source family; autoplay-next prefers it (WuPlay / ARVIO). */
    val bingeGroup: String? = null
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
