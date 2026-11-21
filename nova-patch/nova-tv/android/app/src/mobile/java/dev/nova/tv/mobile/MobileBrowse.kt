package dev.nova.tv.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.Route
import dev.nova.tv.ui.theme.NovaPalette

/** Movies or Series: genre chips over an endless grid. */
@Composable
fun MobileBrowse(route: Route, state: NovaState, viewModel: NovaViewModel) {
    val type = if (route == Route.SERIES) "series" else "movie"
    val browse = state.browse[route]
    val genres = state.genres[type].orEmpty()
    val grid = rememberLazyGridState()
    LaunchedEffect(route) {
        // Arriving from a deep link or a cold start: make sure the grid has something.
        if (browse == null || (browse.items.isEmpty() && !browse.loading)) viewModel.loadBrowse(route, reset = true)
    }
    LaunchedEffect(browse?.genre) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(route.label)
        if (genres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                item(key = "all") { GenreChip("All", browse?.genre == null) { viewModel.selectGenre(route, null) } }
                items(genres, key = { it }) { g -> GenreChip(g, browse?.genre == g) { viewModel.selectGenre(route, g) } }
            }
        }
        if (browse != null && browse.items.isEmpty() && !browse.loading && browse.error != null) {
            Column(Modifier.fillMaxWidth().padding(ScreenPadding)) {
                Text(browse.error, style = MType.body)
                Button(onClick = { viewModel.loadBrowse(route, reset = true) }) { Text("Try again") }
            }
        } else {
            PosterGrid(
                items = browse?.items.orEmpty(),
                loading = browse?.loading ?: true,
                exhausted = browse?.exhausted ?: false,
                onOpen = viewModel::openTitle,
                onNearEnd = { viewModel.loadBrowse(route) },
                state = grid,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MType.label) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = NovaPalette.textPrimary,
            selectedLabelColor = NovaPalette.background,
            labelColor = NovaPalette.textPrimary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = NovaPalette.textMuted.copy(alpha = 0.35f)
        )
    )
}

/** Search as you type. With an empty box, the first home row fills the grid. */
@Composable
fun MobileSearch(state: NovaState, viewModel: NovaViewModel) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (state.query.isEmpty()) runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = { Text("Films and series", style = MType.body.copy(color = NovaPalette.textMuted)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MType.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NovaPalette.accent,
                unfocusedBorderColor = NovaPalette.textMuted.copy(alpha = 0.3f),
                focusedContainerColor = NovaPalette.surface,
                unfocusedContainerColor = NovaPalette.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = ScreenPadding, vertical = 10.dp)
                .focusRequester(focus)
        )
        if (state.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).height(2.dp), color = NovaPalette.accent)
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(8.dp))
        val idle = state.query.trim().length < 2
        val items = if (idle) state.shelves.firstOrNull { it.items.isNotEmpty() && it.style == dev.nova.tv.ui.screens.ShelfStyle.POSTER }?.items.orEmpty() else state.results
        if (!idle && !state.searching && items.isEmpty()) {
            EmptyNote("No matches")
        } else {
            PosterGrid(
                items = items,
                loading = !idle && state.searching && items.isEmpty(),
                exhausted = true,
                onOpen = { keyboard?.hide(); viewModel.openTitle(it) },
                onNearEnd = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun MobileMyList(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("My List", onBack = onBack)
        if (state.myList.isEmpty()) {
            EmptyNote("Nothing saved yet")
        } else {
            PosterGrid(
                items = state.myList,
                loading = false,
                exhausted = true,
                onOpen = viewModel::openTitle,
                onNearEnd = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
