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
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.EmptyState
import dev.nova.tv.ui.components.ErrorState
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
 * The six panes.
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
        else -> TvLazyColumn(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item { Hero(hero = state.rows.firstOrNull()?.items?.firstOrNull(), viewModel) }
            items(state.rows) { row ->
                Column {
                    SectionTitle(row.title)
                    Shelf(row.items, viewModel)
                }
            }
        }
    }
}

/** The hero is the first title of the first row, promoted — not a separate fetch. */
@Composable
private fun Hero(hero: MetaPreview?, viewModel: NovaViewModel) {
    if (hero == null) return
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(
            text = hero.name,
            style = NovaType.hero,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            hero.year?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = NovaType.meta)
            }
            if (hero.rating > 0.0) {
                Text("★ %.1f".format(hero.rating), style = NovaType.meta, color = NovaPalette.accent)
            }
            hero.genres.take(2).forEach { genre ->
                Text(genre, style = NovaType.meta)
            }
        }
        if (hero.description.isNotBlank()) {
            Text(
                text = hero.description,
                style = NovaType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(680.dp)
            )
        }
        Row(
            Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrimaryButton("More info") { viewModel.openTitle(hero) }
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
 * The browse screens exist to scan a whole catalogue, and a single horizontal
 * row forces the viewer to hold right for a hundred titles. Three rows of
 * posters is the smallest grid that still reads as a grid on screen.
 */
@Composable
private fun CatalogGrid(items: List<MetaPreview>, viewModel: NovaViewModel) {
    TvLazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(items.chunked(6)) { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowItems.forEach { title ->
                    PosterCard(title = title, onClick = { viewModel.openTitle(title) })
                }
            }
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
 * The query filters what is already loaded and says so. It does not claim to
 * have searched sources it never asked.
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
        Keyboard(onLetter = viewModel::appendQuery, onBackspace = viewModel::backspaceQuery)
        Spacer(Modifier.height(24.dp))

        val results = state.searchResults()
        when {
            state.query.isEmpty() -> Text(
                "Search filters the catalogue already on this device.",
                style = NovaType.meta
            )
            results.isEmpty() -> EmptyState(
                title = "No matches",
                message = "Nothing in the loaded catalogue contains \"${state.query}\"."
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
private fun Keyboard(onLetter: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("ABCDEFGHI", "JKLMNOPQR", "STUVWXYZ")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                letters.forEach { letter ->
                    Key(letter.toString()) { onLetter(letter) }
                }
                if (letters == rows.last()) {
                    Key("⌫", width = 96) { onBackspace() }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(label: String, width: Int = 56, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width.dp)
            .height(52.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(NovaShape.card)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = NovaPalette.surface,
            contentColor = NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.accent,
            focusedContentColor = NovaPalette.background
        )
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
 * Only controls that do something are rendered. `repository.remove()` ignores
 * built-in sources, so built-ins show no Remove button at all rather than a
 * button that silently does nothing.
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
        Spacer(Modifier.height(24.dp))

        if (state.addons.isEmpty()) {
            EmptyState(
                title = "No sources yet",
                message = "Nothing is installed. Reload to install the built-in source.",
                actionLabel = "Reload sources",
                onAction = viewModel::refresh
            )
        } else {
            TvLazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.addons) { addon ->
                    AddonRow(
                        name = addon.name,
                        host = addon.baseUrl,
                        builtIn = addon.isBuiltIn
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonRow(name: String, host: String, builtIn: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(NovaPalette.surface, RoundedCornerShape(NovaShape.card))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusDot(active = true)
        Column(Modifier.width(320.dp)) {
            Text(name, style = NovaType.body)
            Text(host, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (builtIn) {
            // A label, not a disabled button: a greyed control still reads as a
            // control and invites pressing it.
            Text("Built in", style = NovaType.meta, color = NovaPalette.accent)
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
    Column(Modifier.fillMaxSize()) {
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(NovaPalette.surface, RoundedCornerShape(NovaShape.card))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(640.dp)) {
            Text(title, style = NovaType.body)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = NovaType.meta)
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton(value, onClick = onToggle)
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
