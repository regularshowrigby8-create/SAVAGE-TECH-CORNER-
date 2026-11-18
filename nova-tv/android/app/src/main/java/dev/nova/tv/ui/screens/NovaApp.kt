package dev.nova.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaTheme
import dev.nova.tv.ui.theme.NovaType
import dev.nova.tv.ui.theme.novaBackgroundBrush

/**
 * The shell: a focusable side rail and a content area.
 *
 * Layout notes that matter on a real television:
 *
 *  - The content is inset by a safe margin. Some panels still crop 5%, and a
 *    heading that is half off-screen looks like a bug, not like a design choice.
 *  - The rail is a fixed 96dp of icons. It expands on focus, which is where the
 *    label appears; a permanently labelled rail costs 12% of a 1080p screen to
 *    show six words.
 *  - BACK is handled once, here. Every surface below closes exactly one level.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaApp(viewModel: NovaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    NovaTheme(reduceMotion = state.reduceMotion) {
        // BACK closes one surface. Returning false lets the system exit, which is
        // correct only when nothing is open.
        BackHandler(enabled = true) { viewModel.back() }

        // One root Box, so the shell and the player stack deterministically.
        // Emitting them as bare siblings leaves their stacking to the caller,
        // which is how a fullscreen player ends up drawn behind the rail.
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(novaBackgroundBrush())
            ) {
                SideRail(current = state.route, onSelect = viewModel::navigate)

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            end = NovaSpace.safeHorizontal,
                            top = NovaSpace.safeVertical,
                            bottom = NovaSpace.safeVertical
                        )
                ) {
                    // One branch per destination. Adding a Route without adding
                    // a pane here fails to compile, so a rail entry can never be
                    // dead -- which is exactly what `check_buttons.py` used to
                    // report as `unrouted-entries`.
                    when (state.route) {
                        Route.HOME -> HomePane(state, viewModel)
                        Route.MOVIES -> CatalogPane("Movies", state, viewModel)
                        Route.SERIES -> CatalogPane("Series", state, viewModel)
                        Route.SEARCH -> SearchPane(state, viewModel)
                        Route.ADDONS -> AddonsPane(state, viewModel)
                        Route.SETTINGS -> SettingsPane(state, viewModel)
                    }

                    if (state.opened != null && state.playing == null) {
                        DetailOverlay(state, viewModel)
                    }
                }
            }

            // The player owns the whole window: video beside a 96dp rail is not
            // fullscreen. Drawn last so it covers both the rail and the content.
            state.playing?.let { stream ->
                NovaPlayerHost(
                    stream = stream,
                    title = state.playingTitle.ifBlank { stream.name ?: "Playing" },
                    onLeave = viewModel::stopPlayback
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideRail(current: Route, onSelect: (Route) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .width(if (expanded) 208.dp else 96.dp)
            .fillMaxHeight()
            .background(NovaPalette.surface.copy(alpha = 0.6f))
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = NovaSpace.safeVertical, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (expanded) "NOVA" else "N",
            style = NovaType.sectionTitle.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            ),
            color = NovaPalette.accent,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 20.dp)
        )

        Route.entries.forEach { entry ->
            RailItem(
                route = entry,
                selected = entry == current,
                expanded = expanded,
                onClick = { onSelect(entry) }
            )
            // The divider sits above Settings, as in the shell diagram.
            if (entry == Route.SEARCH) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .padding(start = 16.dp)
                        .width(if (expanded) 160.dp else 32.dp)
                        .height(1.dp)
                        .background(NovaPalette.surfaceHigh)
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailItem(
    route: Route,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxSize().height(52.dp),
        shape = RoundedCornerShape(percent = NovaShape.pillPercent),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                NovaPalette.accent.copy(alpha = 0.22f)
            } else {
                NovaPalette.surface.copy(alpha = 0f)
            },
            contentColor = if (selected) NovaPalette.accent else NovaPalette.textMuted,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = glyph(route),
                style = NovaType.body.copy(fontWeight = FontWeight.Bold),
                color = if (selected) NovaPalette.accent else NovaPalette.textMuted
            )
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(route.label, style = NovaType.body, maxLines = 1)
            }
        }
    }
}

/**
 * Rail glyphs are single letters, not emoji.
 *
 * Emoji render differently on every panel, are sized inconsistently by the
 * platform font, and are not focusable in a predictable box. A letter is the
 * same width and weight everywhere.
 */
internal fun glyph(route: Route): String = when (route) {
    Route.HOME -> "H"
    Route.MOVIES -> "M"
    Route.SERIES -> "S"
    Route.SEARCH -> "Q"
    Route.ADDONS -> "A"
    Route.SETTINGS -> "G"
}
