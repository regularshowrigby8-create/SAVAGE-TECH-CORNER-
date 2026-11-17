package dev.nova.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Playback engine.
 *
 * Failure is modelled as data, not an exception (clappr / vidstack / openplayer
 * all do this): a stream that fails reports a reason, and the caller walks the
 * ranked fallback list rather than the app dying on the first bad URL.
 */
class NovaPlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val exo: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    private var lastKnownPosition = 0L
    private var prepared = false

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                lastKnownPosition = exo.currentPosition
                sync()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) _state.value = _state.value.copy(buffering = true)
                if (state == Player.STATE_READY) {
                    _state.value = _state.value.copy(buffering = false, durationMs = exo.duration.coerceAtLeast(0L))
                }
                if (state == Player.STATE_ENDED) _state.value = _state.value.copy(ended = true, playing = false)
                sync()
            }

            override fun onPlayerError(error: PlaybackException) {
                _failure.value = classify(error)
                _state.value = _state.value.copy(error = error.message ?: "playback failed", buffering = false)
            }
        })
        // A single 500 ms position tick drives the scrub bar. One tick, not a
        // timer per control.
        scope.launch {
            while (true) {
                if (prepared) {
                    lastKnownPosition = exo.currentPosition
                    _state.value = _state.value.copy(
                        positionMs = exo.currentPosition,
                        durationMs = exo.duration.coerceAtLeast(0L)
                    )
                }
                delay(500)
            }
        }
    }

    fun prepare(url: String, startPositionMs: Long = 0L, headers: Map<String, String> = emptyMap()) {
        _failure.value = null
        _state.value = _state.value.copy(error = null, ended = false, buffering = true)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (headers.isNotEmpty()) {
                    setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setExtras(android.os.Bundle().apply {
                            headers.forEach { (k, v) -> putString(k, v) }
                        }).build()
                    )
                }
            }
            .build()
        exo.setMediaItem(item, startPositionMs)
        exo.prepare()
        exo.playWhenReady = true
        prepared = true
    }

    fun play() { exo.playWhenReady = true }
    fun pause() { exo.playWhenReady = false }
    fun togglePlay() { if (exo.playWhenReady) pause() else play() }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs.coerceIn(0L, exo.duration.coerceAtLeast(0L)))
        lastKnownPosition = positionMs
    }

    fun seekBy(deltaMs: Long) {
        when (val result = seekBy(exo.currentPosition, deltaMs, exo.duration)) {
            is SeekResult.SeekedTo -> seekTo(result.targetMs)
            is SeekResult.Clamped -> seekTo(result.targetMs)
            is SeekResult.Ignored -> Unit
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        exo.volume = clamped
        _state.value = _state.value.copy(volume = clamped, muted = clamped == 0f)
    }

    fun stepVolume(delta: Float) = setVolume(exo.volume + delta)

    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.25f, 3f)
        exo.setPlaybackSpeed(clamped)
        _state.value = _state.value.copy(playbackRate = clamped)
    }

    fun resumePosition(): Long = lastKnownPosition

    private fun sync() {
        _state.value = _state.value.copy(
            playing = exo.isPlaying,
            ended = exo.playbackState == Player.STATE_ENDED
        )
    }

    fun release() {
        prepared = false
        exo.release()
    }

    /** Turn an engine error into a reason the caller can act on. */
    private fun classify(error: PlaybackException): PlaybackFailure = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            PlaybackFailure("bad-http", "The server refused this stream.", retryable = false)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            PlaybackFailure("network", "Network unreachable.", retryable = true)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            PlaybackFailure("timeout", "The stream timed out.", retryable = true)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            PlaybackFailure("container", "This format cannot be decoded.", retryable = false)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            PlaybackFailure("decoder", "The device decoder rejected this stream.", retryable = false)
        else -> PlaybackFailure("unknown", error.message ?: "Playback failed.", retryable = true)
    }
}

data class PlaybackFailure(
    val reason: String,
    val message: String,
    val retryable: Boolean
)
