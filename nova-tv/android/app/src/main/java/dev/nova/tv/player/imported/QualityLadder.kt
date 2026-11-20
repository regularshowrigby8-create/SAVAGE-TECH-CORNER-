// Imported from DKVideoPlayer (https://github.com/Doikki/DKVideoPlayer,
// commit 4d1f3a2), Apache License 2.0.
// Original paths:
//   dkplayer-sample/src/main/java/xyz/doikki/dkplayer/widget/component/DefinitionControlView.java
//   dkplayer-java/src/main/java/xyz/doikki/videoplayer/controller/GestureVideoController.java
//
// The upstream class is a PopupWindow of TextViews keyed by a
// LinkedHashMap<String, String> of "720P" -> url. Nova keeps the data model and
// the ordering rule (highest first, current one ticked) and drops the Android
// view plumbing, because Nova's chrome is Compose.
package dev.nova.tv.player.imported

import dev.nova.tv.data.model.Stream
import dev.nova.tv.finder.FinderRules

/**
 * A quality rung the viewer can switch to.
 *
 * [streamIndex] is the index in the player's own source queue, so picking a
 * rung is a source switch with the position preserved — the same mechanism the
 * Sources panel uses, which is why this costs no new playback code.
 */
data class QualityRung(
    val label: String,
    val detail: String?,
    val streamIndex: Int,
    val selected: Boolean
)

object QualityLadder {

    /**
     * DKVideoPlayer reverses the map before showing it so the best quality is
     * at the top. Nova does the same, but sorts on the parsed height rather
     * than insertion order, because addon streams arrive in no order at all.
     */
    fun rungs(streams: List<Stream>, currentIndex: Int): List<QualityRung> {
        if (streams.isEmpty()) return emptyList()
        val keyed = streams.mapIndexed { index, stream -> Triple(index, stream, heightOf(stream)) }
        val sorted = keyed.sortedWith(
            compareByDescending<Triple<Int, Stream, Int>> { it.third }
                .thenByDescending { it.second.sizeBytes }
                .thenBy { it.first }
        )
        return sorted.map { (index, stream, height) ->
            QualityRung(
                label = if (height > 0) "${height}p" else "Auto",
                detail = listOfNotNull(
                    stream.sourceName.ifBlank { null },
                    stream.quality.ifBlank { null },
                    stream.sizeBytes.takeIf { it > 0 }?.let { FinderRules.formatSize(it) }
                ).distinct().joinToString("  ·  ").ifBlank { null },
                streamIndex = index,
                selected = index == currentIndex
            )
        }
    }

    /** The height a stream advertises, or 0 when it does not say. */
    fun heightOf(stream: Stream): Int {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ")
        val explicit = Regex("(\\d{3,4})[pP]").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (explicit != null) return explicit
        return when {
            Regex("\\b4k\\b|\\buhd\\b|2160", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 2160
            Regex("\\b1080", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 1080
            Regex("\\b720", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 720
            Regex("\\b480", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 480
            Regex("\\b360", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 360
            else -> 0
        }
    }

    /** Presets for the in-player cap, mirroring DKVideoPlayer's rate list. */
    val CAPS = listOf(0 to "Auto", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p")

    fun labelForCap(cap: Int): String = CAPS.firstOrNull { it.first == cap }?.second ?: "Auto"
}
