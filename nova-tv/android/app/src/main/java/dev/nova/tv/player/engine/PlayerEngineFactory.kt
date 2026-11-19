// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original path:
//   app/src/main/kotlin/com/arflix/tv/ui/screens/player/engine/PlayerEngineFactory.kt
// Changes for Nova are listed in nova-tv/android/THIRD_PARTY_NOTICES.md.
package dev.nova.tv.player.engine

import androidx.media3.exoplayer.ExoPlayer
import dev.nova.tv.player.engine.exoplayer.ExoPlayerEngine
import kotlinx.coroutines.CoroutineScope

object PlayerEngineFactory {
    fun createEngine(
        type: PlayerEngineType,
        exoPlayer: ExoPlayer,
        scope: CoroutineScope
    ): PlayerEngine {
        return when (type) {
            PlayerEngineType.EXOPLAYER -> ExoPlayerEngine(exoPlayer, scope)
            // Nova links no MPV or VLC bindings either; both fall back, as in ARVIO.
            PlayerEngineType.MPV, PlayerEngineType.VLC -> ExoPlayerEngine(exoPlayer, scope)
        }
    }
}
