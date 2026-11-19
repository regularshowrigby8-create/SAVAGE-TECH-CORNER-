package dev.nova.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.nova.tv.NovaApplication
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.ui.player.PlayerSurface

/**
 * Owns the player for one playback request.
 *
 * Every source the detail screen found goes into one [NovaPlayer] as a ranked
 * queue. Picking a different source, or the automatic failover after a dead
 * one, happens inside that player, so there is exactly one decoder and one
 * surface no matter how many addons answered.
 *
 * The engine is keyed on the request token, created when playback starts and
 * released when the surface leaves composition: on TV hardware a leaked
 * decoder is the next video failing to start.
 */
@Composable
fun NovaPlayerHost(request: PlaybackRequest, viewModel: NovaViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as NovaApplication

    val player = remember(request.token) { NovaPlayer(context, app.http) }

    LaunchedEffect(request.token) {
        player.load(request.streams, request.startIndex, 0L, request.subtitles)
    }

    // Subtitles arrive after playback starts (they are a separate addon call).
    LaunchedEffect(request.token, request.subtitles) {
        player.setSubtitles(request.subtitles)
    }

    DisposableEffect(request.token) {
        onDispose { player.release() }
    }

    PlayerSurface(player = player, title = request.title, viewModel = viewModel)
}
