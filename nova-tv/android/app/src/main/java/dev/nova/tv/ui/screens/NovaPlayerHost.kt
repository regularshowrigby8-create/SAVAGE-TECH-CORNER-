package dev.nova.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.nova.tv.NovaApplication
import dev.nova.tv.data.model.Stream
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.ui.player.PlayerSurface

/**
 * Owns the player for the duration of one title.
 *
 * The engine is created when playback starts and released when the surface
 * leaves composition, so a failed stream cannot leak a decoder -- on TV hardware
 * a leaked decoder is not a warning in a log, it is the next video failing to
 * start.
 */
@Composable
fun NovaPlayerHost(
    stream: Stream,
    title: String,
    startPositionMs: Long = 0L,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NovaApplication

    val player = remember(stream) { NovaPlayer(context, app.appScope) }

    LaunchedEffect(stream) {
        val url = stream.url ?: return@LaunchedEffect
        val headers = stream.behaviorHints?.proxyHeaders?.request ?: emptyMap()
        player.prepare(url, startPositionMs, headers)
    }

    DisposableEffect(stream) {
        onDispose { player.release() }
    }

    PlayerSurface(player = player, title = title, onLeave = onLeave)
}
