package dev.nova.tv.mobile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.ShimmerBox
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.Shelf
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

@Composable
fun MobileHome(state: NovaState, viewModel: NovaViewModel, onDualAudio: () -> Unit) = SpotlightFeed(
    shelves = state.shelves,
    hero = state.hero,
    loading = state.homeLoading,
    error = state.homeError,
    viewModel = viewModel,
    onRefresh = viewModel::refresh,
    onMoreShelves = viewModel::loadMoreShelves,
    topBar = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = ScreenPadding, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NOVA", style = MType.wordmark)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDualAudio) { Icon(Icons.Filled.Star, contentDescription = "Dual Audio") }
        }
    }
)

/** Dual Audio: international titles; their sources rank English plus the original first. */
@Composable
fun MobileDualAudio(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) = SpotlightFeed(
    shelves = state.dualShelves,
    hero = state.dualShelves.firstOrNull()?.items.orEmpty().filter { !it.background.isNullOrBlank() }.take(6),
    loading = state.dualLoading || (state.dualShelves.isEmpty() && state.homeLoading),
    error = null,
    viewModel = viewModel,
    onRefresh = viewModel::loadDualAudio,
    onMoreShelves = viewModel::loadMoreDualShelves,
    topBar = { ScreenHeader("Dual Audio", onBack = onBack) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotlightFeed(
    shelves: List<Shelf>,
    hero: List<MetaPreview>,
    loading: Boolean,
    error: String?,
    viewModel: NovaViewModel,
    onRefresh: () -> Unit,
    onMoreShelves: () -> Unit,
    topBar: @Composable () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(shelves.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (shelves.isNotEmpty() && last >= listState.layoutInfo.totalItemsCount - 3) onMoreShelves() }
    }
    PullToRefreshBox(
        isRefreshing = loading && shelves.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "hero") {
                if (hero.isNotEmpty()) Spotlight(hero, onOpen = viewModel::openTitle, onPlay = viewModel::openAndPlay)
                else if (loading) ShimmerBox(Modifier.fillMaxWidth().height(heroHeight()), radius = 0.dp)
                else Spacer(Modifier.statusBarsPadding().height(56.dp))
            }
            if (error != null) {
                item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Can't reach the catalogue", style = MType.section)
                        Spacer(Modifier.height(8.dp))
                        Text(error, style = MType.body)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onRefresh) { Text("Try again") }
                    }
                }
            }
            if (loading && shelves.isEmpty()) {
                items(4, key = { "shimmer$it" }) { ShimmerShelf(landscape = it == 0) }
            }
            items(shelves, key = { it.key }) { shelf ->
                if (shelf.items.isNotEmpty() || shelf.loading) {
                    Column {
                        ShelfRow(shelf, onOpen = viewModel::openTitle, onNearEnd = { viewModel.loadMoreInShelf(shelf.key) })
                        if (shelf.error != null) {
                            Row(Modifier.padding(horizontal = ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                                Text("Couldn't load more titles", style = MType.body, modifier = Modifier.weight(1f))
                                Button(onClick = { viewModel.loadMoreInShelf(shelf.key, retry = true) }) { Text("Try again") }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(28.dp)) }
        }
        // Over the art, not part of the scroll: the wordmark stays put.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NovaPalette.background.copy(alpha = 0.75f), Color.Transparent)))
        ) { topBar() }
    }
}

@Composable
private fun heroHeight() = (LocalConfiguration.current.screenHeightDp * 0.62f).dp.coerceIn(360.dp, 560.dp)

/** Swipeable spotlight that advances on its own; pages parallax as they move. */
@Composable
private fun Spotlight(items: List<MetaPreview>, onOpen: (MetaPreview) -> Unit, onPlay: (MetaPreview) -> Unit) {
    val pager = rememberPagerState { items.size }
    val reduced = LocalReduceMotion.current
    LaunchedEffect(pager.currentPage, items.size, pager.isScrollInProgress) {
        if (reduced || items.size < 2 || pager.isScrollInProgress) return@LaunchedEffect
        delay(6_500)
        pager.animateScrollToPage((pager.currentPage + 1) % items.size)
    }
    Box(Modifier.fillMaxWidth().height(heroHeight())) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].id }) { page ->
            val meta = items[page]
            Box(Modifier.fillMaxSize().pressable { onOpen(meta) }) {
                AsyncImage(
                    model = meta.background ?: meta.poster,
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (!reduced) {
                                // Read the pager inside the layer so a swipe redraws, not recomposes.
                                val offset = pager.currentPage - page + pager.currentPageOffsetFraction
                                translationX = offset * size.width * 0.35f
                                val s = lerp(1.08f, 1f, 1f - offset.absoluteValue.coerceIn(0f, 1f))
                                scaleX = s; scaleY = s
                            }
                        }
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to NovaPalette.background)
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = ScreenPadding, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!meta.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = meta.logo,
                            contentDescription = meta.name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.fillMaxWidth(0.66f).height(84.dp)
                        )
                    } else {
                        Text(meta.name, style = MType.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val info = listOfNotNull(meta.genres.take(2).joinToString("  ·  ").ifBlank { null }, meta.shortInfo.ifBlank { null })
                    if (info.isNotEmpty()) Text(info.joinToString("  ·  "), style = MType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onPlay(meta) },
                            colors = ButtonDefaults.buttonColors(containerColor = NovaPalette.textPrimary, contentColor = NovaPalette.background)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play", style = MType.body.copy(color = NovaPalette.background))
                        }
                        FilledTonalButton(onClick = { onOpen(meta) }) {
                            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Details", style = MType.body)
                        }
                    }
                }
            }
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = ScreenPadding, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(items.size) { i ->
                    val selected = i == pager.currentPage
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (selected) 16.dp else 5.dp)
                            .background(if (selected) NovaPalette.textPrimary else NovaPalette.textMuted.copy(alpha = 0.5f), RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}
