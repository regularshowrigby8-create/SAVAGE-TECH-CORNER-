package dev.nova.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items as gridItems
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.EmptyState
import dev.nova.tv.ui.components.ErrorState
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.focusScale
import dev.nova.tv.ui.components.PosterCard
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.components.SectionTitle
import dev.nova.tv.ui.components.SkeletonRow
import dev.nova.tv.ui.components.StatusDot
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaType

/**
 * The seven panes.
 *
 * Before this file, all six rail destinations rendered `HomePane`. The rail was
 * live, the state changed, and nothing branched on it -- so Movies showed Home,
 * Search showed Home, and Settings showed Home. That is the `unrouted-entries`
 * finding `scripts/check_buttons.py` reports.
 *
 * Each pane below has a heading that names where you are, because on a TV the
 * rail is 96dp of letters and the heading is the only thing that confirms the
 * destination.
 */

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
fun HomePane(state: NovaState, viewModel: NovaViewModel) {
    when {
        state.loading -> LoadingPane()
        state.error != null && state.rows.isEmpty() -> ErrorPane(state, viewModel)
        state.rows.isEmpty() -> EmptyPane(viewModel)
        else -> TvLazyColumn(
            verticalArrangement = Arrangement.spacedBy(28.dp),
            // Room for the focused card's scale and glow at the bottom edge.
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Hero(
                    hero = state.rows.firstOrNull()?.items?.firstOrNull(),
                    viewModel = viewModel,
                    // Returning from a detail restores the card instead.
                    takeFocus = state.restoreFocusId == null
                )
            }
            items(state.rows) { row ->
                Column {
                    SectionTitle(row.title)
                    Shelf(row.items, viewModel)
                }
            }
        }
    }
}

/**
 * The hero is the first title of the first row, promoted — not a separate fetch.
 *
 * It takes initial focus when Home first loads, so the remote does something
 * on the first press. Before, nothing was focused at launch and the first
 * D-pad press picked whatever Compose found, often the rail.
 */
@Composable
private fun Hero(hero: MetaPreview?, viewModel: NovaViewModel, takeFocus: Boolean) {
    if (hero == null) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(hero.id, takeFocus) {
        if (takeFocus) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        val art = hero.background ?: hero.poster
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(NovaShape.sheet))
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to NovaPalette.background,
                            0.5f to NovaPalette.background.copy(alpha = 0.75f),
                            1f to Color.Transparent
                        )
                    )
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.6f)
                .padding(bottom = 8.dp)
        ) {
            Text(text = hero.name, style = NovaType.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                hero.year?.takeIf { it.isNotBlank() }?.let { Text(it, style = NovaType.meta) }
                if (hero.rating > 0.0) {
                    Text("★ %.1f".format(hero.rating), style = NovaType.meta, color = NovaPalette.accent)
                }
                hero.genres.take(2).forEach { genre -> Text(genre, style = NovaType.meta) }
            }
            if (hero.description.isNotBlank()) {
                Text(
                    text = hero.description,
                    style = NovaType.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Opens the detail with "Play best" focused: OK, OK plays.
                PrimaryButton("Watch", modifier = Modifier.focusRequester(focus)) { viewModel.openTitle(hero) }
            }
        }
    }
}

@Composable
private fun Shelf(items: List<MetaPreview>, viewModel: NovaViewModel) {
    TvLazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        // Padding lives inside the scroll view so the focus ring and its glow
        // are not clipped at the row edge.
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        items(items) { title ->
            PosterCard(title = title, onClick = { viewModel.openTitle(title) })
        }
    }
}

// ---------------------------------------------------------------------------
// Movies / Series
// ---------------------------------------------------------------------------

@Composable
fun CatalogPane(name: String, state: NovaState, viewModel: NovaViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(name, style = NovaType.display)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "From every installed source",
            style = NovaType.meta
        )
        Spacer(Modifier.height(20.dp))

        when {
            state.loading -> {
                SkeletonRow()
                Spacer(Modifier.height(24.dp))
                SkeletonRow()
            }
            state.error != null && state.rows.isEmpty() -> ErrorPane(state, viewModel)
            else -> {
                val row = state.rowFor(if (name == "Movies") Route.MOVIES else Route.SERIES)
                if (row == null || row.items.isEmpty()) {
                    EmptyState(
                        title = "Nothing here yet",
                        message = "$name comes from the sources you have installed. " +
                            "Cinemeta answers without an account; add another source in Addons.",
                        actionLabel = "Open Addons",
                        onAction = { viewModel.navigate(Route.ADDONS) }
                    )
                } else {
                    CatalogGrid(row.items, viewModel)
                }
            }
        }
    }
}

/**
 * A grid, not a row.
 *
 * The browse screens exist to scan a whole catalogue. This used to be a
 * TvLazyColumn of hand-chunked Rows of six, which broke D-pad UP/DOWN (focus
 * jumped to the nearest card by geometry, not by column) and overflowed a
 * 960dp TV canvas. It is now a real lazy grid with adaptive columns.
 */
@Composable
private fun CatalogGrid(items: List<MetaPreview>, viewModel: NovaViewModel) {
    TvLazyVerticalGrid(
        columns = TvGridCells.Adaptive(128.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        gridItems(items, key = { it.id }) { title ->
            PosterCard(title = title, onClick = { viewModel.openTitle(title) }, width = 0)
        }
    }
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

/**
 * Search with a D-pad keyboard.
 *
 * The system IME needs a touchscreen; a television has five buttons. Rather
 * than ship an input that cannot be typed into, this is an explicit letter grid
 * that is fully focusable, plus the results below it.
 *
 * Results are the loaded catalogue first (instant), then every installed
 * addon's search catalogue, asked once per pause in typing.
 */
@Composable
fun SearchPane(state: NovaState, viewModel: NovaViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("Search", style = NovaType.display)
        Spacer(Modifier.height(16.dp))

        // The query, or an honest placeholder.
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(NovaPalette.surface, RoundedCornerShape(NovaShape.card))
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = state.query.ifEmpty { "Type with the pad below" },
                style = NovaType.body,
                color = if (state.query.isEmpty()) NovaPalette.textMuted else NovaPalette.textPrimary
            )
        }

        Spacer(Modifier.height(16.dp))
        Keyboard(
            onLetter = viewModel::appendQuery,
            onBackspace = viewModel::backspaceQuery,
            onClear = { viewModel.setQuery("") }
        )
        Spacer(Modifier.height(24.dp))

        val results = state.searchResults()
        when {
            state.query.isEmpty() -> Text(
                "Searches the loaded catalogue and every installed source that supports search.",
                style = NovaType.meta
            )
            results.isEmpty() && state.searching -> Text("Asking sources…", style = NovaType.meta)
            results.isEmpty() -> EmptyState(
                title = "No matches",
                message = "No installed source has a title matching \"${state.query}\"."
            )
            else -> TvLazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                items(results) { title ->
                    PosterCard(title = title, onClick = { viewModel.openTitle(title) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Keyboard(onLetter: (Char) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    // Titles have digits ("2001", "1917") and spaces; the old pad had neither.
    val rows = listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ", "1234567890")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                letters.forEach { letter ->
                    Key(letter.toString()) { onLetter(letter) }
                }
                if (i == 2) {
                    Key("Space", width = 104) { onLetter(' ') }
                    Key("⌫", width = 64) { onBackspace() }
                    Key("Clear", width = 96) { onClear() }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(label: String, width: Int = 48, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width.dp)
            .height(44.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(NovaShape.card)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = NovaPalette.surface,
            contentColor = NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.accent,
            focusedContentColor = NovaPalette.background
        ),
        scale = focusScale()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = NovaType.body)
        }
    }
}

// ---------------------------------------------------------------------------
// Addons
// ---------------------------------------------------------------------------

/**
 * Addons.
 *
 * Every row is a focus target. Rows used to be plain, unfocusable Rows, so on
 * a remote the list could not even be scrolled. Installed addons can be
 * removed; built-ins say so instead of showing a button that does nothing.
 * New sources come from the Source Finder, which only adds addons that have
 * returned a playable stream.
 */
@Composable
fun AddonsPane(state: NovaState, viewModel: NovaViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text("Addons", style = NovaType.display)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sources are Stremio-protocol addons. Built-in sources cannot be removed.",
            style = NovaType.meta
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Find more sources") { viewModel.navigate(Route.FINDER) }
            SecondaryButton("Reload catalogue", onClick = viewModel::refresh)
            state.addonMessage?.let { Text(it, style = NovaType.meta, color = NovaPalette.accent) }
        }
        Spacer(Modifier.height(20.dp))

        if (state.addons.isEmpty()) {
            EmptyState(
                title = "No sources yet",
                message = "Nothing is installed. Reload to install the built-in source.",
                actionLabel = "Reload sources",
                onAction = viewModel::refresh
            )
        } else {
            TvLazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.addons, key = { it.baseUrl }) { addon ->
                    AddonRow(
                        name = addon.name,
                        host = addon.baseUrl,
                        builtIn = addon.isBuiltIn,
                        onRemove = { viewModel.removeAddon(addon.baseUrl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(name: String, host: String, builtIn: Boolean, onRemove: () -> Unit) {
    // Two presses to remove: one stray OK must not delete a source.
    var armed by remember { mutableStateOf(false) }
    FocusableRow(
        onClick = {
            if (!builtIn) {
                if (armed) onRemove() else armed = true
            }
        },
        modifier = Modifier.onFocusChanged { if (!it.isFocused) armed = false },
        trailing = {
            Text(
                text = when {
                    builtIn -> "Built in"
                    armed -> "Press OK again to remove"
                    else -> "OK to remove"
                },
                style = NovaType.meta,
                color = if (builtIn) NovaPalette.accent else NovaPalette.danger
            )
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusDot(active = true)
            Column {
                Text(name, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/**
 * Settings. Every row changes something real and reflects its own state.
 *
 * The old screen had ten write-only properties — assigned and never read. Each
 * toggle here reads its value back into the label, which is the difference
 * between a setting and a decoration.
 */
@Composable
fun SettingsPane(state: NovaState, viewModel: NovaViewModel) {
    // Scrolls: focus moving down brings the row into view on a 540dp-tall canvas.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = NovaType.display)
        Spacer(Modifier.height(24.dp))

        SettingRow(
            title = "Reduce motion",
            detail = "Removes all animation and scaling. Useful if movement is uncomfortable.",
            value = if (state.reduceMotion) "On" else "Off",
            onToggle = { viewModel.setReduceMotion(!state.reduceMotion) }
        )
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Autoplay next episode",
            detail = "Play the next episode when the current one ends.",
            value = if (state.autoplayNext) "On" else "Off",
            onToggle = viewModel::toggleAutoplay
        )
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Source Finder",
            detail = "Search addon directories for new stream sources and test them before adding.",
            value = "Open",
            onToggle = { viewModel.navigate(Route.FINDER) }
        )
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "Sources",
            detail = "${state.addons.size} installed, " +
                "${state.addons.count { it.isBuiltIn }} built in.",
            value = "Manage",
            onToggle = { viewModel.navigate(Route.ADDONS) }
        )
        Spacer(Modifier.height(12.dp))
        // These three are read-only here on purpose: there is no quality picker
        // or subtitle-size stepper yet, and rendering a control that cannot change
        // anything is the exact defect this screen is meant to be free of. They
        // are shown as stated values, which is honest, rather than as dead
        // buttons, which is not.
        InfoRow("Preferred quality", state.preferredQuality)
        Spacer(Modifier.height(8.dp))
        InfoRow("Subtitle size", "${state.subtitleSize}%")
        Spacer(Modifier.height(8.dp))
        InfoRow("Playback speed", "%.2fx".format(state.playbackRate))
    }
}

/** A stated value with no control. Honest about being information, not a knob. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = NovaType.meta)
        Spacer(Modifier.weight(1f))
        Text(value, style = NovaType.meta, color = NovaPalette.textPrimary)
    }
}

@Composable
private fun SettingRow(
    title: String,
    detail: String,
    value: String,
    onToggle: () -> Unit
) {
    // The whole row is the target; it used to be a small pill at the far right.
    FocusableRow(
        onClick = onToggle,
        trailing = { Text(value, style = NovaType.body, color = NovaPalette.accent) }
    ) {
        Column {
            Text(title, style = NovaType.body)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = NovaType.meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared states
// ---------------------------------------------------------------------------

@Composable
private fun LoadingPane() {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        SkeletonRow()
        SkeletonRow()
    }
}

/**
 * The error names the failing source.
 *
 * "Something went wrong" is a dead end; "Cinemeta: timeout" tells the viewer
 * whether to check the network or the addon.
 */
@Composable
private fun ErrorPane(state: NovaState, viewModel: NovaViewModel) {
    ErrorState(
        title = state.error ?: "Could not load",
        detail = if (state.errorSources.isEmpty()) {
            "No source answered. Check the connection, then try again."
        } else {
            state.errorSources.joinToString("  ·  ")
        },
        onRetry = viewModel::refresh
    )
}

@Composable
private fun EmptyPane(viewModel: NovaViewModel) {
    EmptyState(
        title = "No titles yet",
        message = "The catalogue is installed but empty. Reload, or add another source.",
        actionLabel = "Reload",
        onAction = viewModel::refresh
    )
}
