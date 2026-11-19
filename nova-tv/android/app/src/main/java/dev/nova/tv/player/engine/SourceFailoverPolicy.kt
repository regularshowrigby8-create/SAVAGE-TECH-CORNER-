// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original path:
//   app/src/main/kotlin/com/arflix/tv/ui/screens/player/SourceFailoverPolicy.kt
// Changes for Nova are listed in nova-tv/android/THIRD_PARTY_NOTICES.md.
package dev.nova.tv.player.engine

import dev.nova.tv.data.model.Stream
import java.util.Locale

internal data class PlaybackRequestIdentity(val url: String, val headers: Map<String, String>) {
    // Identities are for in-memory comparison, never diagnostics: headers may contain credentials.
    override fun toString(): String = "PlaybackRequestIdentity(redacted)"
}

private fun isSafePlaybackHeader(name: String, value: String): Boolean =
    name.isNotBlank() && value.isNotBlank() &&
        name.all { ch -> ch.code in 33..126 &&
            ch !in setOf('(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}') } &&
        value.all { ch -> ch == '\t' || ch.code in 32..126 }

internal fun Map<String, String>.safePlaybackHeaders(): Map<String, String> =
    filter { (name, value) -> isSafePlaybackHeader(name.trim(), value.trim()) }
        .mapKeys { (name, _) -> name.trim() }
        .mapValues { (_, value) -> value.trim() }

internal fun playbackRequestIdentity(
    stream: Stream?,
    playbackUrl: String? = stream?.url
): PlaybackRequestIdentity? {
    val url = playbackUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val headers = stream?.requestHeaders.orEmpty().safePlaybackHeaders()
        .mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }
    return PlaybackRequestIdentity(url, headers)
}

internal fun canTryFailoverSource(
    candidate: Stream,
    playingStream: Stream?,
    playingUrl: String?,
    placeholders: Set<PlaybackRequestIdentity>
): Boolean {
    val request = playbackRequestIdentity(candidate) ?: return false
    return request != playbackRequestIdentity(playingStream, playingUrl) && request !in placeholders
}

internal fun playingRequestIndex(streams: List<Stream>, playing: Stream?, url: String?): Int {
    val request = playbackRequestIdentity(playing, url) ?: return -1
    return streams.indexOfFirst { playbackRequestIdentity(it) == request }
}

/**
 * Nova addition: the next index worth trying after [failedIndex], or -1.
 *
 * Walks forward then wraps, skipping anything already tried and anything that
 * is the same request as the one that just failed (two addons mirroring one
 * dead URL must not count as two attempts).
 */
internal fun nextFailoverIndex(
    streams: List<Stream>,
    failedIndex: Int,
    tried: Set<Int>
): Int {
    if (streams.isEmpty()) return -1
    val failed = streams.getOrNull(failedIndex)
    val failedIdentities = tried.mapNotNull { playbackRequestIdentity(streams.getOrNull(it)) }.toSet()
    for (step in 1 until streams.size) {
        val index = (failedIndex + step).mod(streams.size)
        if (index in tried) continue
        val candidate = streams[index]
        if (canTryFailoverSource(candidate, failed, failed?.url, failedIdentities)) return index
    }
    return -1
}
