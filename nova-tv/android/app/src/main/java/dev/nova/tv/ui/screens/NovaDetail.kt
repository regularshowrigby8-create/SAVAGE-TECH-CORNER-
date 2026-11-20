package dev.nova.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.Stream
import dev.nova.tv.ui.components.EpisodeCard
import dev.nova.tv.ui.components.FilterChip
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.focus.PivotScroll
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

/**
 * A title's page.
 *
 * Sources are found in the background the moment it opens; the viewer sees
 * one Play button that becomes live as soon as anything playable is ranked.
 * The source list exists but stays behind a button, because most people
 * just want to press Play. Series get season chips and an episode row.
 */
@Composable
fun DetailScreen(state: NovaState, viewModel: NovaViewModel) {
    val detail = state.detail ?: return
    val title = detail.title
    val primary = remember(title.id) { FocusRequester() }
    val reduced = LocalReduceMotion.current

    LaunchedEffect(title.id) {
        withFrameNanos { }
        runCatching { primary.requestFocus() }
    }
    // The sheet held focus; when it closes, give it back to Play so the remote never goes dead.
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(detail.showSources) {
        if (sheetWasOpen && !detail.showSources) {
            withFrameNanos { }
            runCatching { primary.requestFocus() }
        }
        sheetWasOpen = detail.showSources
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NovaPalette.background)
            .focusProperties { onExit = { cancelFocusChange() } }
            .arvioDpadFocusGroup(enableFocusRestorer = false)
    ) {
        // Slow push-in on the backdrop: the page feels alive without moving anything you read.
        val zoom = if (reduced) 1f else {
            val t = rememberInfiniteTransition(label = "kenburns")
            val z by t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
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
                Brush.horizontalGradient(0f to NovaPalette.background, 0.45f to NovaPalette.background.copy(alpha = 0.82f), 0.85f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to NovaPalette.background)
            )
        )

        // One vertical scroll axis for the whole page. It used to be a fixed
        // Column with a weight() spacer, so on a short viewport the season
        // chips and the episode row were squeezed into the same band and the
        // bottom of the cards fell off screen with no way to reach it.
        val pageScroll = rememberScrollState()
        PivotScroll(parentFraction = 0.18f) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScroll)
                    .padding(start = NovaSpace.safeHorizontal + 24.dp, top = NovaSpace.safeVertical + 36.dp)
                    .padding(bottom = NovaSpace.safeVertical + 24.dp)
            ) {
                Column(Modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleArt(title, maxHeight = 120.dp)
                    InfoLine(detail)
                    val overview = detail.currentVideo?.overview?.takeIf { it.isNotBlank() } ?: title.description
                    if (overview.isNotBlank()) {
                        Text(overview, style = NovaType.body.copy(color = NovaPalette.textPrimary.copy(alpha = 0.85f)), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(26.dp))
                Actions(detail, viewModel, primary)

                if (detail.isSeries && title.videos.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Episodes(detail, viewModel)
                }
            }
        }

        AnimatedVisibility(
            visible = detail.showSources,
            enter = if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(260)) { it } + fadeIn(),
            exit = if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(200)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            SourceSheet(detail.sources.streams, viewModel, dualMode = state.route == Route.DUAL_AUDIO)
        }

        AnimatedVisibility(
            visible = detail.showDownload,
            enter = if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(260)) { it } + fadeIn(),
            exit = if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(200)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            DownloadSheet(detail, viewModel)
        }
    }
}

@Composable
private fun InfoLine(detail: DetailState) {
    val title = detail.title
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (detail.isSeries) {
            Text("S${detail.season} E${detail.episode}", style = NovaType.body, color = NovaPalette.textPrimary)
            detail.currentVideo?.let { Text(it.label, style = NovaType.body, color = NovaPalette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (title.shortInfo.isNotBlank()) Text(title.shortInfo, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail.sources.streams.firstOrNull()?.quality?.takeIf { it.isNotBlank() }?.let { Badge(it) }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = NovaType.meta.copy(color = NovaPalette.textPrimary),
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun Actions(detail: DetailState, viewModel: NovaViewModel, primary: FocusRequester) {
    val streams = detail.sources.streams
    val ready = streams.isNotEmpty()
    val resume = detail.resume
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        val label = when {
            ready && resume != null -> "Resume"
            ready -> "Play"
            detail.resolving -> "Finding sources" + dots()
            else -> "Not available yet"
        }
        PrimaryButton(
            label = label,
            icon = if (ready) Icons.Filled.PlayArrow else null,
            modifier = Modifier.focusRequester(primary)
        ) {
            if (ready) viewModel.play(0) else if (!detail.resolving) viewModel.retrySources()
        }
        if (ready && resume != null) SecondaryButton("From start") { viewModel.play(0, fromStart = true) }
        if (!ready && !detail.resolving) SecondaryButton("Try again", icon = Icons.Filled.Refresh, onClick = viewModel::retrySources)
        SecondaryButton(
            if (detail.inList) "In My List" else "My List",
            icon = if (detail.inList) Icons.Filled.Check else Icons.Filled.Add,
            onClick = viewModel::toggleMyList
        )
        if (streams.size > 1) SecondaryButton("Sources  ${streams.size}", onClick = viewModel::toggleSourcesSheet)
        if (ready) SecondaryButton("Download", onClick = viewModel::downloadBest)
    }
    if (resume != null && ready) {
        Box(Modifier.padding(top = 12.dp).width(160.dp).height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(resume.fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(NovaPalette.accent))
        }
    }
}

/** Three animated dots for the one thing that waits. */
@Composable
private fun dots(): String {
    if (LocalReduceMotion.current) return "…"
    val t = rememberInfiniteTransition(label = "dots")
    val n by t.animateFloat(0f, 3.99f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "n")
    return ".".repeat(n.toInt()).padEnd(3, ' ')
}

@Composable
private fun Episodes(detail: DetailState, viewModel: NovaViewModel) {
    val title = detail.title
    val seasons = title.seasons.filter { it > 0 }.ifEmpty { title.seasons }
    val episodes = title.episodes(detail.season)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (seasons.size > 1) {
            LazyRow(modifier = Modifier.arvioDpadFocusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(seasons, key = { it }) { s ->
                    FilterChip("Season $s", selected = s == detail.season) { viewModel.selectSeason(s) }
                }
            }
        }
        PivotScroll(parentFraction = 0.02f) {
            LazyRow(
                modifier = Modifier.arvioDpadFocusGroup(),
                contentPadding = PaddingValues(end = 120.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(episodes, key = { "${it.season}:${it.episode}" }) { v ->
                    EpisodeCard(
                        number = v.episode,
                        name = v.label,
                        thumbnail = v.thumbnail,
                        selected = v.episode == detail.episode,
                        progress = if (v.episode == detail.episode) detail.resume?.fraction ?: 0f else 0f,
                        onClick = {
                            if (v.episode == detail.episode && detail.sources.streams.isNotEmpty()) viewModel.play(0)
                            else viewModel.selectEpisode(v.season, v.episode)
                        }
                    )
                }
            }
        }
    }
}

/** Every ranked source, best first. Hidden until asked for. */
@Composable
private fun SourceSheet(streams: List<Stream>, viewModel: NovaViewModel, dualMode: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .width(560.dp)
            .fillMaxHeight()
            .background(NovaPalette.surface.copy(alpha = 0.98f))
            .padding(horizontal = 24.dp, vertical = NovaSpace.safeVertical)
            .focusGroup()
            // Modal: D-pad stays in the sheet until Back closes it.
            .focusProperties { onExit = { cancelFocusChange() } }
    ) {
        Text("Sources", style = NovaType.sectionTitle)
        Spacer(Modifier.height(14.dp))
        LazyColumn(modifier = Modifier.arvioDpadFocusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(streams, key = { i, s -> s.url ?: "$i" }) { i, stream ->
                FocusableRow(
                    onClick = { viewModel.play(i) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (dualMode && dev.nova.tv.finder.DualAudio.hasEnglishPlusOther(stream)) Badge("Dual")
                            stream.quality.takeIf { it.isNotBlank() }?.let { Badge(it) }
                        }
                    }
                ) {
                    Column {
                        Text(stream.sourceName.ifBlank { stream.name ?: "Source ${i + 1}" }, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val size = stream.sizeBytes.takeIf { it > 0 }?.let { dev.nova.tv.finder.FinderRules.formatSize(it) }
                        if (size != null) Text(size, style = NovaType.meta)
                    }
                }
            }
        }
    }
}

/**
 * Download options for TV. The old path queued the top-ranked source the moment
 * the button was pressed, with no say over which source or what quality. This
 * shows every downloadable source with its real quality and byte size.
 */
@Composable
private fun DownloadSheet(detail: DetailState, viewModel: NovaViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val streams = detail.sources.streams.filter { !it.url.isNullOrBlank() }
    Column(
        Modifier
            .width(560.dp)
            .fillMaxHeight()
            .background(NovaPalette.surface.copy(alpha = 0.98f))
            .padding(horizontal = 24.dp, vertical = NovaSpace.safeVertical)
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
    ) {
        Text("Download", style = NovaType.sectionTitle)
        Spacer(Modifier.height(4.dp))
        Text(
            if (detail.isSeries) "${detail.title.name}  ·  S${detail.season} E${detail.episode}" else detail.title.name,
            style = NovaType.meta,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        if (streams.isEmpty()) {
            Text("No downloadable source", style = NovaType.body, color = NovaPalette.textMuted)
        } else {
            LazyColumn(modifier = Modifier.arvioDpadFocusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(streams, key = { i, s -> s.url ?: "$i" }) { i, stream ->
                    val size = stream.sizeBytes.takeIf { it > 0 }?.let { dev.nova.tv.finder.FinderRules.formatSize(it) }
                    FocusableRow(
                        onClick = { viewModel.downloadStream(i) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        trailing = { if (i == 0) Badge("Best") }
                    ) {
                        Column {
                            Text(
                                stream.sourceName.ifBlank { stream.name ?: "Source ${i + 1}" },
                                style = NovaType.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOfNotNull(stream.quality.takeIf { it.isNotBlank() }, size).joinToString("  ·  ").ifBlank { "Size unknown" },
                                style = NovaType.meta
                            )
                        }
                    }
                }
            }
        }
    }
}
