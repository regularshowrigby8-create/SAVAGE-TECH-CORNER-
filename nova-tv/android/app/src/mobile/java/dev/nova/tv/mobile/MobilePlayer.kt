@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.mobile

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.player.SkipSeconds
import dev.nova.tv.player.SubtitleStyle
import dev.nova.tv.player.applySubtitleStyle
import dev.nova.tv.player.preload.PreloadMode
import dev.nova.tv.ui.player.BufferingOverlay
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.PlayerPanel
import dev.nova.tv.ui.screens.UpNext
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import kotlinx.coroutines.delay

private val Speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
private const val DOUBLE_TAP_MS = 10_000L

/**
 * The touch chrome over the one shared player (the same NovaPlayer, failover
 * queue, preloader and DNS the TV build uses).
 *
 *  - Tap shows or hides the controls; double-tap the left or right third to
 *    skip 10 s; drag up/down on the left for brightness, on the right for
 *    volume.
 *  - Landscape and full screen while playing; both are handed back on exit.
 *  - Leaving the app floats the video in picture-in-picture.
 */
@Composable
fun MobilePlayerSurface(player: NovaPlayer, title: String, viewModel: NovaViewModel) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()
    val chrome by viewModel.chromeState.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val buffering by player.buffering.collectAsStateWithLifecycle()
    val pip = LocalPip.current
    val panel = ui.playerPanel
    val failure = session.failure
    val activity = LocalContext.current as? Activity

    FullscreenLandscape(inPip = pip.active)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Entering PiP also stops the activity, but it must not stop the film.
        // Only a genuine background stop (no PiP window) pauses playback.
        if (activity?.isInPictureInPictureMode != true) player.pause()
    }
    DisposableEffect(Unit) {
        onDispose { MobilePlayback.active = false }
    }
    // Only a playing film should auto-enter PiP. Setting this unconditionally
    // meant pressing Home on a paused film floated a frozen window.
    LaunchedEffect(engine.isPlaying) {
        MobilePlayback.active = engine.isPlaying
    }

    LaunchedEffect(engine.isPlaying, engine.isBuffering, session.ended, failure) {
        viewModel.chrome.update {
            it.copy(playing = engine.isPlaying, buffering = engine.isBuffering, ended = session.ended, error = failure?.message)
        }
    }

    val showChrome = chrome.chromeVisible && failure == null && panel == null && ui.upNext == null && !pip.active
    val chromeShown by rememberUpdatedState(showChrome)

    var seekFlash by remember { mutableStateOf<SeekFlash?>(null) }
    var level by remember { mutableStateOf<Level?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val exo = player.player
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    this.player = exo
                }.also { playerView = it }
            },
            modifier = Modifier.fillMaxSize()
        )
        LaunchedEffect(playerView, ui.subtitleStyle) {
            playerView?.applySubtitleStyle(ui.subtitleStyle)
        }

        if (!pip.active) {
            GestureLayer(
                onTap = { if (chromeShown) viewModel.chrome.hideNow() else viewModel.chrome.onUserActive() },
                onDoubleTap = { side ->
                    when (side) {
                        Side.LEFT -> { player.seekBy(-DOUBLE_TAP_MS); seekFlash = SeekFlash(Side.LEFT, System.nanoTime()) }
                        Side.RIGHT -> { player.seekBy(DOUBLE_TAP_MS); seekFlash = SeekFlash(Side.RIGHT, System.nanoTime()) }
                        Side.CENTER -> player.togglePlay()
                    }
                },
                onLevel = { level = it }
            )
        }

        // Buffering is shown once, in the middle, with its percentage, and
        // only while playback is actually waiting (never over a paused film).
        if (!showChrome && failure == null && !pip.active && engine.isBuffering) {
            buffering?.let { status ->
                BufferingOverlay(status, Modifier.align(Alignment.Center), ringSize = 88.dp)
            }
        }

        seekFlash?.let { flash ->
            LaunchedEffect(flash.at) { delay(650); seekFlash = null }
            Text(
                if (flash.side == Side.LEFT) "−10s" else "+10s",
                style = MType.section,
                modifier = Modifier
                    .align(if (flash.side == Side.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        level?.let { l ->
            LevelPill(l, Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        }

        if (!pip.active) {
            session.notice?.let { notice ->
                Text(
                    notice,
                    style = MType.meta.copy(color = NovaPalette.textPrimary),
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(top = 12.dp)
                        .background(NovaPalette.surface.copy(alpha = 0.92f), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(player, title, viewModel, onPip = { pip.enter() })
        }

        failure?.let { problem ->
            if (!pip.active) {
                Column(
                    Modifier.fillMaxSize().background(NovaPalette.background.copy(alpha = 0.92f)).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("This one won't play right now", style = MType.title)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (problem.retryable) {
                            Button(onClick = player::retry) { Text("Try again") }
                        }
                        FilledTonalButton(onClick = { viewModel.openPlayerPanel(PlayerPanel.SOURCES) }) { Text("Other sources") }
                        FilledTonalButton(onClick = viewModel::stopPlayback) { Text("Close") }
                    }
                }
            }
        }

        if (!pip.active) {
            ui.upNext?.let { next -> UpNextCard(next, viewModel, Modifier.align(Alignment.BottomEnd)) }
        }

        AnimatedVisibility(
            visible = panel != null && !pip.active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = viewModel::closePlayerPanel)
            )
        }
        AnimatedVisibility(
            visible = panel != null && !pip.active,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            var last by remember { mutableStateOf(PlayerPanel.SOURCES) }
            if (panel != null) last = panel
            Panel(last, player, viewModel, ui.subtitleStyle)
        }
    }
}

/**
 * Landscape, edge to edge, bars hidden; everything handed back on exit.
 *
 * Keyed on [inPip]: a PiP window owns its own orientation and chrome, so
 * forcing SENSOR_LANDSCAPE while floating made the window flip and flicker on
 * every transition. In PiP the activity is left alone; on the way back the
 * full-screen treatment is reapplied. [original] is captured once, so the
 * phone returns to the orientation it had before playback, not to the
 * landscape the PiP branch happened to leave behind.
 */
@Composable
private fun FullscreenLandscape(inPip: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    val original = remember(activity) { activity.requestedOrientation }
    DisposableEffect(inPip) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        if (inPip) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = original
            // Brightness set by the swipe gesture belongs to playback only.
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

private enum class Side { LEFT, CENTER, RIGHT }
private data class SeekFlash(val side: Side, val at: Long)
private data class Level(val brightness: Boolean, val value: Float)

@Composable
private fun GestureLayer(onTap: () -> Unit, onDoubleTap: (Side) -> Unit, onLevel: (Level?) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val tap by rememberUpdatedState(onTap)
    val double by rememberUpdatedState(onDoubleTap)
    val report by rememberUpdatedState(onLevel)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { o ->
                        val third = size.width / 3f
                        double(if (o.x < third) Side.LEFT else if (o.x > third * 2) Side.RIGHT else Side.CENTER)
                    }
                )
            }
            .pointerInput(Unit) {
                var brightness = true
                var value = 0f
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        brightness = start.x < size.width / 2f
                        value = if (brightness) {
                            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        } else {
                            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                        }
                        report(Level(brightness, value))
                    },
                    onDragEnd = { report(null) },
                    onDragCancel = { report(null) },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        value = (value - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                        if (brightness) {
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value.coerceAtLeast(0.02f) } }
                        } else {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVolume).toInt(), 0)
                        }
                        report(Level(brightness, value))
                    }
                )
            }
    )
}

@Composable
private fun LevelPill(level: Level, modifier: Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (level.brightness) "Brightness" else "Volume", style = MType.label)
        Spacer(Modifier.width(12.dp))
        LinearProgressIndicator(
            progress = { level.value },
            modifier = Modifier.width(120.dp).height(4.dp),
            color = NovaPalette.textPrimary,
            trackColor = Color.White.copy(alpha = 0.25f),
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun Controls(player: NovaPlayer, title: String, viewModel: NovaViewModel, onPip: () -> Unit) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()
    val preload by player.preloadStatus.collectAsStateWithLifecycle()
    val buffering by player.buffering.collectAsStateWithLifecycle()
    var speed by remember { mutableIntStateOf(0) }
    val upNext = remember(title) { viewModel.nextEpisode() }

    Box(Modifier.fillMaxSize()) {
        // Scrims top and bottom so white text reads over any frame.
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
        )

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::stopPlayback) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MType.section, maxLines = 1, overflow = TextOverflow.Ellipsis)
                session.current?.label?.let { Text(it, style = MType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButtonPill("PiP", onClick = onPip)
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkipButton(back = true) { player.seekBy(-SkipSeconds.TAP); viewModel.chrome.onUserActive() }
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { player.togglePlay(); viewModel.chrome.onUserActive() },
                contentAlignment = Alignment.Center
            ) {
                when {
                    engine.isBuffering -> Text("${buffering?.percent ?: 0}%", style = MType.section)
                    engine.isPlaying -> Icon(PauseIcon, contentDescription = "Pause", modifier = Modifier.size(40.dp))
                    else -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(44.dp))
                }
            }
            SkipButton(back = false) { player.seekBy(SkipSeconds.TAP); viewModel.chrome.onUserActive() }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            SeekBar(
                position = engine.currentPositionMs,
                duration = engine.durationMs,
                buffered = engine.bufferedPositionMs,
                preloadedAhead = if (preload.active) preload.aheadMs else 0L,
                onScrub = { viewModel.chrome.onUserActive() },
                onSeek = { player.engine.seekTo(it) }
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${dev.nova.tv.ui.player.formatTime(engine.currentPositionMs)} / ${if (engine.durationMs > 0) dev.nova.tv.ui.player.formatTime(engine.durationMs) else "--:--"}",
                    style = MType.label,
                    modifier = Modifier.padding(end = 8.dp)
                )
                // FastStream-style anti-buffering: Off → Preload → Multi preload.
                TextButtonPill(
                    if (preload.unavailable) "No preload" else preload.mode.label,
                    active = preload.mode != PreloadMode.OFF && !preload.unavailable
                ) {
                    val next = player.preloadMode().next()
                    player.setPreload(next)
                    viewModel.setPreload(next)
                    viewModel.chrome.onUserActive()
                }
                if (session.sources.size > 1) TextButtonPill("Sources  ${session.sources.size}") { viewModel.openPlayerPanel(PlayerPanel.SOURCES) }
                TextButtonPill("Audio") { viewModel.openPlayerPanel(PlayerPanel.AUDIO) }
                TextButtonPill("Subtitles") { viewModel.openPlayerPanel(PlayerPanel.SUBTITLES) }
                TextButtonPill(if (speed == 0) "Speed" else "${Speeds[speed]}x", active = speed != 0) {
                    speed = (speed + 1) % Speeds.size
                    player.setRate(Speeds[speed])
                    viewModel.chrome.onUserActive()
                }
                if (upNext != null) TextButtonPill("Next episode", onClick = viewModel::playNext)
            }
        }
    }
}

@Composable
private fun TextButtonPill(label: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MType.label.copy(color = if (active) Color.White else NovaPalette.textPrimary),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) NovaPalette.accent else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun SkipButton(back: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = if (back) "Back 10 seconds" else "Forward 10 seconds",
            modifier = Modifier.size(40.dp).graphicsLayer { if (back) scaleX = -1f }
        )
        Text("${SkipSeconds.TAP / 1000}", style = MType.label)
    }
}

/**
 * Seek bar that shows what is safe to watch: the player's own buffer and,
 * when preload is on, how far ahead the preloader has stored the file.
 */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    preloadedAhead: Long,
    onScrub: () -> Unit,
    onSeek: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val total by rememberUpdatedState(duration)
    fun frac(ms: Long) = if (duration <= 0) 0f else (ms.toFloat() / duration).coerceIn(0f, 1f)
    val played = if (dragging) dragFraction else frac(position)

    BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        if (total > 0) seek(((o.x / widthPx).coerceIn(0f, 1f) * total).toLong())
                        scrub()
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / widthPx).coerceIn(0f, 1f); scrub() },
                        onDragEnd = {
                            if (total > 0) seek((dragFraction * total).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            dragFraction = (dragFraction + dx / widthPx).coerceIn(0f, 1f)
                            scrub()
                        }
                    )
                }
        ) {
            val h = if (dragging) 6.dp.toPx() else 4.dp.toPx()
            val y = size.height / 2f - h / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, h), r)
            val ahead = frac(maxOf(buffered, position + preloadedAhead))
            if (ahead > 0f) drawRoundRect(Color.White.copy(alpha = 0.42f), Offset(0f, y), Size(size.width * ahead, h), r)
            drawRoundRect(NovaPalette.accent, Offset(0f, y), Size(size.width * played, h), r)
            drawCircle(NovaPalette.accent, radius = if (dragging) 9.dp.toPx() else 6.dp.toPx(), center = Offset(size.width * played, size.height / 2f))
        }
        if (dragging && duration > 0) {
            Text(
                dev.nova.tv.ui.player.formatTime((dragFraction * duration).toLong()),
                style = MType.label,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = ((maxWidth - 56.dp) * dragFraction).coerceAtLeast(0.dp))
                    .graphicsLayer { translationY = -36.dp.toPx() }
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

private data class PanelOption(val label: String, val detail: String?, val selected: Boolean, val onPick: () -> Unit)

@Composable
private fun Panel(
    panel: PlayerPanel,
    player: NovaPlayer,
    viewModel: NovaViewModel,
    subtitleStyle: SubtitleStyle
) {
    val engine by player.engineState.collectAsStateWithLifecycle()
    val session by player.session.collectAsStateWithLifecycle()
    val options: List<PanelOption> = when (panel) {
        PlayerPanel.SOURCES -> session.sources.mapIndexed { i, stream ->
            val tried = i in session.tried && i != session.index
            PanelOption(stream.label, if (i == session.index) "Playing" else if (tried) "Failed" else null, i == session.index) {
                player.selectSource(i)
                viewModel.closePlayerPanel()
            }
        }
        PlayerPanel.AUDIO -> engine.audioTracks.map { track ->
            val detail = listOfNotNull(
                track.language?.uppercase(),
                track.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
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
        ) + listOf(
            // Style rows stay open so the change is visible over the picture.
            PanelOption("Caption style", subtitleStyle.tone.label, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextTone())
            },
            PanelOption("Caption size", subtitleStyle.sizeLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextSize())
            },
            PanelOption("Caption box", subtitleStyle.backgroundLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextBackground())
            },
            PanelOption("Caption height", subtitleStyle.bottomLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextBottom())
            }
        ) + engine.subtitleTracks.map { track ->
            PanelOption(track.displayLabel(), null, track.id == engine.selectedSubtitleTrack?.id) {
                player.engine.selectSubtitleTrack(track)
                viewModel.closePlayerPanel()
            }
        }
    }
    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(NovaPalette.surface.copy(alpha = 0.98f))
            .safeDrawingPadding()
            .padding(vertical = 12.dp)
    ) {
        Text(panel.label, style = MType.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (options.isEmpty()) {
            Text(if (panel == PlayerPanel.AUDIO) "One audio track" else "None available", style = MType.meta, modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                itemsIndexed(options) { _, option ->
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = option.onPick).padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option.label,
                            style = MType.body.copy(color = if (option.selected) NovaPalette.accent else NovaPalette.textPrimary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        option.detail?.let { Text(it, style = MType.meta, modifier = Modifier.padding(start = 8.dp)) }
                        if (option.selected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = NovaPalette.accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Up next with a countdown; Cancel stays on the finished episode. */
@Composable
private fun UpNextCard(next: UpNext, viewModel: NovaViewModel, modifier: Modifier) {
    var left by remember(next) { mutableIntStateOf(UP_NEXT_SECONDS) }
    val reduced = LocalReduceMotion.current
    LaunchedEffect(next) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        viewModel.playNext()
    }
    Column(
        modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .width(300.dp)
            .background(NovaPalette.surface.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!next.thumbnail.isNullOrBlank()) {
            AsyncImage(
                model = next.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
            )
        }
        Text(next.label, style = MType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!reduced) {
            LinearProgressIndicator(
                progress = { 1f - left / UP_NEXT_SECONDS.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = NovaPalette.accent,
                trackColor = Color.White.copy(alpha = 0.15f),
                drawStopIndicator = {}
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::playNext,
                colors = ButtonDefaults.buttonColors(containerColor = NovaPalette.textPrimary, contentColor = NovaPalette.background)
            ) { Text("Play in $left", style = MType.label.copy(color = NovaPalette.background)) }
            FilledTonalButton(onClick = viewModel::cancelUpNext) { Text("Cancel", style = MType.label) }
        }
    }
}

private const val UP_NEXT_SECONDS = 8

/** Material "pause" (two bars). material-icons-core does not ship it. */
private val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes("M6,19h4V5H6v14zM14,5v14h4V5h-4z"), fill = SolidColor(Color.White))
        .build()
}
