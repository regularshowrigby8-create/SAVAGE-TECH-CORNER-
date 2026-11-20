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
import dev.nova.tv.player.preload.FragmentPreloader
import dev.nova.tv.player.preload.PreloadCache
import dev.nova.tv.player.preload.PreloadMode
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
    http: HttpClient,
    initialBitrateEstimate: Long = 0L,
    preferredAudioLanguage: String? = null,
    initialPreload: PreloadMode = PreloadMode.OFF
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = PreloadCache.get(context)

    private val bundle: NovaExoPlayerBundle = NovaExoPlayerFactory.create(
        context, http.okHttp, http.agent,
        preferredAudioLanguage = preferredAudioLanguage,
        initialBitrateEstimate = initialBitrateEstimate,
        cache = cache
    )

    /** FastStream-style pre-download into the cache the player reads. */
    private val preloader = FragmentPreloader(cache, http.okHttp, http.agent)
    val preloadStatus: StateFlow<FragmentPreloader.Status> get() = preloader.status
    private var preloadMode: PreloadMode = initialPreload
    private var preloadTicker: Job? = null

    private val probe = StreamProbe(http.okHttp)
    private val exo: ExoPlayer = bundle.player

    /** ARVIO's engine abstraction; ExoPlayer is the only linked engine. */
    val engine: PlayerEngine =
        PlayerEngineFactory.createEngine(PlayerEngineType.EXOPLAYER, context, exo, scope)

    val engineState: StateFlow<PlayerEngineState> get() = engine.state

    /** The percentage ring both players draw while waiting; null when playing. */
    private val _buffering = MutableStateFlow<BufferingStatus?>(null)
    val buffering: StateFlow<BufferingStatus?> = _buffering.asStateFlow()
    private var bufferJob: Job? = null

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
    private var stallJob: Job? = null
    private val recoveredOnce = mutableSetOf<String>()
    /** Sources whose bitrate was already capped after a stall. */
    private val downshifted = mutableSetOf<String>()
    private val stalls = StallTracker()
    private var lastSeekAt = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        startupJob?.cancel()
                        stallJob?.cancel()
                        endBuffering()
                        val firstStart = !_session.value.started
                        _session.update { it.copy(started = true) }
                        if (firstStart) {
                            HostHealth.noteSuccess(_session.value.current?.url)
                            prewarmNext()
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        beginBuffering()
                        if (_session.value.started) onRebuffer()
                    }
                    Player.STATE_ENDED -> {
                        stallJob?.cancel()
                        endBuffering()
                        _session.update { it.copy(ended = true) }
                    }
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
        stalls.reset()
        downshifted.clear()
        clearQualityCeiling()
        _session.value = Session(sources = playable, index = start, tried = setOf(start), subtitles = subtitles)
        prepareCurrent(startPositionMs)
    }

    /**
     * Sources keep arriving after Play (slow addons, the finders). Add the
     * new ones to the end of the failover queue without touching playback.
     */
    fun appendSources(more: List<Stream>) {
        val current = _session.value
        if (current.sources.isEmpty()) return
        val known = current.sources.mapNotNull { it.url }.toHashSet()
        val fresh = more.filter { it.isPlayable && it.url !in known }
        if (fresh.isEmpty()) return
        _session.value = current.copy(sources = current.sources + fresh)
        // Every source had failed; the first new one gets its turn.
        if (current.failure != null) {
            _failure.value = null
            selectSource(current.sources.size)
        }
    }

    fun durationMs(): Long = exo.duration.takeIf { it > 0 } ?: 0L

    /** The viewer picked another source; keep their place in the film. */
    fun selectSource(index: Int) {
        val current = _session.value
        if (index !in current.sources.indices) return
        val resumeAt = if (current.started) exo.currentPosition.coerceAtLeast(0L) else 0L
        _session.value = current.copy(index = index, tried = current.tried + index, failure = null, notice = null, ended = false)
        stalls.reset()
        prepareCurrent(resumeAt)
    }

    // -- anti-buffering -------------------------------------------------------

    /** The player's Preload button: Off → Preload → Multi-thread → Off. */
    fun setPreload(mode: PreloadMode) {
        preloadMode = mode
        restartPreload()
    }

    fun preloadMode(): PreloadMode = preloadMode

    /**
     * Start preloading as soon as a source is prepared, not at READY: the
     * opening fragments land on disk while ExoPlayer is still opening the
     * container, which is the difference between an instant start and a
     * spinner. The head window needs no duration, so nothing waits here.
     */
    private fun restartPreload(startPositionMs: Long = 0L) {
        preloadTicker?.cancel()
        preloader.stop()
        preloader.playheadMs = startPositionMs
        preloader.durationMs = 0
        val stream = _session.value.current ?: return
        val url = stream.url ?: return
        val kind = (FinderRules.classifyStream(stream) as? FinderRules.Verdict.Playable)?.kind
        when {
            preloadMode == PreloadMode.OFF -> { preloader.waiting(PreloadMode.OFF); return }
            // HLS / DASH fetch their own segments ahead, and the cache data
            // source still serves them; byte-range preloading is for files.
            kind != FinderRules.StreamKind.DIRECT -> { preloader.unavailable(preloadMode); return }
        }
        preloader.start(preloadMode, url, stream.requestHeaders.safePlaybackHeaders(), 0)
        // The workers run off the main thread; ExoPlayer may only be read on it.
        preloadTicker = scope.launch {
            while (true) {
                preloader.playheadMs = exo.currentPosition.coerceAtLeast(0)
                preloader.durationMs = exo.duration.takeIf { it > 0 } ?: 0
                delay(PRELOAD_TICK_MS)
            }
        }
    }

    // -- buffering meter ------------------------------------------------------

    private fun beginBuffering() {
        if (bufferJob?.isActive == true) return
        val startedAt = System.currentTimeMillis()
        _buffering.value = BufferingStatus(percent = 1, label = "Starting playback")
        bufferJob = scope.launch {
            while (true) {
                val ahead = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L)
                val raw = BufferingMeter.percent(ahead, elapsedMs = System.currentTimeMillis() - startedAt)
                val shown = BufferingMeter.monotonic(_buffering.value?.percent, raw)
                val session = _session.value
                val pre = preloader.status.value
                _buffering.value = BufferingStatus(
                    percent = shown,
                    label = when {
                        session.notice != null -> session.notice!!
                        !session.started -> "Starting playback"
                        pre.sequential -> "Buffering · caching file"
                        pre.active -> "Buffering · ${pre.workers} ${if (pre.workers == 1) "connection" else "connections"}"
                        else -> "Buffering"
                    }
                )
                delay(BUFFER_TICK_MS)
            }
        }
    }

    private fun endBuffering() {
        bufferJob?.cancel()
        bufferJob = null
        _buffering.value = null
    }

    /**
     * Watchdog: a single rebuffer longer than [StallTracker.LONG_STALL_MS], or
     * [StallTracker.MAX_STALLS] rebuffers inside a minute, means this host
     * cannot keep up. Switch to the next healthy source at the same position
     * instead of letting the viewer watch a spinner.
     */
    private fun onRebuffer() {
        val now = System.currentTimeMillis()
        // A seek always buffers; that is the viewer's doing, not the host's.
        if (now - lastSeekAt < SEEK_GRACE_MS) return
        if (stalls.note(now)) {
            switchForStall()
            return
        }
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(StallTracker.LONG_STALL_MS)
            if (exo.playbackState == Player.STATE_BUFFERING) switchForStall()
        }
    }

    private fun switchForStall() {
        stallJob?.cancel()
        val current = _session.value
        HostHealth.noteStall(current.current?.url)
        // Fallback ladder, cheapest fix first: ask the same source for a lower
        // bitrate (ExoPlayer re-selects without re-preparing), and only move to
        // another host when even that keeps stalling.
        if (downshiftQuality(current.current?.url)) {
            stalls.reset()
            _session.update { it.copy(notice = "Lowering quality to keep playing") }
            showNoticeBriefly()
            return
        }
        val next = nextFailoverIndex(current.sources, current.index, current.tried)
        if (next < 0) return // nothing better to go to; keep buffering
        val resumeAt = exo.currentPosition.coerceAtLeast(0L)
        stalls.reset()
        _session.value = current.copy(
            index = next,
            tried = current.tried + next,
            failure = null,
            notice = "Switching to a faster source"
        )
        showNoticeBriefly()
        prepareCurrent(resumeAt)
    }

    /** Open connections to the next two failover sources while this one plays. */
    /**
     * Second anti-buffering fallback: cap the video bitrate the measured link
     * can actually sustain. Adaptive streams switch down by themselves, but a
     * progressive file has no switch — the track selector has to be told.
     */
    private fun downshiftQuality(url: String?): Boolean {
        if (url == null || url in downshifted) return false
        // A single-track file has nothing to switch down to. Capping there would
        // only delay the source switch and promise a fix that cannot happen.
        if (videoTrackCount() <= 1) return false
        val estimate = bundle.bandwidthMeter.bitrateEstimate.takeIf { it > 0 } ?: return false
        val cap = (estimate * BITRATE_MARGIN).toInt().coerceAtLeast(MIN_BITRATE_CAP)
        val currentCap = exo.trackSelectionParameters.maxVideoBitrate
        downshifted += url
        if (currentCap <= cap) return false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoBitrate(cap)
            .build()
        return true
    }

    private fun videoTrackCount(): Int {
        val tracks = exo.currentTracks
        return tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }.sumOf { it.length }
    }

    private fun clearQualityCeiling() {
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .build()
        }
    }

    private fun prewarmNext() {
        val s = _session.value
        val upcoming = generateSequence(s.index) { i ->
            nextFailoverIndex(s.sources, i, s.tried + i).takeIf { it >= 0 && it != s.index }
        }.drop(1).take(PREWARM_COUNT).mapNotNull { s.sources.getOrNull(it) }.toList()
        scope.launch { upcoming.forEach { probe.prewarm(it) } }
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
        // The preloader is left running: a caption arriving must not throw away
        // fragments that are already in flight.
        if (_session.value.current != null && _session.value.started) {
            prepareCurrent(exo.currentPosition.coerceAtLeast(0L), keepPlaying = exo.playWhenReady, restartPreload = false)
        }
    }

    private fun prepareCurrent(startPositionMs: Long, keepPlaying: Boolean = true, restartPreload: Boolean = true) {
        val stream = _session.value.current ?: return
        val url = stream.url ?: return
        // A new source restarts the preloader once it is READY.
        preloadTicker?.cancel()
        preloader.waiting(preloadMode)
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
        // A new prepare is a new buffering episode: without this the ring would
        // hold the previous episode's 97% through a failover.
        endBuffering()
        beginBuffering()
        if (restartPreload) restartPreload(startPositionMs)
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
        stallJob?.cancel()
        endBuffering()
        val current = _session.value
        HostHealth.noteFailure(current.current?.url)
        val resumeAt = if (current.started) exo.currentPosition.coerceAtLeast(0L) else 0L
        val next = nextFailoverIndex(current.sources, current.index, current.tried)
        if (next >= 0) {
            _session.value = current.copy(
                index = next,
                tried = current.tried + next,
                failure = null,
                notice = "Trying another source"
            )
            showNoticeBriefly()
            // Keep the viewer's place: a source that dies mid-film resumes there.
            prepareCurrent(resumeAt)
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
        lastSeekAt = System.currentTimeMillis()
        when (val result = seekBy(exo.currentPosition, deltaMs, exo.duration)) {
            is SeekResult.SeekedTo -> engine.seekTo(result.targetMs)
            is SeekResult.Clamped -> engine.seekTo(result.targetMs)
            is SeekResult.Ignored -> Unit
        }
    }

    fun setRate(rate: Float) = engine.setPlaybackSpeed(rate.coerceIn(0.25f, 3f))

    /**
     * Absolute seek, for the scrub bar.
     *
     * `seekBy` could not serve a bar: the bar knows a position, not a delta.
     * The end guard is the same one `seekBy` uses, so dragging to the far right
     * lands just short of the end instead of firing STATE_ENDED and rolling
     * into the next episode.
     */
    fun seekTo(positionMs: Long) {
        lastSeekAt = System.currentTimeMillis()
        val duration = exo.duration
        val target = if (duration > 0L) {
            positionMs.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
        } else {
            positionMs.coerceAtLeast(0L)
        }
        engine.seekTo(target)
    }

    /** 0 = no cap. Applied live; the audio/subtitle choice is left alone. */
    private var qualityCap: Int = 0

    fun qualityCapHeight(): Int = qualityCap

    fun setQualityCap(height: Int) {
        qualityCap = height
        runCatching {
            exo.trackSelectionParameters = dev.nova.tv.player.imported.TrackOptions
                .capHeight(exo.trackSelectionParameters, height)
        }
    }

    /** Add a subtitle track (a picked file or an addon result) without a restart. */
    fun addSubtitle(ref: SubtitleRef) {
        val current = _session.value.subtitles
        if (current.any { it.url == ref.url }) return
        setSubtitles(current + ref)
    }

    fun removeSubtitle(url: String) {
        setSubtitles(_session.value.subtitles.filterNot { it.url == url })
    }

    fun resumePosition(): Long = exo.currentPosition.coerceAtLeast(0L)

    fun release() {
        preloadTicker?.cancel()
        preloader.release()
        startupJob?.cancel()
        stallJob?.cancel()
        noticeJob?.cancel()
        endBuffering()
        engine.release()
        exo.release()
        scope.cancel()
    }

    /** Turn an engine error into a reason the caller can act on. */
    private fun classify(error: PlaybackException): PlaybackFailure = classifyError(error.errorCode, error.message)

    companion object {
        /** ARVIO uses 20 s; with probed sources a dead start is obvious sooner. */
        const val STARTUP_TIMEOUT_MS = 15_000L
        private const val PREWARM_COUNT = 2
        private const val SEEK_GRACE_MS = 4_000L
        private const val NOTICE_MS = 4_000L
        private const val PRELOAD_TICK_MS = 500L
        private const val BUFFER_TICK_MS = 150L
        private const val BITRATE_MARGIN = 0.7
        private const val MIN_BITRATE_CAP = 600_000

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

/**
 * Counts rebuffers in a sliding minute. Pure, so the thresholds are tested.
 */
class StallTracker {
    private val times = ArrayDeque<Long>()

    /** Record a rebuffer; true when the source should be abandoned. */
    fun note(now: Long): Boolean {
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MS) times.removeFirst()
        return times.size >= MAX_STALLS
    }

    fun reset() = times.clear()

    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_STALLS = 3
        const val LONG_STALL_MS = 8_000L
    }
}

data class PlaybackFailure(
    val reason: String,
    val message: String,
    val retryable: Boolean
)
