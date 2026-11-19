package dev.nova.tv.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.nova.tv.ui.focus.dpadRepeatGate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nova.tv.ui.components.FocusRestore
import dev.nova.tv.ui.components.LocalFocusRestore
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaTheme
import dev.nova.tv.ui.theme.NovaType

/**
 * The shell: an icon rail over full-bleed screens.
 *
 *  - Screens draw edge to edge (backdrops run behind the rail) and inset their
 *    own content by [ContentStart], so nothing reflows when the rail opens.
 *  - The rail is icons only until it takes focus, then slides open with labels
 *    over the content.
 *  - BACK is handled once, here, through `ui/nav/NovaNav.resolveBack`.
 *  - Route changes crossfade; the detail rises in; the player fades over all.
 */
@Composable
fun NovaApp(viewModel: NovaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    NovaTheme(reduceMotion = state.reduceMotion) {
        BackHandler(enabled = true) {
            if (!viewModel.back()) activity?.finish()
        }
        val reduced = LocalReduceMotion.current

        val restoreId = if (state.detail == null && state.playback == null) state.restoreFocusId else null
        CompositionLocalProvider(
            LocalFocusRestore provides FocusRestore(restoreId, viewModel::consumeFocusRestore)
        ) {
            Box(Modifier.fillMaxSize().dpadRepeatGate().background(NovaPalette.background)) {
                val covered = state.detail != null || state.playback != null

                AnimatedContent(
                    targetState = state.route,
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 24 }) togetherWith fadeOut(tween(160))
                    },
                    label = "route",
                    modifier = Modifier.fillMaxSize()
                ) { route ->
                    // One branch per destination: a Route without a screen fails to compile.
                    when (route) {
                        Route.HOME -> HomeScreen(state, viewModel)
                        Route.MOVIES -> BrowseScreen(Route.MOVIES, state, viewModel)
                        Route.SERIES -> BrowseScreen(Route.SERIES, state, viewModel)
                        Route.DUAL_AUDIO -> DualAudioScreen(state, viewModel)
                        Route.SEARCH -> SearchScreen(state, viewModel)
                        Route.MY_LIST -> MyListScreen(state, viewModel)
                        Route.SOURCES -> SourcesScreen(state, viewModel)
                        Route.SETTINGS -> SettingsScreen(state, viewModel)
                    }
                }

                if (!covered) {
                    SideRail(current = state.route, onSelect = viewModel::navigate, modifier = Modifier.zIndex(1f))
                }

                AnimatedVisibility(
                    visible = state.detail != null && state.playback == null,
                    enter = if (reduced) EnterTransition.None else fadeIn(tween(240)) + scaleIn(tween(320), initialScale = 1.04f),
                    exit = if (reduced) ExitTransition.None else fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.02f),
                    modifier = Modifier.zIndex(2f)
                ) {
                    DetailScreen(state, viewModel)
                }

                state.playback?.let { request ->
                    Box(Modifier.zIndex(3f)) { NovaPlayerHost(request = request, viewModel = viewModel) }
                }

                Toast(state.toast, Modifier.align(Alignment.BottomCenter).zIndex(4f))
            }
        }
    }
}

/** Where screen content starts, clear of the collapsed rail. */
internal val ContentStart = 112.dp
private val RailCollapsed = 88.dp
private val RailExpanded = 236.dp

@Composable
private fun Toast(message: String?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier.padding(bottom = NovaSpace.safeVertical)
    ) {
        Text(
            text = last,
            style = NovaType.body,
            modifier = Modifier
                .background(NovaPalette.surfaceHigh.copy(alpha = 0.96f), RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 12.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SideRail(current: Route, onSelect: (Route) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val reduced = LocalReduceMotion.current
    val width by animateDpAsState(
        targetValue = if (expanded) RailExpanded else RailCollapsed,
        animationSpec = if (reduced) snap() else tween(200),
        label = "rail"
    )
    val requesters = remember { Route.entries.associateWith { FocusRequester() } }

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(
                if (expanded) {
                    Brush.horizontalGradient(listOf(NovaPalette.background, NovaPalette.background.copy(alpha = 0.94f), Color.Transparent))
                } else {
                    Brush.horizontalGradient(listOf(NovaPalette.background.copy(alpha = 0.85f), Color.Transparent))
                }
            )
            .onFocusChanged { expanded = it.hasFocus }
            // Compose's own focus history: re-entering lands on the last item.
            .arvioDpadFocusGroup()
            .padding(vertical = NovaSpace.safeVertical, horizontal = 14.dp),
        // 8 destinations × 46 dp + gaps fit under the NOVA mark on a 540 dp-high TV screen.
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Route.entries.forEach { entry ->
            RailItem(
                route = entry,
                selected = entry == current,
                expanded = expanded,
                requester = requesters.getValue(entry),
                onClick = { onSelect(entry) }
            )
        }
    }
    Text(
        text = "NOVA",
        style = NovaType.sectionTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp),
        color = NovaPalette.accent,
        modifier = Modifier.padding(start = 26.dp, top = NovaSpace.safeVertical).zIndex(1f)
    )
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
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .focusRequester(requester),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = NovaShape.pillPercent)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) NovaPalette.textPrimary else NovaPalette.textMuted,
            focusedContainerColor = NovaPalette.textPrimary,
            focusedContentColor = NovaPalette.background
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.04f)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon(route), contentDescription = route.label, modifier = Modifier.size(26.dp))
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(route.label, style = NovaType.body.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (selected) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(5.dp).background(NovaPalette.accent, RoundedCornerShape(50)))
            }
        }
    }
}

internal fun icon(route: Route): ImageVector = when (route) {
    Route.HOME -> Icons.Filled.Home
    Route.MOVIES -> Icons.Filled.PlayArrow
    Route.SERIES -> Icons.AutoMirrored.Filled.List
    Route.DUAL_AUDIO -> Icons.Filled.Star
    Route.SEARCH -> Icons.Filled.Search
    Route.MY_LIST -> Icons.Filled.Favorite
    Route.SOURCES -> Icons.Filled.Build
    Route.SETTINGS -> Icons.Filled.Settings
}
