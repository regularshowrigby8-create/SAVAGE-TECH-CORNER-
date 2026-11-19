@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import dev.nova.tv.ui.screens.UpNext
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.Text
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.player.SkipSeconds
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.PlayerPanel
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

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
 *  - Sources, Audio and Subtitles panels: every stream the detail screen
 *    found is in one queue, and the viewer can switch without leaving.
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = exo
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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
                position = engine.currentPositionMs,
                duration = engine.durationMs,
                playing = engine.isPlaying,
                buffering = engine.isBuffering,
                sourceLabel = session.current?.label,
                sourceIndex = session.index,
                sourceCount = session.sources.size,
                playFocus = playFocus,
                viewModel = viewModel
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
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun Controls(
    player: NovaPlayer,
    title: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    buffering: Boolean,
    sourceLabel: String?,
    sourceIndex: Int,
    sourceCount: Int,
    playFocus: FocusRequester,
    viewModel: NovaViewModel
) {
    val preload by player.preloadStatus.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color.Transparent, NovaPalette.background.copy(alpha = 0.92f))
                )
            )
            .padding(horizontal = NovaSpace.safeHorizontal, vertical = NovaSpace.safeVertical)
            .focusGroup()
    ) {
        Text(title, style = NovaType.sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sourceLabel != null) {
            Text(
                text = sourceLabel,
                style = NovaType.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))
        ProgressBar(position, duration, preloadedMs = if (preload.active) preload.aheadMs else 0L)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                text = formatTime(position),
                style = NovaType.meta,
                color = NovaPalette.textPrimary
            )
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration) else "--:--", style = NovaType.meta)
        }
        Spacer(Modifier.height(14.dp))
        // Scrolls with focus: eight pills do not fit 960 dp, and none may be cut off.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryButton(
                if (playing) "Pause" else "Play",
                modifier = Modifier.focusRequester(playFocus)
            ) { player.togglePlay() }
            SecondaryButton("−${SkipSeconds.TAP / 1000}s") { player.seekBy(-SkipSeconds.TAP) }
            SecondaryButton("+${SkipSeconds.TAP / 1000}s") { player.seekBy(SkipSeconds.TAP) }
            // FastStream-style anti-buffering: Off → Preload → Multi-thread.
            SecondaryButton(
                label = if (preload.unavailable) "No preload" else preload.mode.label,
                icon = Icons.Filled.Refresh,
                active = preload.mode != dev.nova.tv.player.preload.PreloadMode.OFF && !preload.unavailable
            ) {
                val next = player.preloadMode().next()
                player.setPreload(next)
                viewModel.setPreload(next)
            }
            if (sourceCount > 1) SecondaryButton("Sources") { viewModel.openPlayerPanel(PlayerPanel.SOURCES) }
            SecondaryButton("Audio") { viewModel.openPlayerPanel(PlayerPanel.AUDIO) }
            SecondaryButton("Subtitles") { viewModel.openPlayerPanel(PlayerPanel.SUBTITLES) }
            SecondaryButton("Close", onClick = viewModel::stopPlayback)
        }
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long, preloadedMs: Long = 0L) {
    val fraction = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
    val preloaded = if (duration <= 0) 0f else ((position + preloadedMs).toFloat() / duration).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(NovaPalette.surfaceHigh, RoundedCornerShape(3.dp))
    ) {
        // What is already on disk ahead of the playhead.
        if (preloaded > fraction) {
            Box(
                Modifier
                    .fillMaxWidth(preloaded)
                    .fillMaxHeight()
                    .background(NovaPalette.textPrimary.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            )
        }
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(NovaPalette.accent, RoundedCornerShape(3.dp))
        )
    }
}

/** One option in a panel. */
private data class PanelOption(val label: String, val detail: String?, val selected: Boolean, val onPick: () -> Unit)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Panel(panel: PlayerPanel, player: NovaPlayer, viewModel: NovaViewModel, modifier: Modifier) {
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
        PlayerPanel.SUBTITLES -> listOf(
            PanelOption("Off", null, engine.selectedSubtitleTrack == null) {
                player.engine.selectSubtitleTrack(null)
                viewModel.closePlayerPanel()
            }
        ) + engine.subtitleTracks.map { track ->
            PanelOption(
                track.displayLabel(),
                null,
                track.id == engine.selectedSubtitleTrack?.id
            ) {
                player.engine.selectSubtitleTrack(track)
                viewModel.closePlayerPanel()
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
    var left by androidx.compose.runtime.remember(next) { mutableIntStateOf(UP_NEXT_SECONDS) }
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

private const val UP_NEXT_SECONDS = 8
