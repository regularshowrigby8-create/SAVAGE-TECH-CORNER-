package dev.nova.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.Stream
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.components.SkeletonRow
import dev.nova.tv.ui.components.StatusDot
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

/**
 * The detail surface.
 *
 * It renders above the browse screen rather than replacing it, so BACK closes
 * the overlay and focus returns to the card that opened it.
 *
 * TV changes: it now takes initial focus on "Play best" (it used to open with
 * focus left on the hidden card behind it), traps D-pad inside itself, sits in
 * the safe area, shows the backdrop, and every row is the whole focus target.
 * Play hands the *entire* ranked list to the player, so a dead first source
 * fails over instead of ending playback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetailOverlay(state: NovaState, viewModel: NovaViewModel) {
    val title = state.opened ?: return
    val streams = state.streams
    val playable = streams.orEmpty().filter { it.isPlayable }
    val primary = remember(title.id) { FocusRequester() }

    LaunchedEffect(title.id) {
        withFrameNanos { }
        runCatching { primary.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaPalette.background)
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
    ) {
        val art = title.background ?: title.poster
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Scrim: text must stay readable over any backdrop.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to NovaPalette.background,
                        0.55f to NovaPalette.background.copy(alpha = 0.92f),
                        1f to Color.Transparent
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = NovaSpace.safeHorizontal, vertical = NovaSpace.safeVertical)
        ) {
            Text(title.name, style = NovaType.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)

            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                title.year?.takeIf { it.isNotBlank() }?.let { Text(it, style = NovaType.meta) }
                if (title.rating > 0.0) {
                    Text("★ %.1f".format(title.rating), style = NovaType.meta, color = NovaPalette.accent)
                }
                title.genres.take(3).forEach { Text(it, style = NovaType.meta) }
            }

            if (title.description.isNotBlank()) {
                Text(
                    text = title.description,
                    style = NovaType.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth(0.62f)
                )
            }

            Spacer(Modifier.height(22.dp))

            // Primary actions. "Play best" is always the first focus target so
            // OK straight after opening plays the top-ranked source.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    label = when {
                        state.streamsLoading -> "Finding sources…"
                        playable.isEmpty() -> "No playable source"
                        else -> "Play best"
                    },
                    modifier = Modifier.focusRequester(primary)
                ) { if (playable.isNotEmpty()) viewModel.play(playable, 0) }
                SecondaryButton(
                    if (state.finderSearching) "Searching more sources…" else "Find more sources"
                ) { viewModel.findMoreSources() }
                SecondaryButton("Close", onClick = viewModel::closeTitle)
            }

            if (title.isSeries) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S${state.season} E${state.episode}", style = NovaType.sectionTitle)
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Season −") { viewModel.stepEpisode(-1, 0) }
                    SecondaryButton("Season +") { viewModel.stepEpisode(1, 0) }
                    SecondaryButton("Episode −") { viewModel.stepEpisode(0, -1) }
                    SecondaryButton("Episode +") { viewModel.stepEpisode(0, 1) }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sources", style = NovaType.sectionTitle)
                Spacer(Modifier.width(14.dp))
                Text(
                    text = buildString {
                        if (streams != null) append("${playable.size} playable of ${streams.size}")
                        if (state.finderStreams > 0) append("  ·  ${state.finderStreams} from Source Finder")
                    },
                    style = NovaType.meta
                )
            }
            Spacer(Modifier.height(10.dp))

            when {
                state.streamsLoading -> {
                    Text("Asking every installed source…", style = NovaType.meta)
                    Spacer(Modifier.height(12.dp))
                    SkeletonRow(cardCount = 4)
                }
                streams == null -> Text("No sources asked yet.", style = NovaType.meta)
                playable.isEmpty() -> Text(
                    text = if (streams.isEmpty()) {
                        "No installed source has a stream for this title. Try Find more sources."
                    } else {
                        "${streams.size} stream(s) answered, but none can be decoded here " +
                            "(torrents, web embeds or browser-only links). Try Find more sources."
                    },
                    style = NovaType.meta
                )
                else -> TvLazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(0.62f)
                ) {
                    itemsIndexed(playable) { i, stream ->
                        StreamRow(index = i, stream = stream) { viewModel.play(playable, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(index: Int, stream: Stream, onPlay: () -> Unit) {
    FocusableRow(
        onClick = onPlay,
        trailing = {
            Text(
                stream.quality.ifBlank { "Play" },
                style = NovaType.meta,
                color = NovaPalette.accent
            )
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusDot(active = true)
            Column {
                Text(
                    text = "${index + 1}.  ${stream.sourceName.ifBlank { stream.name ?: "Stream" }}",
                    style = NovaType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                (stream.title ?: stream.description ?: stream.name)?.let {
                    Text(
                        it.lineSequence().joinToString("  ·  ") { line -> line.trim() },
                        style = NovaType.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
