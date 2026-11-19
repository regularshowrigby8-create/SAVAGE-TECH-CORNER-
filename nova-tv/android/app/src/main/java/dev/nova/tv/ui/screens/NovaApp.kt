package dev.nova.tv.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nova.tv.ui.components.FocusRestore
import dev.nova.tv.ui.components.LocalFocusRestore
import dev.nova.tv.ui.theme.LocalReduceMotion
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
 *  - The content is inset by a safe margin on every side. Some panels still
 *    crop 5%, and a heading that is half off-screen looks like a bug.
 *  - The rail is 96dp of letters and expands to 208dp on focus, drawn *over*
 *    the content. It used to be a Row sibling that changed width, so every
 *    focus move into the rail reflowed the whole screen sideways.
 *  - BACK is handled once, here, through `ui/nav/NovaNav.resolveBack`. Every
 *    surface below closes exactly one level; when nothing is left the
 *    activity finishes instead of the press being swallowed.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaApp(viewModel: NovaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    NovaTheme(reduceMotion = state.reduceMotion) {
        BackHandler(enabled = true) {
            if (!viewModel.back()) activity?.finish()
        }

        // Only restore once nothing covers the browse screen.
        val restoreId = if (state.opened == null && state.playback == null) state.restoreFocusId else null
        CompositionLocalProvider(
            LocalFocusRestore provides FocusRestore(restoreId, viewModel::consumeFocusRestore)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(novaBackgroundBrush())
            ) {
                val covered = state.opened != null || state.playback != null
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = RailCollapsed + 24.dp,
                            end = NovaSpace.safeHorizontal,
                            top = NovaSpace.safeVertical,
                            bottom = NovaSpace.safeVertical
                        )
                ) {
                    // One branch per destination. Adding a Route without a pane
                    // fails to compile, so a rail entry can never be dead.
                    when (state.route) {
                        Route.HOME -> HomePane(state, viewModel)
                        Route.MOVIES -> CatalogPane("Movies", state, viewModel)
                        Route.SERIES -> CatalogPane("Series", state, viewModel)
                        Route.SEARCH -> SearchPane(state, viewModel)
                        Route.ADDONS -> AddonsPane(state, viewModel)
                        Route.FINDER -> FinderPane(state, viewModel)
                        Route.SETTINGS -> SettingsPane(state, viewModel)
                    }
                }

                if (!covered) {
                    SideRail(
                        current = state.route,
                        onSelect = viewModel::navigate,
                        modifier = Modifier.zIndex(1f)
                    )
                }

                if (state.opened != null && state.playback == null) {
                    DetailOverlay(state, viewModel)
                }

                // The player owns the whole window and is drawn last.
                state.playback?.let { request ->
                    NovaPlayerHost(request = request, viewModel = viewModel)
                }
            }
        }
    }
}

private val RailCollapsed = 96.dp
private val RailExpanded = 208.dp

// ---------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun SideRail(current: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val reduced = LocalReduceMotion.current
    val width by animateDpAsState(
        targetValue = if (expanded) RailExpanded else RailCollapsed,
        animationSpec = if (reduced) snap() else tween(160),
        label = "rail"
    )
    val requesters = remember { Route.entries.associateWith { FocusRequester() } }

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(
                if (expanded) NovaPalette.surface.copy(alpha = 0.96f)
                else NovaPalette.surface.copy(alpha = 0.6f)
            )
            .onFocusChanged { expanded = it.hasFocus }
            // Entering the rail lands on the current destination, not on Home.
            .focusRestorer(requesters.getValue(current))
            .focusGroup()
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
                requester = requesters.getValue(entry),
                onClick = { onSelect(entry) }
            )
            // Browse destinations above the divider, source management below.
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
    requester: FocusRequester,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        // fillMaxWidth, not fillMaxSize: a 52dp item that asks for the full
        // height only worked because the Column measured it with a bound.
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(requester),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = NovaShape.pillPercent)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                NovaPalette.accent.copy(alpha = 0.22f)
            } else {
                NovaPalette.surface.copy(alpha = 0f)
            },
            contentColor = if (selected) NovaPalette.accent else NovaPalette.textMuted,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(3.dp, NovaPalette.accent),
                shape = RoundedCornerShape(percent = NovaShape.pillPercent)
            )
        )
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = glyph(route),
                style = NovaType.body.copy(fontWeight = FontWeight.Bold),
                color = if (selected) NovaPalette.accent else NovaPalette.textMuted
            )
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(route.label, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Route.FINDER -> "F"
    Route.SETTINGS -> "G"
}
