// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original path:
//   app/src/main/kotlin/com/arflix/tv/ui/screens/player/engine/PlayerEngine.kt
// Changes for Nova are listed in nova-tv/android/THIRD_PARTY_NOTICES.md.
package dev.nova.tv.player.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-agnostic interface for media playback (ExoPlayer, MPV, VLC, etc.).
 * UI layers (Mobile & TV) interact purely with this abstraction.
 */
interface PlayerEngine {
    val engineType: PlayerEngineType
    val state: StateFlow<PlayerEngineState>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long) {
        val current = state.value.currentPositionMs
        val duration = state.value.durationMs
        val target = if (duration > 0L) (current + deltaMs).coerceIn(0L, duration) else (current + deltaMs).coerceAtLeast(0L)
        seekTo(target)
    }
    fun setPlaybackSpeed(speed: Float)
    fun setResizeMode(mode: ResizeMode)
    fun cycleResizeMode() {
        val next = when (state.value.resizeMode) {
            ResizeMode.AUTO -> ResizeMode.FIT
            ResizeMode.FIT -> ResizeMode.STRETCH
            ResizeMode.STRETCH -> ResizeMode.CROP
            ResizeMode.CROP -> ResizeMode.AUTO
        }
        setResizeMode(next)
    }
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(track: SubtitleTrackItem?)
    fun setVolume(volume: Float)
    fun release()
}
