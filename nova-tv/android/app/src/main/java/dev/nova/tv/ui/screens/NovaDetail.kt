package dev.nova.tv.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import dev.nova.tv.data.model.Stream
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.components.SkeletonRow
import dev.nova.tv.ui.components.StatusDot
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaType

/**
 * The detail surface.
 *
 * It renders above the browse screen rather than replacing it, so BACK closes
 * the overlay and returns the viewer exactly where they were in the row. Losing
 * scroll position on an accidental press is one of the fastest ways to make a
 * TV app feel hostile.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailOverlay(state: NovaState, viewModel: NovaViewModel) {
    val title = state.opened ?: return
    val streams = state.streams

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaPalette.background.copy(alpha = 0.97f))
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = title.name,
                style = NovaType.hero,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

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
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .width(820.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // Sources: the dots are real state, not ornament. Filled means the
            // source returned something for this title.
            Text("Sources", style = NovaType.sectionTitle)
            Spacer(Modifier.height(10.dp))

            when {
                state.streamsLoading -> {
                    Text("Asking every source…", style = NovaType.meta)
                    Spacer(Modifier.height(12.dp))
                    SkeletonRow(cardCount = 4)
                }
                streams == null -> Text("No sources asked yet.", style = NovaType.meta)
                streams.isEmpty() -> Text(
                    "No source has a playable stream for this title.",
                    style = NovaType.meta
                )
                else -> {
                    // Only decodable streams are offered. A page embed is not a
                    // stream, and framing another site's player is what this app
                    // deliberately does not do.
                    val playable = streams.filter { it.isPlayable }
                    if (playable.isEmpty()) {
                        Text(
                            "${streams.size} source(s) answered, but none returned a " +
                                "directly decodable stream.",
                            style = NovaType.meta
                        )
                    } else {
                        TvLazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(playable) { stream ->
                                StreamRow(stream) { viewModel.play(stream) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryButton("Close", viewModel::closeTitle)
                Text("BACK closes this panel", style = NovaType.meta)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamRow(stream: Stream, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(NovaShape.card)),
        colors = CardDefaults.colors(containerColor = NovaPalette.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusDot(active = true)
            Column(Modifier.width(560.dp)) {
                Text(
                    text = stream.name ?: "Stream",
                    style = NovaType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                stream.title?.let {
                    Text(it, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Play", onPlay)
        }
    }
}
