package dev.nova.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.CardGap
import dev.nova.tv.ui.components.EmptyState
import dev.nova.tv.ui.components.FilterChip
import dev.nova.tv.ui.components.PosterCard
import dev.nova.tv.ui.components.RegisterContentEntry
import dev.nova.tv.ui.components.ShimmerBox
import dev.nova.tv.ui.focus.PivotScroll
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

private const val GRID_COLUMNS = 6

/** Movies / Series: a genre strip over an endless poster grid. */
@Composable
fun BrowseScreen(route: Route, state: NovaState, viewModel: NovaViewModel) {
    val type = if (route == Route.SERIES) "series" else "movie"
    val browse = state.browse[route] ?: BrowseState(loading = true)
    val genres = state.genres[type].orEmpty()

    // Entering the catalogue puts focus on the genre strip, and the rail's Right
    // lands here too, so the grid is always one Down away.
    val entry = remember { FocusRequester() }
    RegisterContentEntry(entry)
    LaunchedEffect(route) {
        withFrameNanos { }
        runCatching { entry.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(top = NovaSpace.safeVertical)) {
        Text(route.label, style = NovaType.display, modifier = Modifier.padding(start = ContentStart))
        Spacer(Modifier.height(24.dp))
        PivotScroll(parentFraction = 0.05f) {
            LazyRow(
                modifier = Modifier.arvioDpadFocusGroup(),
                contentPadding = PaddingValues(start = ContentStart, end = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "all") {
                    FilterChip("All", selected = browse.genre == null, modifier = Modifier.focusRequester(entry)) {
                        viewModel.selectGenre(route, null)
                    }
                }
                items(genres, key = { it }) { g -> FilterChip(g, selected = browse.genre == g) { viewModel.selectGenre(route, g) } }
            }
        }
        Spacer(Modifier.height(24.dp))
        PosterGrid(
            items = browse.items,
            loading = browse.loading,
            onOpen = viewModel::openTitle,
            onNearEnd = { viewModel.loadBrowse(route) },
            focusFirst = false
        )
    }
}

/** Search: the query, a keyboard, results. Popular titles fill the empty state. */
@Composable
fun SearchScreen(state: NovaState, viewModel: NovaViewModel) {
    val firstKey = remember { FocusRequester() }
    RegisterContentEntry(firstKey)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    val popular = state.shelves.firstOrNull { it.style == ShelfStyle.POSTER }?.items.orEmpty()
    val showing = if (state.query.isBlank()) popular else state.results

    Row(Modifier.fillMaxSize().padding(top = NovaSpace.safeVertical)) {
        Column(Modifier.padding(start = ContentStart).width(420.dp)) {
            QueryField(state.query.ifEmpty { "Search" }, placeholder = state.query.isEmpty())
            Spacer(Modifier.height(20.dp))
            OnScreenKeyboard(
                layout = KeyboardLayout.SEARCH,
                onKey = viewModel::appendQuery,
                onBackspace = viewModel::backspaceQuery,
                onClear = { viewModel.setQuery("") },
                firstKey = firstKey
            )
        }
        Spacer(Modifier.width(36.dp))
        Box(Modifier.weight(1f)) {
            when {
                state.searchError != null && !state.searching ->
                    EmptyState(title = "Search failed", message = state.searchError ?: "Try again in a moment.")
                state.query.isNotBlank() && !state.searching && state.results.isEmpty() && state.query.trim().length >= 2 ->
                    EmptyState(title = "No results", message = "Try a different spelling.")
                else -> PosterGrid(
                    items = showing,
                    loading = state.searching && state.results.isEmpty(),
                    onOpen = viewModel::openTitle,
                    columns = 5,
                    startPadding = 0.dp,
                    header = if (state.query.isBlank() && popular.isNotEmpty()) "Popular" else null
                )
            }
        }
    }
}

@Composable
internal fun QueryField(text: String, placeholder: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(NovaPalette.surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (placeholder) text else "$text|",
            style = NovaType.sectionTitle,
            color = if (placeholder) NovaPalette.textMuted else NovaPalette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis
        )
    }
}

/** My List: everything saved, newest first. */
@Composable
fun MyListScreen(state: NovaState, viewModel: NovaViewModel) {
    Column(Modifier.fillMaxSize().padding(top = NovaSpace.safeVertical)) {
        Text("My List", style = NovaType.display, modifier = Modifier.padding(start = ContentStart))
        Spacer(Modifier.height(28.dp))
        if (state.myList.isEmpty()) {
            EmptyState(title = "Nothing here yet", message = "Add titles from their page and they will wait for you here.")
        } else {
            PosterGrid(items = state.myList, loading = false, onOpen = viewModel::openTitle, focusFirst = true)
        }
    }
}

/** The shared endless grid. [onNearEnd] fires two rows before the end. */
@Composable
internal fun PosterGrid(
    items: List<MetaPreview>,
    loading: Boolean,
    onOpen: (MetaPreview) -> Unit,
    onNearEnd: () -> Unit = {},
    columns: Int = GRID_COLUMNS,
    startPadding: androidx.compose.ui.unit.Dp = ContentStart,
    focusFirst: Boolean = false,
    header: String? = null
) {
    val first = remember { FocusRequester() }
    if (focusFirst) {
        // My List is reached straight from the rail; without this registration
        // Right on the rail had nothing to hand focus to and the viewer was
        // stuck on the rail.
        RegisterContentEntry(first)
        LaunchedEffect(items.isNotEmpty()) {
            if (items.isEmpty()) return@LaunchedEffect
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    PivotScroll(parentFraction = 0.12f) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().arvioDpadFocusGroup(),
            contentPadding = PaddingValues(start = startPadding, end = NovaSpace.safeHorizontal, top = 16.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            verticalArrangement = Arrangement.spacedBy(CardGap + 12.dp)
        ) {
            if (header != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") { Text(header, style = NovaType.sectionTitle) }
            }
            itemsIndexed(items, key = { i, m -> if (m.id.isBlank()) "row-$i" else "${m.id}#$i" }) { i, meta ->
                if (i >= items.size - columns * 2) LaunchedEffect(items.size) { onNearEnd() }
                PosterCard(
                    title = meta,
                    onClick = { onOpen(meta) },
                    width = 0.dp,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                )
            }
            if (loading) placeholders(columns * 2)
        }
    }
}

private fun LazyGridScope.placeholders(count: Int) {
    items(count) { ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) }
}
