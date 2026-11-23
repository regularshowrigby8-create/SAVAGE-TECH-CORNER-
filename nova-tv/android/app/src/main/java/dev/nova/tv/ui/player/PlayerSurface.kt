@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.ui.player

import android.content.Intent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.player.SkipSeconds
import dev.nova.tv.player.SubtitleStyle
import dev.nova.tv.player.applySubtitleStyle
import dev.nova.tv.player.engine.ResizeMode
import dev.nova.tv.player.imported.AspectSteps
import dev.nova.tv.player.imported.QualityLadder
import dev.nova.tv.player.imported.ScrubState
import dev.nova.tv.player.imported.SpeedStep
import dev.nova.tv.player.imported.SubtitleFiles
import dev.nova.tv.player.imported.SubtitleOffsetController
import dev.nova.tv.player.imported.attachSubtitleOffset
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.PlayerPanel
import dev.nova.tv.ui.screens.UpNext
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType
import kotlinx.coroutines.delay

/**
 * The single player every source plays through.
 *
 * `PlayerView` owns the Surface (the one thing Compose cannot); the chrome is
 * built for a remote. Changes from the previous surface:
 *
 *  - BACK is no longer intercepted here. It goes through the shell's one
 *    BackHandler and `NovaNav.resolveBack`: panel, then reveal chrome, then
 *    stop. This surface used to keep its own `chromeVisible` flag and a
 *    two-press rule that fought the shell's handler.
 *  - Chrome visibility is `ChromeController` (the video.js rule), fed from the
 *    ARVIO engine's state: paused, buffering, ended or failed keep it up.
 *  - With chrome hidden, LEFT/RIGHT seek and OK/UP/DOWN reveal the controls;
 *    with chrome showing, the D-pad moves between real buttons. Previously OK
 *    always toggled play, so the on-screen buttons could never be pressed.
 *  - The progress bar is now an input, not a drawing: focus it and LEFT/RIGHT
 *    scrub, OK commits. It used to be a `Box` with a width, which is why
 *    seeking "did not work" on a remote.
 *  - Subtitles are a real menu: Off, embedded and sidecar tracks, add a file
 *    from the device (SAF), delay in 250 ms steps, and caption styling.
 *  - Quality, playback speed, aspect ratio and episode skipping are all in
 *    the chrome rather than being declared-but-unreachable engine calls.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerSurface(
    player: NovaPlayer,
    title: String,
    viewModel: NovaViewModel,
    modifier: Modifier = Modifier
) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()
    val chrome by viewModel.chromeState.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val buffering by player.buffering.collectAsStateWithLifecycle()
    val panel = ui.playerPanel
    val failure = session.failure

    LaunchedEffect(engine.isPlaying, engine.isBuffering, session.ended, failure) {
        viewModel.chrome.update {
            it.copy(
                playing = engine.isPlaying,
                buffering = engine.isBuffering,
                ended = session.ended,
                error = failure?.message
            )
        }
    }

    // Subtitle delay: the controller keeps every cue interval the renderer
    // emitted, so the offset can be changed in both directions without a
    // re-prepare (no picture blink). Imported from JustPlayer.
    val subtitleOffset = remember(player) { SubtitleOffsetController(player.player) }
    var offsetMs by remember(player) { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = player.player.attachSubtitleOffset(subtitleOffset)
        onDispose { player.player.removeListener(listener) }
    }
    SideEffect {
        subtitleOffset.listener = { offsetMs = it }
    }

    val context = LocalContext.current
    // SAF picker: .srt/.vtt/.ass/.ssa/.ttml all reach ExoPlayer as sidecar
    // tracks through the same DefaultDataSource that carries addon headers.
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = runCatching { SubtitleFiles.displayName(context, uri) }.getOrNull()
            ?: uri.lastPathSegment ?: "Subtitle"
        player.addSubtitle(
            SubtitleRef(
                id = "ext:${uri}",
                url = uri.toString(),
                lang = SubtitleFiles.languageOf(name).orEmpty()
            )
        )
        subtitleOffset.reset()
        offsetMs = 0L
        viewModel.closePlayerPanel()
        viewModel.notify("Subtitle added: $name")
    }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val failureFocus = remember { FocusRequester() }
    val showChrome = chrome.chromeVisible && failure == null && panel == null && ui.upNext == null

    // Focus follows what is on screen. Without this, hiding the chrome removed
    // the focused button and the next key press went nowhere.
    LaunchedEffect(showChrome, failure != null, panel) {
        withFrameNanos { }
        runCatching {
            when {
                panel != null -> Unit // the panel focuses its own first row
                failure != null -> failureFocus.requestFocus()
                showChrome -> playFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(NovaPalette.background)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                if (code != AndroidKeyEvent.KEYCODE_BACK) viewModel.chrome.onUserActive()
                when (code) {
                    // Media keys work whatever has focus.
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.togglePlay(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBy(-SkipSeconds.HOLD); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekBy(SkipSeconds.HOLD); true }
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { viewModel.openPlayerPanel(PlayerPanel.SUBTITLES); true }
                    else -> if (showChrome || failure != null || panel != null || ui.upNext != null) {
                        false // the focus system moves between buttons
                    } else when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBy(-SkipSeconds.TAP); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekBy(SkipSeconds.TAP); true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_DPAD_UP,
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> true // onUserActive above revealed the chrome
                        else -> false
                    }
                }
            }
    ) {
        val exo = player.player
        // Held so caption styling, the resize mode and the delayed-cue ticker
        // can reach the same view after it is created; PlayerView owns the
        // Surface and the subtitle view.
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    // The percentage ring below replaces PlayerView's spinner.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    this.player = exo
                }.also { playerView = it }
            },
            modifier = Modifier.fillMaxSize()
        )
        LaunchedEffect(playerView, ui.subtitleStyle) {
            playerView?.applySubtitleStyle(ui.subtitleStyle)
        }
        // ExoPlayerEngine records the resize mode; the view is the only thing
        // that can honour it. Without this the Aspect menu did nothing.
        LaunchedEffect(playerView, engine.resizeMode) {
            playerView?.resizeMode = when (engine.resizeMode) {
                ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                ResizeMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ResizeMode.FIT, ResizeMode.AUTO -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        // A non-zero delay needs cues re-timed against a shifted clock, so the
        // view is redrawn from the controller instead of the renderer.
        LaunchedEffect(playerView, offsetMs) {
            val view = playerView?.subtitleView ?: return@LaunchedEffect
            if (offsetMs == 0L) return@LaunchedEffect
            while (true) {
                view.setCues(subtitleOffset.cuesAt(exo.currentPosition))
                delay(100)
            }
        }

        // Counted, not just spinning: the ring shows how far the buffer is.
        // Only while the player is really waiting to play — a paused player can
        // sit in STATE_BUFFERING, and a ring over a paused film reads as broken.
        if (failure == null && panel == null && engine.isBuffering) {
            buffering?.let { status ->
                BufferingOverlay(status, Modifier.align(Alignment.Center))
            }
        }

        // Failover is never silent: say which source died and what is next.
        session.notice?.let { notice ->
            Text(
                text = notice,
                style = NovaType.meta,
                color = NovaPalette.textPrimary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NovaSpace.safeVertical)
                    .background(NovaPalette.surface.copy(alpha = 0.9f), RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }

        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Controls(
                player = player,
                title = title,
                viewModel = viewModel,
                playFocus = playFocus
            )
        }

        failure?.let { problem ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(NovaPalette.background.copy(alpha = 0.92f))
                    .padding(48.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("This one won't play right now", style = NovaType.sectionTitle)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (problem.retryable) {
                        PrimaryButton("Try again", modifier = Modifier.focusRequester(failureFocus)) { player.retry() }
                        SecondaryButton("Other sources") { viewModel.openPlayerPanel(PlayerPanel.SOURCES) }
                    } else {
                        PrimaryButton("Other sources", modifier = Modifier.focusRequester(failureFocus)) {
                            viewModel.openPlayerPanel(PlayerPanel.SOURCES)
                        }
                    }
                    SecondaryButton("Close", onClick = viewModel::stopPlayback)
                }
            }
        }

        ui.upNext?.let { next ->
            UpNextCard(next, viewModel, Modifier.align(Alignment.BottomEnd))
        }

        if (panel != null) {
            Panel(
                panel = panel,
                player = player,
                viewModel = viewModel,
                subtitleStyle = ui.subtitleStyle,
                subtitleOffset = subtitleOffset,
                offsetMs = offsetMs,
                onOffsetChange = { offsetMs = it },
                onAddSubtitleFile = { runCatching { pickSubtitle.launch(SubtitleFiles.MIME_TYPES.toTypedArray()) } },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun Controls(
    player: NovaPlayer,
    title: String,
    viewModel: NovaViewModel,
    playFocus: FocusRequester
) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()
    val preload by player.preloadStatus.collectAsStateWithLifecycle()

    val previous = remember(session.index, engine.durationMs) { viewModel.previousEpisode() }
    val next = remember(session.index, engine.durationMs) { viewModel.nextEpisode() }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, NovaPalette.background.copy(alpha = 0.92f))
                )
            )
            .padding(horizontal = NovaSpace.safeHorizontal, vertical = NovaSpace.safeVertical)
            .focusGroup()
    ) {
        Text(title, style = NovaType.sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        session.current?.label?.let { label ->
            Text(
                text = label,
                style = NovaType.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(10.dp))
        ScrubBar(
            player = player,
            position = engine.currentPositionMs,
            duration = engine.durationMs,
            preloadedMs = if (preload.active) preload.aheadMs else 0L
        )
        Spacer(Modifier.height(10.dp))
        // Scrolls with focus: thirteen pills do not fit 960 dp, and none may be
        // cut off.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(
                if (engine.isPlaying) "Pause" else "Play",
                modifier = Modifier.focusRequester(playFocus)
            ) { player.togglePlay() }
            SecondaryButton("−${SkipSeconds.TAP / 1000}s") { player.seekBy(-SkipSeconds.TAP) }
            SecondaryButton("+${SkipSeconds.TAP / 1000}s") { player.seekBy(SkipSeconds.TAP) }
            if (previous != null) SecondaryButton("Prev") { viewModel.playPrevious() }
            if (next != null) SecondaryButton("Next") { viewModel.playNext() }
            SecondaryButton("Quality") { viewModel.openPlayerPanel(PlayerPanel.QUALITY) }
            SecondaryButton(SpeedStep.nearest(engine.playbackSpeed).label) {
                viewModel.openPlayerPanel(PlayerPanel.SPEED)
            }
            SecondaryButton(AspectSteps.label(engine.resizeMode)) {
                player.engine.setResizeMode(AspectSteps.next(engine.resizeMode))
            }
            // FastStream-style anti-buffering: Off → Preload → Multi-thread.
            SecondaryButton(
                label = if (preload.unavailable) "No preload" else preload.mode.label,
                icon = Icons.Filled.Refresh,
                active = preload.mode != dev.nova.tv.player.preload.PreloadMode.OFF && !preload.unavailable
            ) {
                val mode = player.preloadMode().next()
                player.setPreload(mode)
                viewModel.setPreload(mode)
            }
            if (session.sources.size > 1) SecondaryButton("Sources") { viewModel.openPlayerPanel(PlayerPanel.SOURCES) }
            SecondaryButton("Audio") { viewModel.openPlayerPanel(PlayerPanel.AUDIO) }
            SecondaryButton("Subtitles") { viewModel.openPlayerPanel(PlayerPanel.SUBTITLES) }
            SecondaryButton("Close", onClick = viewModel::stopPlayback)
        }
    }
}

/**
 * A progress bar that is also an input.
 *
 * Focus it and the D-pad scrubs: LEFT/RIGHT move the cursor (coarse on a tap),
 * OK jumps to it, and leaving the bar commits whatever the cursor reached.
 * This is the piece that was missing — the old bar was a `Box` with a width
 * and no key handling at all, so on a remote "seeking did not work".
 *
 * The scrub cursor is local state; the player is only touched on commit, so a
 * held key does not queue forty seeks.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ScrubBar(
    player: NovaPlayer,
    position: Long,
    duration: Long,
    preloadedMs: Long,
    modifier: Modifier = Modifier
) {
    val scrub = remember(player) {
        ScrubState(
            durationMs = { player.engineState.value.durationMs },
            positionMs = { player.engineState.value.currentPositionMs }
        )
    }
    var preview by remember(player) { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }

    val shown = preview ?: position
    val fraction = if (duration <= 0) 0f else (shown.toFloat() / duration).coerceIn(0f, 1f)
    val preloaded = if (duration <= 0) 0f else ((shown + preloadedMs).toFloat() / duration).coerceIn(0f, 1f)

    val commit: () -> Unit = {
        scrub.commit()?.let { player.seekTo(it) }
        preview = null
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(shown),
                style = NovaType.meta,
                color = if (preview != null) NovaPalette.accent else NovaPalette.textPrimary
            )
            Spacer(Modifier.width(10.dp))
            if (focused || preview != null) {
                Text(
                    text = "◀ ▶ scrub  ·  OK jump",
                    style = NovaType.meta,
                    color = NovaPalette.textMuted
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration) else "--:--", style = NovaType.meta)
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (state.isFocused) {
                        scrub.begin()
                        preview = scrub.cursorMs
                    } else {
                        // Leaving the bar applies the cursor: the TV convention.
                        commit()
                        scrub.cancel()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            preview = scrub.nudge(-SCRUB_STEP_MS)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            preview = scrub.nudge(SCRUB_STEP_MS)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER -> {
                            commit()
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
        ) {
            val trackHeight = if (focused) 10.dp else 6.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .align(Alignment.Center)
                    .background(NovaPalette.surfaceHigh, RoundedCornerShape(trackHeight / 2))
            ) {
                // What is already on disk ahead of the playhead.
                if (preloaded > fraction) {
                    Box(
                        Modifier
                            .fillMaxWidth(preloaded)
                            .fillMaxHeight()
                            .background(NovaPalette.textPrimary.copy(alpha = 0.35f), RoundedCornerShape(trackHeight / 2))
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(NovaPalette.accent, RoundedCornerShape(trackHeight / 2))
                )
            }
            // The thumb: a dot the viewer can see move from the sofa.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth * fraction) - 8.dp)
                    .size(if (focused) 18.dp else 14.dp)
                    .clip(CircleShape)
                    .background(if (focused) NovaPalette.textPrimary else NovaPalette.accent)
                    .then(if (focused) Modifier.border(3.dp, NovaPalette.accent, CircleShape) else Modifier)
            )
        }
    }
}

/** One option in a panel. */
private data class PanelOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onPick: () -> Unit
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Panel(
    panel: PlayerPanel,
    player: NovaPlayer,
    viewModel: NovaViewModel,
    subtitleStyle: SubtitleStyle,
    subtitleOffset: SubtitleOffsetController,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onAddSubtitleFile: () -> Unit,
    modifier: Modifier
) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()

    val options: List<PanelOption> = when (panel) {
        PlayerPanel.SOURCES -> session.sources.mapIndexed { i, stream ->
            val tried = i in session.tried && i != session.index
            PanelOption(
                label = stream.label,
                detail = when {
                    i == session.index -> "Playing"
                    tried -> "Failed"
                    else -> null
                },
                selected = i == session.index
            ) {
                player.selectSource(i)
                viewModel.closePlayerPanel()
            }
        }

        PlayerPanel.QUALITY -> buildList {
            add(
                PanelOption(
                    label = "Auto",
                    detail = if (player.qualityCapHeight() == 0) "Adaptive" else "Cap off",
                    selected = player.qualityCapHeight() == 0
                ) {
                    player.setQualityCap(0)
                    viewModel.closePlayerPanel()
                }
            )
            QualityLadder.rungs(session.sources, session.index).forEach { rung ->
                val height = rung.label.removeSuffix("p").toIntOrNull() ?: 0
                add(
                    PanelOption(
                        label = rung.label,
                        detail = rung.detail,
                        selected = rung.streamIndex == session.index && height == player.qualityCapHeight()
                    ) {
                        if (rung.streamIndex != session.index) player.selectSource(rung.streamIndex)
                        player.setQualityCap(height)
                        viewModel.closePlayerPanel()
                    }
                )
            }
        }

        PlayerPanel.SPEED -> SpeedStep.entries.map { step ->
            PanelOption(
                label = step.label,
                detail = if (kotlin.math.abs(engine.playbackSpeed - step.value) < 0.01f) "Playing" else null,
                selected = kotlin.math.abs(engine.playbackSpeed - step.value) < 0.01f
            ) {
                player.setRate(step.value)
                viewModel.setPlaybackRate(step.value)
                viewModel.closePlayerPanel()
            }
        }

        PlayerPanel.AUDIO -> engine.audioTracks.map { track ->
            val detail = listOfNotNull(
                track.language?.uppercase(),
                track.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                track.mimeType?.substringAfter('/')
            ).joinToString("  ·  ").ifBlank { null }
            PanelOption(track.displayLabel(), detail, track.index == engine.selectedAudioIndex) {
                player.engine.selectAudioTrack(track.index)
                viewModel.closePlayerPanel()
            }
        }

        PlayerPanel.SUBTITLES -> buildList {
            add(
                PanelOption("Off", null, engine.selectedSubtitleTrack == null) {
                    player.engine.selectSubtitleTrack(null)
                    viewModel.closePlayerPanel()
                }
            )
            add(PanelOption("Add subtitle file…", "From this device", false, onAddSubtitleFile))
            // The engines are asked again on demand: a title that had no
            // captions at Play time often gains them a minute later.
            add(
                PanelOption(
                    "Refresh subtitles",
                    if (session.subtitles.isEmpty()) "Searching…" else "${session.subtitles.size} found",
                    false
                ) { viewModel.reloadSubtitles() }
            )
            // Delay: positive shows captions later. Two rows keep it one click
            // per direction instead of a hidden submenu.
            add(
                PanelOption(
                    label = "Delay later",
                    detail = subtitleOffset.label(),
                    selected = false
                ) {
                    subtitleOffset.step(SubtitleOffsetController.STEP_MS)
                    onOffsetChange(subtitleOffset.offsetMs)
                }
            )
            add(
                PanelOption(
                    label = "Delay earlier",
                    detail = subtitleOffset.label(),
                    selected = false
                ) {
                    subtitleOffset.step(-SubtitleOffsetController.STEP_MS)
                    onOffsetChange(subtitleOffset.offsetMs)
                }
            )
            if (offsetMs != 0L) {
                add(
                    PanelOption("Reset delay", subtitleOffset.label(), false) {
                        subtitleOffset.offsetMs = 0L
                        subtitleOffset.reset()
                        onOffsetChange(0L)
                    }
                )
            }
            // Style stays on screen while it is adjusted, unlike a track pick.
            add(
                PanelOption("Caption style", subtitleStyle.tone.label, false) {
                    viewModel.setSubtitleStyle(subtitleStyle.nextTone())
                }
            )
            add(
                PanelOption("Caption size", subtitleStyle.sizeLabel, false) {
                    viewModel.setSubtitleStyle(subtitleStyle.nextSize())
                }
            )
            add(
                PanelOption("Caption box", subtitleStyle.backgroundLabel, false) {
                    viewModel.setSubtitleStyle(subtitleStyle.nextBackground())
                }
            )
            add(
                PanelOption("Caption height", subtitleStyle.bottomLabel, false) {
                    viewModel.setSubtitleStyle(subtitleStyle.nextBottom())
                }
            )
            engine.subtitleTracks.forEach { track ->
                add(
                    PanelOption(
                        track.displayLabel(),
                        if (track.isExternal) "Sidecar" else null,
                        track.id == engine.selectedSubtitleTrack?.id
                    ) {
                        player.engine.selectSubtitleTrack(track)
                        viewModel.closePlayerPanel()
                    }
                )
            }
            // Sidecar tracks can be taken back out; embedded ones cannot.
            session.subtitles.forEach { ref ->
                add(
                    PanelOption("Remove ${ref.label}", ref.detail, false) {
                        player.removeSubtitle(ref.url)
                        viewModel.closePlayerPanel()
                    }
                )
            }
        }
    }

    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel, options.isNotEmpty()) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(
        modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(NovaPalette.surface.copy(alpha = 0.97f))
            .padding(horizontal = 28.dp, vertical = NovaSpace.safeVertical)
            .focusGroup()
            // The panel is modal: D-pad cannot wander into the hidden chrome.
            .focusProperties { onExit = { cancelFocusChange() } }
    ) {
        Text(panel.label, style = NovaType.sectionTitle)
        Spacer(Modifier.height(16.dp))
        if (options.isEmpty()) {
            Text(
                text = when (panel) {
                    PlayerPanel.AUDIO -> "One audio track"
                    PlayerPanel.QUALITY -> "This source has one quality"
                    else -> "None available"
                },
                style = NovaType.meta
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Close", modifier = Modifier.focusRequester(first), onClick = viewModel::closePlayerPanel)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(options) { i, option ->
                    FocusableRow(
                        onClick = option.onPick,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        trailing = {
                            option.detail?.let {
                                Text(
                                    it,
                                    style = NovaType.meta,
                                    color = if (option.selected) NovaPalette.accent else NovaPalette.textMuted
                                )
                            }
                        }
                    ) {
                        Text(
                            text = option.label,
                            color = if (option.selected) NovaPalette.accent else NovaPalette.textPrimary,
                            style = NovaType.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Up next (ARVIO NextEpisodeOverlay): a card in the corner with a countdown.
 * OK plays now, BACK or Cancel stays on the finished episode.
 */
@Composable
private fun UpNextCard(next: UpNext, viewModel: NovaViewModel, modifier: Modifier) {
    var left by remember(next) { mutableIntStateOf(UP_NEXT_SECONDS) }
    val focus = remember(next) { FocusRequester() }
    LaunchedEffect(next) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        while (left > 0) {
            delay(1_000)
            left--
        }
        viewModel.playNext()
    }
    Column(
        modifier
            .padding(NovaSpace.safeHorizontal)
            .width(380.dp)
            .background(NovaPalette.surface.copy(alpha = 0.96f), RoundedCornerShape(18.dp))
            .padding(18.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!next.thumbnail.isNullOrBlank()) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
            )
        }
        Text(next.label, style = NovaType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Play in $left", modifier = Modifier.focusRequester(focus), onClick = viewModel::playNext)
            SecondaryButton("Cancel", onClick = viewModel::cancelUpNext)
        }
    }
}

/** Coarse tap step for the scrub bar; a held key repeats it. */
private const val SCRUB_STEP_MS = 10_000L

private const val UP_NEXT_SECONDS = 8
