@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.player.engine.PlayerEngine
import dev.nova.tv.player.engine.PlayerEngineFactory
import dev.nova.tv.player.engine.PlayerEngineState
import dev.nova.tv.player.engine.PlayerEngineType
import dev.nova.tv.player.engine.exoplayer.NovaExoPlayerBundle
import dev.nova.tv.player.engine.exoplayer.NovaExoPlayerFactory
import dev.nova.tv.player.engine.exoplayer.compatibleSurroundTrack
import dev.nova.tv.player.engine.nextFailoverIndex
import dev.nova.tv.player.engine.safePlaybackHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The single player every source feeds.
 *
 * Playback is ARVIO's engine layer (`player/engine`, Apache-2.0): an ExoPlayer
 * built with ARVIO's buffer, renderer and track-selection settings, wrapped in
 * ARVIO's [PlayerEngine] so the UI reads one [PlayerEngineState] for position,
 * audio and subtitle tracks.
 *
 * Nova owns the part ARVIO kept inside its 7,000-line PlayerScreen: the ranked
 * source queue. Every stream the addons and the source finder returned is in
 * [Session.sources]; the viewer can switch with the Sources panel, and a
 * stream that fails is replaced by the next untried one automatically.
 *
 * Fixes against the previous NovaPlayer:
 *  - request headers were put in `RequestMetadata.extras`, which ExoPlayer
 *    never sends; they now go to the OkHttp data source;
 *  - the 500 ms position loop ran on the application scope forever, one more
 *    loop per title; the player now owns a scope that [release] cancels;
 *  - `isPlaying()` returned `playWhenReady`, so a buffering or failed stream
 *    read as "Playing".
 */
class NovaPlayer(
    context: Context,
    http: HttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bundle: NovaExoPlayerBundle =
        NovaExoPlayerFactory.create(context, http.okHttp, http.agent)
    private val exo: ExoPlayer = bundle.player

    /** ARVIO's engine abstraction; ExoPlayer is the only linked engine. */
    val engine: PlayerEngine =
        PlayerEngineFactory.createEngine(PlayerEngineType.EXOPLAYER, context, exo, scope)

    val engineState: StateFlow<PlayerEngineState> get() = engine.state

    /** The source queue and what happened to it. */
    data class Session(
        val sources: List<Stream> = emptyList(),
        val index: Int = -1,
        val tried: Set<Int> = emptySet(),
        val subtitles: List<SubtitleRef> = emptyList(),
        val failure: PlaybackFailure? = null,
        /** "Source 2 failed (timeout) — trying source 3", shown for a few seconds. */
        val notice: String? = null,
        val started: Boolean = false,
        val ended: Boolean = false
    ) {
        val current: Stream? get() = sources.getOrNull(index)
    }

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    /** Kept for the existing failure surface and tests. */
    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    private var startupJob: Job? = null
    private var noticeJob: Job? = null
    private val recoveredOnce = mutableSetOf<String>()

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        startupJob?.cancel()
                        _session.update { it.copy(started = true) }
                    }
                    Player.STATE_ENDED -> _session.update { it.copy(ended = true) }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (tryRecoverSameSource(error)) return
                fail(classify(error))
            }
        })
    }

    // -- source queue -------------------------------------------------------

    /** Load the whole ranked list and start at [index]. */
    fun load(
        sources: List<Stream>,
        index: Int,
        startPositionMs: Long = 0L,
        subtitles: List<SubtitleRef> = emptyList()
    ) {
        val playable = sources.filter { it.isPlayable }
        if (playable.isEmpty()) {
            fail(PlaybackFailure("no-source", "None of these sources can be played here.", retryable = false))
            return
        }
        val start = index.coerceIn(0, playable.lastIndex)
        _session.value = Session(sources = playable, index = start, tried = setOf(start), subtitles = subtitles)
        prepareCurrent(startPositionMs)
    }

    /** The viewer picked another source; keep their place in the film. */
    fun selectSource(index: Int) {
        val current = _session.value
        if (index !in current.sources.indices) return
        val resumeAt = if (current.started) exo.currentPosition.coerceAtLeast(0L) else 0L
        _session.value = current.copy(index = index, tried = current.tried + index, failure = null, notice = null, ended = false)
        prepareCurrent(resumeAt)
    }

    /** Retry the current source from where it stopped. */
    fun retry() {
        val current = _session.value
        _session.value = current.copy(failure = null)
        prepareCurrent(exo.currentPosition.coerceAtLeast(0L))
    }

    fun setSubtitles(subtitles: List<SubtitleRef>) {
        if (subtitles == _session.value.subtitles) return
        _session.update { it.copy(subtitles = subtitles) }
        // Sideloaded files are part of the MediaItem, so re-prepare in place.
        if (_session.value.current != null && _session.value.started) {
            prepareCurrent(exo.currentPosition.coerceAtLeast(0L), keepPlaying = exo.playWhenReady)
        }
    }

    private fun prepareCurrent(startPositionMs: Long, keepPlaying: Boolean = true) {
        val stream = _session.value.current ?: return
        val url = stream.url ?: return
        _failure.value = null
        _session.update { it.copy(failure = null, started = false, ended = false) }

        bundle.setRequestHeaders(stream.requestHeaders.safePlaybackHeaders())
        val kind = (FinderRules.classifyStream(stream) as? FinderRules.Verdict.Playable)?.kind
        val item = MediaItem.Builder()
            .setUri(url)
            .apply {
                when (kind) {
                    FinderRules.StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    FinderRules.StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    else -> Unit
                }
                val subs = _session.value.subtitles.mapIndexed { i, sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setId("ext:$i")
                        .setMimeType(subtitleMime(sub.url))
                        .setLanguage(sub.lang.ifBlank { null })
                        .setLabel(sub.label)
                        .build()
                }
                if (subs.isNotEmpty()) setSubtitleConfigurations(subs)
            }
            .build()
        exo.setMediaSource(bundle.mediaSource(item, progressive = kind == FinderRules.StreamKind.DIRECT), startPositionMs)
        exo.prepare()
        exo.playWhenReady = keepPlaying
        armStartupTimeout()
    }

    /**
     * A dead CDN link often never errors, it just never answers. ARVIO times
     * out the first frame and moves on; so does Nova.
     */
    private fun armStartupTimeout() {
        startupJob?.cancel()
        startupJob = scope.launch {
            delay(STARTUP_TIMEOUT_MS)
            if (!_session.value.started && _session.value.failure == null) {
                fail(PlaybackFailure("timeout", "The stream did not start.", retryable = true))
            }
        }
    }

    /** Move to the next untried source, or surface the failure if none is left. */
    private fun fail(problem: PlaybackFailure) {
        startupJob?.cancel()
        val current = _session.value
        val next = nextFailoverIndex(current.sources, current.index, current.tried)
        if (next >= 0) {
            val from = current.index + 1
            _session.value = current.copy(
                index = next,
                tried = current.tried + next,
                failure = null,
                notice = "Source $from failed (${problem.reason}), trying source ${next + 1} of ${current.sources.size}"
            )
            showNoticeBriefly()
            prepareCurrent(0L)
        } else {
            _failure.value = problem
            _session.value = current.copy(failure = problem, notice = null)
        }
    }

    private fun showNoticeBriefly() {
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _session.update { it.copy(notice = null) }
        }
    }

    /**
     * ARVIO's audio recovery ladder, in order: block a crashed audio decoder
     * and use the software one; pick a compatible AC-3 track when surround
     * output fails; fall back from passthrough to PCM. Each runs once per
     * source and keeps the viewer's position.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun tryRecoverSameSource(error: PlaybackException): Boolean {
        val source = _session.value.current?.url ?: return false
        val exoError = error as? ExoPlaybackException
        val decoderName = (exoError?.cause as? androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException)
            ?.codecInfo?.name
            ?: (exoError?.cause as? androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException)?.codecInfo?.name
        val audioRenderer = exoError?.type == ExoPlaybackException.TYPE_RENDERER &&
            exoError.rendererFormat?.sampleMimeType?.let { MimeTypes.isAudio(it) } == true
        if (audioRenderer && decoderName != null && bundle.renderers.blockAudioDecoder(decoderName, exoError?.rendererFormat)) {
            return restartInPlace()
        }
        val outputFailure = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        val failedFormat = exoError?.rendererFormat ?: exo.audioFormat
        if (outputFailure && recoveredOnce.add("surround:$source")) {
            val alternate = compatibleSurroundTrack(failedFormat, exo.currentTracks)
            if (alternate != null) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(alternate).build()
                return restartInPlace()
            }
        }
        if (outputFailure && bundle.renderers.blockAudioPassthrough(failedFormat)) {
            return restartInPlace()
        }
        return false
    }

    private fun restartInPlace(): Boolean {
        val resumeAt = exo.currentPosition.coerceAtLeast(0L)
        val keepPlaying = exo.playWhenReady
        exo.stop()
        exo.prepare()
        exo.seekTo(resumeAt)
        exo.playWhenReady = keepPlaying
        return true
    }

    // -- transport ----------------------------------------------------------

    /** The engine, for the one thing Compose cannot do: attach a Surface. */
    val player: ExoPlayer get() = exo

    /** True only while frames are actually advancing. */
    fun isPlaying(): Boolean = exo.isPlaying

    fun play() = engine.play()
    fun pause() = engine.pause()
    fun togglePlay() = engine.togglePlayPause()

    fun seekBy(deltaMs: Long) {
        when (val result = seekBy(exo.currentPosition, deltaMs, exo.duration)) {
            is SeekResult.SeekedTo -> engine.seekTo(result.targetMs)
            is SeekResult.Clamped -> engine.seekTo(result.targetMs)
            is SeekResult.Ignored -> Unit
        }
    }

    fun setRate(rate: Float) = engine.setPlaybackSpeed(rate.coerceIn(0.25f, 3f))

    fun resumePosition(): Long = exo.currentPosition.coerceAtLeast(0L)

    fun release() {
        startupJob?.cancel()
        noticeJob?.cancel()
        engine.release()
        exo.release()
        scope.cancel()
    }

    /** Turn an engine error into a reason the caller can act on. */
    private fun classify(error: PlaybackException): PlaybackFailure = classifyError(error.errorCode, error.message)

    companion object {
        const val STARTUP_TIMEOUT_MS = 20_000L
        private const val NOTICE_MS = 4_000L

        internal fun classifyError(code: Int, message: String?): PlaybackFailure = when (code) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                PlaybackFailure("bad-http", "The server refused this stream.", retryable = false)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                PlaybackFailure("network", "Network unreachable.", retryable = true)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                PlaybackFailure("timeout", "The stream timed out.", retryable = true)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                PlaybackFailure("container", "This format cannot be decoded.", retryable = false)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                PlaybackFailure("decoder", "The device decoder rejected this stream.", retryable = false)
            else -> PlaybackFailure("unknown", message ?: "Playback failed.", retryable = true)
        }

        /** ARVIO defaults unknown subtitle files to SubRip; so does Nova. */
        internal fun subtitleMime(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}

data class PlaybackFailure(
    val reason: String,
    val message: String,
    val retryable: Boolean
)
