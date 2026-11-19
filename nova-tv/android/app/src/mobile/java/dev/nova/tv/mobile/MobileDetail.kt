package dev.nova.tv.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.Video
import dev.nova.tv.finder.DualAudio
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.ui.screens.DetailState
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette

/**
 * A title's page. Sources are found in the background from the moment it
 * opens; Play goes live as soon as one is ranked. The full list is one tap
 * away in a bottom sheet for anyone who wants to choose.
 */
@Composable
fun MobileDetail(state: NovaState, viewModel: NovaViewModel) {
    val detail = state.detail ?: return
    val title = detail.title
    val reduced = LocalReduceMotion.current
    val headerHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp.coerceIn(260.dp, 460.dp)

    Box(Modifier.fillMaxSize().background(NovaPalette.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "header") {
                Box(Modifier.fillMaxWidth().height(headerHeight)) {
                    val zoom = if (reduced) 1f else {
                        val t = rememberInfiniteTransition(label = "kenburns")
                        val z by t.animateFloat(1f, 1.07f, infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
                        z
                    }
                    val art = title.background ?: title.poster
                    if (!art.isNullOrBlank()) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to NovaPalette.background.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to NovaPalette.background)
                        )
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = ScreenPadding)) {
                        if (!title.logo.isNullOrBlank()) {
                            AsyncImage(
                                model = title.logo,
                                contentDescription = title.name,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier.fillMaxWidth(0.7f).height(90.dp)
                            )
                        } else {
                            Text(title.name, style = MType.hero, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "info") { InfoLine(detail) }
            item(key = "actions") { Actions(detail, viewModel) }
            item(key = "overview") { Overview(detail) }
            if (detail.isSeries && title.videos.isNotEmpty()) {
                item(key = "seasons") { Seasons(detail, viewModel) }
                items(title.episodes(detail.season), key = { "ep:${it.season}:${it.episode}" }) { v ->
                    EpisodeRow(v, detail, viewModel)
                }
            }
        }

        IconButton(
            onClick = viewModel::closeTitle,
            colors = IconButtonDefaults.iconButtonColors(containerColor = NovaPalette.background.copy(alpha = 0.55f)),
            modifier = Modifier.statusBarsPadding().padding(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        if (detail.showSources) {
            SourcesSheet(detail.sources.streams, detail.resolving, viewModel)
        }
    }
}

@Composable
private fun InfoLine(detail: DetailState) {
    val title = detail.title
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (title.rating > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFC94D), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("%.1f".format(title.rating), style = MType.label)
            }
        }
        if (title.shortInfo.isNotBlank()) Text(title.shortInfo, style = MType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        detail.sources.streams.firstOrNull()?.quality?.takeIf { it.isNotBlank() }?.let { Badge(it) }
        if (detail.sources.streams.any { DualAudio.hasEnglishPlusOther(it) }) Badge("Dual", accent = true)
    }
}

@Composable
private fun Actions(detail: DetailState, viewModel: NovaViewModel) {
    val streams = detail.sources.streams
    val ready = streams.isNotEmpty()
    val resume = detail.resume
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val label = when {
            ready && resume != null -> "Resume"
            ready && detail.isSeries -> "Play  S${detail.season} E${detail.episode}"
            ready -> "Play"
            detail.resolving -> "Finding sources"
            else -> "Not available yet"
        }
        Button(
            onClick = { if (ready) viewModel.play(0) else if (!detail.resolving) viewModel.retrySources() },
            enabled = ready || !detail.resolving,
            colors = ButtonDefaults.buttonColors(
                containerColor = NovaPalette.textPrimary,
                contentColor = NovaPalette.background,
                disabledContainerColor = NovaPalette.surfaceHigh,
                disabledContentColor = NovaPalette.textPrimary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            when {
                ready -> Icon(Icons.Filled.PlayArrow, contentDescription = null)
                detail.resolving -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NovaPalette.accent)
                else -> Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (!ready && !detail.resolving) "Try again" else label, style = MType.section.copy(color = if (ready || !detail.resolving) NovaPalette.background else NovaPalette.textPrimary))
        }
        if (resume != null && ready) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(3.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(resume.fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(NovaPalette.accent, RoundedCornerShape(2.dp)))
                }
                TextButton(onClick = { viewModel.play(0, fromStart = true) }) { Text("From start", style = MType.label) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionIcon(
                label = if (detail.inList) "In My List" else "My List",
                icon = if (detail.inList) Icons.Filled.Check else Icons.Filled.Add,
                onClick = viewModel::toggleMyList
            )
            ActionIcon(
                label = if (streams.isEmpty()) "Sources" else "Sources  ${streams.size}",
                icon = Icons.AutoMirrored.Filled.List,
                onClick = viewModel::toggleSourcesSheet
            )
            ActionIcon(label = "Search again", icon = Icons.Filled.Refresh, onClick = viewModel::retrySources)
        }
    }
}

@Composable
private fun ActionIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MType.label.copy(color = NovaPalette.textMuted))
    }
}

@Composable
private fun Overview(detail: DetailState) {
    val text = detail.currentVideo?.overview?.takeIf { it.isNotBlank() } ?: detail.title.description
    if (text.isBlank()) return
    var expanded by remember(detail.streamId) { mutableStateOf(false) }
    Text(
        text,
        style = MType.body.copy(color = NovaPalette.textPrimary.copy(alpha = 0.86f)),
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
            .padding(horizontal = ScreenPadding, vertical = 12.dp)
    )
}

@Composable
private fun Seasons(detail: DetailState, viewModel: NovaViewModel) {
    val seasons = detail.title.seasons.filter { it > 0 }.ifEmpty { detail.title.seasons }
    if (seasons.size <= 1) {
        Text("Episodes", style = MType.section, modifier = Modifier.padding(start = ScreenPadding, top = 14.dp, bottom = 6.dp))
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    ) {
        items(seasons, key = { it }) { s ->
            FilterChip(
                selected = s == detail.season,
                onClick = { viewModel.selectSeason(s) },
                label = { Text(if (s == 0) "Specials" else "Season $s", style = MType.label) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NovaPalette.textPrimary,
                    selectedLabelColor = NovaPalette.background
                )
            )
        }
    }
}

@Composable
private fun EpisodeRow(v: Video, detail: DetailState, viewModel: NovaViewModel) {
    val selected = v.episode == detail.episode && v.season == detail.season
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (selected && detail.sources.streams.isNotEmpty()) viewModel.play(0)
                else viewModel.selectEpisode(v.season, v.episode)
            }
            .background(if (selected) NovaPalette.surface else Color.Transparent)
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(NovaPalette.surfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            if (!v.thumbnail.isNullOrBlank()) {
                AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (selected) {
                Box(Modifier.size(34.dp).background(NovaPalette.background.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                    if (detail.sources.streams.isEmpty() && detail.resolving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NovaPalette.textPrimary)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                    }
                }
            }
            val progress = if (selected) detail.resume?.fraction ?: 0f else 0f
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(NovaPalette.accent))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${v.episode}.  ${v.label}",
                style = MType.body.copy(color = if (selected) NovaPalette.textPrimary else NovaPalette.textPrimary.copy(alpha = 0.9f)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            v.overview?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MType.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Every ranked source, best first. Tap one to play it; the rest stay as failover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesSheet(streams: List<Stream>, resolving: Boolean, viewModel: NovaViewModel) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = viewModel::toggleSourcesSheet,
        sheetState = sheet,
        containerColor = NovaPalette.surface
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sources", style = MType.title, modifier = Modifier.weight(1f))
            if (resolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NovaPalette.accent)
        }
        if (streams.isEmpty()) {
            EmptyNote(if (resolving) "Searching" else "None found yet")
        } else {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(streams, key = { i, s -> s.url ?: "$i" }) { i, stream ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.play(i) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stream.sourceName.ifBlank { stream.name?.lineSequence()?.firstOrNull() ?: "Source ${i + 1}" },
                                style = MType.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            stream.sizeBytes.takeIf { it > 0 }?.let { Text(FinderRules.formatSize(it), style = MType.meta) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (i == 0) Badge("Best", accent = true)
                            if (DualAudio.hasEnglishPlusOther(stream)) Badge("Dual")
                            stream.quality.takeIf { it.isNotBlank() }?.let { Badge(it) }
                        }
                    }
                    if (i < streams.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
    }
}
