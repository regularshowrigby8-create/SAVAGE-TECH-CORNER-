package dev.nova.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaType
import kotlinx.coroutines.delay

/**
 * The player surface.
 *
 * Before this file, `NovaPlayer` was headless: ExoPlayer was built, prepared and
 * started, but nothing was ever attached to a view. Pressing Play would have
 * produced sound with no picture, and nothing in the app would have reported an
 * error, because none of the player code is wrong -- the view was simply never
 * mounted.
 *
 * `PlayerView` is the non-Compose escape hatch that does the one thing Compose
 * cannot: own a Surface and hand it to ExoPlayer. `useController = false`
 * because the built-in controller is a touch UI with tiny targets; the chrome
 * here is built for a remote.
 *
 * BACK is handled here *before* the shell: BACK while playing should first
 * reveal the controls, and only leave on a second press. Leaving instantly on
 * the first press is how an accidental thumb ends a film.
 */
@Composable
fun PlayerSurface(
    player: NovaPlayer,
    title: String,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val failure by player.failure.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(true) }
    var leaveArmed by remember { mutableStateOf(false) }

    // Any input wakes the chrome, and disarms a pending exit.
    fun wake() {
        chromeVisible = true
        leaveArmed = false
    }

    // A failure is never silent. The player classifies it (bad-http, timeout,
    // container) and this is the only place that classification reaches a
    // viewer; without it a dead stream is an empty black screen.
    failure?.let { problem ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(problem.message, style = NovaType.sectionTitle, color = NovaPalette.danger)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (problem.retryable) {
                    "Reason: ${problem.reason} — this one is worth retrying."
                } else {
                    "Reason: ${problem.reason} — retrying will not help."
                },
                style = NovaType.meta
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Only offer a retry when the player says the failure is
                // recoverable. A Retry button on an undecodable container is a
                // dead button dressed as a helpful one.
                if (problem.retryable) {
                    PrimaryButton("Try again") { player.play() }
                }
                PrimaryButton("Close player", onLeave)
            }
        }
        DisposableEffect(problem) { onDispose { } }
    }

    // Chrome hides itself after inactivity, so the picture is never dimmed by a
    // permanent overlay. The timer restarts whenever the chrome is shown.
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(PlayerTimings.IDLE_MS)
            chromeVisible = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(NovaPalette.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        player.togglePlay()
                        wake()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        player.seekBy(-SkipSeconds.TAP)
                        wake()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        player.seekBy(SkipSeconds.TAP)
                        wake()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        player.seekBy(-SkipSeconds.HOLD)
                        wake()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        player.seekBy(SkipSeconds.HOLD)
                        wake()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BACK -> {
                        if (chromeVisible) {
                            onLeave()
                        } else {
                            // First BACK only wakes the chrome; the second leaves.
                            wake()
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // Keep the screen on while watching; a TV that dims during a
                    // film looks like a fault.
                    keepScreenOn = true
                    setPlayer(player.player)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NovaPalette.background.copy(alpha = 0.82f))
                    .padding(horizontal = 48.dp, vertical = 24.dp)
            ) {
                Text(title, style = NovaType.sectionTitle, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(
                        text = if (player.isPlaying()) "Playing" else "Paused",
                        style = NovaType.meta,
                        color = NovaPalette.accent
                    )
                    Text(
                        text = "LEFT / RIGHT skip ${SkipSeconds.TAP / 1000}s  ·  " +
                            "OK play-pause  ·  BACK close",
                        style = NovaType.meta
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(if (player.isPlaying()) "Pause" else "Play") {
                        player.togglePlay()
                        wake()
                    }
                    androidx.tv.material3.Surface(
                        onClick = { onLeave() },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                            containerColor = NovaPalette.surfaceHigh,
                            contentColor = NovaPalette.textPrimary
                        )
                    ) {
                        Text(
                            "Close",
                            style = NovaType.body,
                            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The player object is owned by the caller; only the view is unmounted
            // here. Releasing the engine is the caller's responsibility because
            // the same instance may be reused on the next title.
            player.pause()
        }
    }
}
