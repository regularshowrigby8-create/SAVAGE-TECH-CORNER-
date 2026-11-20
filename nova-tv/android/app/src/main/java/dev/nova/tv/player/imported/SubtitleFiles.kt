// Imported from JustPlayer (https://github.com/moneytoo/Player, commit 7a1c9c4),
// released under the Unlicense (public domain).
// Original path: app/src/main/java/com/brouken/player/SubtitleUtils.java
// Adapted from Java to Kotlin and reduced to the parts Nova uses. The MIME
// table, the filename-to-language rule and the SubtitleConfiguration builder
// are the upstream logic unchanged; the DocumentFile tree walking was dropped
// because Nova has no SAF tree to walk.
package dev.nova.tv.player.imported

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * Everything needed to turn "a subtitle the viewer picked" into something
 * ExoPlayer will render.
 *
 * Nova's old player could only show the tracks the stream already carried, so
 * a film with missing or unreadable captions was unwatchable with subs. This is
 * the missing half: pick a file, work out what it is, and hand it to the player
 * as a real subtitle track.
 */
object SubtitleFiles {

    /** Extensions ExoPlayer can decode, in the order the picker should offer them. */
    val EXTENSIONS = listOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "xml", "sub")

    val MIME_TYPES = listOf(
        "application/x-subrip",
        "text/vtt",
        "text/x-ssa",
        "application/ttml+xml",
        "text/plain",
        "application/octet-stream"
    )

    /** JustPlayer's table, unchanged: extension decides the parser. */
    fun mimeOf(path: String): String {
        val p = path.substringBefore('?').lowercase()
        return when {
            p.endsWith(".ssa") || p.endsWith(".ass") -> MimeTypes.TEXT_SSA
            p.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            p.endsWith(".ttml") || p.endsWith(".dfxp") || p.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * `Movie.2024.1080p.eng.srt` -> `eng`.
     *
     * Upstream only looks at the last dot-separated token of a `.srt` name and
     * accepts it when it is 2..6 letters. That is the convention every release
     * group uses, and it is why a side-loaded file still shows a language
     * instead of "Track 1".
     */
    fun languageOf(path: String): String? {
        val name = path.substringAfterLast('/').substringBefore('?').lowercase()
        if (!name.endsWith(".srt")) return null
        val stem = name.removeSuffix(".srt")
        val token = stem.substringAfterLast('.', "")
        return token.takeIf { it.length in 2..6 && it.all { c -> c.isLetter() } }
    }

    fun isSubtitleFile(name: String): Boolean {
        val lower = name.lowercase()
        return EXTENSIONS.any { lower.endsWith(".$it") }
    }

    /** A human name for a picked file, without the extension. */
    fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "Subtitle"
        return raw.substringBeforeLast('.', raw).ifBlank { "Subtitle" }
    }

    /**
     * Build the configuration ExoPlayer needs. [selected] marks it default so
     * the track turns on the moment it is added, which is what the viewer
     * expects after picking a file.
     */
    fun configuration(
        uri: Uri,
        label: String?,
        language: String? = languageOf(uri.toString()),
        selected: Boolean = true
    ): MediaItem.SubtitleConfiguration {
        val builder = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeOf(uri.toString()))
            .setLanguage(language)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .setLabel(label)
        if (selected) builder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        return builder.build()
    }

    /**
     * JustPlayer's `normalizeFontScale`, kept because it encodes the BBC
     * subtitle guideline: Android's own font scale can be 0.85 or 2.0, and
     * feeding that straight into the caption renderer produces either
     * unreadable or screen-filling text.
     */
    fun normalizeFontScale(fontScale: Float, small: Boolean): Float = when {
        fontScale > 1.01f -> if (fontScale >= 1.99f) (if (small) 1.15f else 1.2f) else (if (small) 1.0f else 1.1f)
        fontScale < 0.99f -> if (fontScale <= 0.26f) (if (small) 0.65f else 0.8f) else (if (small) 0.75f else 0.9f)
        else -> if (small) 0.85f else 1.0f
    }
}
