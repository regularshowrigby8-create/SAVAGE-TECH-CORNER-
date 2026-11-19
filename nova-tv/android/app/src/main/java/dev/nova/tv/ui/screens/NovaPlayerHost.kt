package dev.nova.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nova.tv.NovaApplication
import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.ui.player.PlayerSurface
import kotlinx.coroutines.delay

/**
 * Owns the player for one playback request.
 *
 * Every ranked source goes into one [NovaPlayer] as a failover queue, and
 * sources that keep arriving from the finders are appended while it plays.
 * The player is seeded with the bandwidth the probe measured, so the first
 * segment is fetched at the right quality instead of the 1 Mbit/s default.
 *
 * Progress is saved every few seconds and on exit, which is what Continue
 * Watching and Resume read.
 */
@Composable
fun NovaPlayerHost(
    request: PlaybackRequest,
    viewModel: NovaViewModel,
    /** The chrome drawn over the video: D-pad on TV, touch on phones. */
    surface: @Composable (player: NovaPlayer, title: String) -> Unit = { player, title ->
        PlayerSurface(player = player, title = title, viewModel = viewModel)
    }
) {
    val context = LocalContext.current
    val app = context.applicationContext as NovaApplication
    val state by viewModel.state.collectAsStateWithLifecycle()

    val player = remember(request.token) {
        NovaPlayer(
            context, app.http, request.bandwidthEstimate,
            preferredAudioLanguage = if (state.preferEnglishAudio) "en" else null,
            initialPreload = state.preload
        )
    }

    LaunchedEffect(request.token) {
        player.load(request.streams, request.startIndex, request.startPositionMs, request.subtitles)
    }

    // Subtitles arrive after playback starts (a separate addon call).
    LaunchedEffect(request.token, request.subtitles) {
        player.setSubtitles(request.subtitles)
    }

    // Late sources join the failover queue.
    val live = state.detail?.takeIf { it.meta.id == request.meta.id && it.season == request.season && it.episode == request.episode }
        ?.sources?.streams
    LaunchedEffect(request.token, live) {
        if (!live.isNullOrEmpty()) player.appendSources(live)
    }

    LaunchedEffect(request.token) {
        while (true) {
            delay(PROGRESS_EVERY_MS)
            if (player.session.value.started) {
                viewModel.saveProgress(request, player.resumePosition(), player.durationMs(), player.session.value.current)
            }
        }
    }

    val session by player.session.collectAsStateWithLifecycle()
    LaunchedEffect(session.ended) {
        if (session.ended) {
            viewModel.saveProgress(request, player.durationMs(), player.durationMs(), session.current)
            viewModel.onPlaybackEnded()
        }
    }

    DisposableEffect(request.token) {
        onDispose {
            if (player.session.value.started && !player.session.value.ended) {
                viewModel.saveProgress(request, player.resumePosition(), player.durationMs(), player.session.value.current)
            }
            player.release()
        }
    }

    surface(player, request.title)
}

private const val PROGRESS_EVERY_MS = 10_000L
