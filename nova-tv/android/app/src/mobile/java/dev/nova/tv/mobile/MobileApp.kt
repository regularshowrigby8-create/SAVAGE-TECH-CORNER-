package dev.nova.tv.mobile

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nova.tv.ui.screens.NovaPlayerHost
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.Route
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette

/** Bottom tabs. Five is the most a phone bar holds; the rest live under More. */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    MOVIES("Movies", Icons.Filled.PlayArrow),
    SERIES("Series", Icons.AutoMirrored.Filled.List),
    SEARCH("Search", Icons.Filled.Search),
    MORE("More", Icons.Filled.Menu)
}

/** Destinations reached from the More tab. */
internal val MoreRoutes = setOf(Route.DUAL_AUDIO, Route.MY_LIST, Route.SOURCES, Route.SETTINGS, Route.DEBUG)

internal fun tabFor(route: Route): Tab = when (route) {
    Route.HOME -> Tab.HOME
    Route.MOVIES -> Tab.MOVIES
    Route.SERIES -> Tab.SERIES
    Route.SEARCH -> Tab.SEARCH
    Route.DUAL_AUDIO, Route.MY_LIST, Route.SOURCES, Route.SETTINGS, Route.DEBUG -> Tab.MORE
}

internal fun routeFor(tab: Tab): Route? = when (tab) {
    Tab.HOME -> Route.HOME
    Tab.MOVIES -> Route.MOVIES
    Tab.SERIES -> Route.SERIES
    Tab.SEARCH -> Route.SEARCH
    Tab.MORE -> null
}

/**
 * The phone shell: bottom tabs over full-bleed screens, a detail page that
 * rises over them, and the player over everything.
 *
 * Back on a phone peels one layer: player panel, player, source sheet,
 * detail, More page (back to the More menu), then the Home tab, then exit.
 */
@Composable
fun MobileApp(viewModel: NovaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var moreMenu by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }

    MobileTheme(reduceMotion = state.reduceMotion) {
        val reduced = LocalReduceMotion.current
        BackHandler(enabled = true) {
            when {
                state.playback != null -> if (state.playerPanel != null) viewModel.closePlayerPanel() else viewModel.stopPlayback()
                state.detail != null || state.addingWebsite -> viewModel.back()
                downloadsOpen -> downloadsOpen = false
                moreMenu -> moreMenu = false
                state.route in MoreRoutes -> moreMenu = true
                else -> if (!viewModel.back()) activity?.finish()
            }
        }

        Box(Modifier.fillMaxSize().background(NovaPalette.background)) {
            Scaffold(
                containerColor = NovaPalette.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    BottomBar(
                        selected = if (moreMenu) Tab.MORE else tabFor(state.route),
                        onSelect = { tab ->
                            val route = routeFor(tab)
                            if (route == null) {
                                moreMenu = true
                            } else {
                                moreMenu = false
                                downloadsOpen = false
                                viewModel.navigate(route)
                            }
                        }
                    )
                }
            ) { pad ->
                val screen = if (moreMenu) null else state.route
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                        else fadeIn(tween(220, delayMillis = 40)) togetherWith fadeOut(tween(120))
                    },
                    label = "screen",
                    modifier = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())
                ) { target ->
                    val backToMore = { moreMenu = true }
                    when (target) {
                        null -> MoreScreen(
                            onOpen = { route -> moreMenu = false; viewModel.navigate(route) },
                            onDownloads = { downloadsOpen = true }
                        )
                        Route.HOME -> MobileHome(state, viewModel, onDualAudio = { moreMenu = false; viewModel.navigate(Route.DUAL_AUDIO) })
                        Route.MOVIES -> MobileBrowse(Route.MOVIES, state, viewModel)
                        Route.SERIES -> MobileBrowse(Route.SERIES, state, viewModel)
                        Route.DUAL_AUDIO -> MobileDualAudio(state, viewModel, onBack = backToMore)
                        Route.SEARCH -> MobileSearch(state, viewModel)
                        Route.MY_LIST -> MobileMyList(state, viewModel, onBack = backToMore)
                        Route.SOURCES -> MobileSources(state, viewModel, onBack = backToMore)
                        Route.SETTINGS -> MobileSettings(state, viewModel, onBack = backToMore)
                        Route.DEBUG -> MobileDebug(state, viewModel, onBack = backToMore)
                    }
                }
            }

            AnimatedVisibility(
                visible = state.detail != null && state.playback == null,
                enter = if (reduced) EnterTransition.None else fadeIn(tween(200)) + slideInVertically(tween(300)) { it / 8 },
                exit = if (reduced) ExitTransition.None else fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 10 },
                modifier = Modifier.zIndex(2f)
            ) {
                MobileDetail(state, viewModel)
            }

            AnimatedVisibility(
                visible = downloadsOpen && state.detail == null && state.playback == null,
                enter = if (reduced) EnterTransition.None else fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 10 },
                exit = if (reduced) ExitTransition.None else fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 12 },
                modifier = Modifier.zIndex(2f)
            ) {
                Box(Modifier.fillMaxSize().background(NovaPalette.background)) {
                    MobileDownloads(state, viewModel, onBack = { downloadsOpen = false })
                }
            }

            state.playback?.let { request ->
                Box(Modifier.zIndex(3f)) {
                    NovaPlayerHost(request = request, viewModel = viewModel) { player, title ->
                        MobilePlayerSurface(player = player, title = title, viewModel = viewModel)
                    }
                }
            }

            MobileToast(
                message = state.toast,
                raised = state.playback == null && state.detail == null,
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(4f)
            )
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = NovaPalette.background.copy(alpha = 0.97f), tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = MType.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NovaPalette.textPrimary,
                    selectedTextColor = NovaPalette.textPrimary,
                    indicatorColor = NovaPalette.accent.copy(alpha = 0.28f),
                    unselectedIconColor = NovaPalette.textMuted,
                    unselectedTextColor = NovaPalette.textMuted
                )
            )
        }
    }
}

@Composable
private fun MobileToast(message: String?, raised: Boolean, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier.navigationBarsPadding().padding(bottom = if (raised) 92.dp else 28.dp)
    ) {
        Text(
            text = last,
            style = MType.body,
            modifier = Modifier
                .background(NovaPalette.surfaceHigh.copy(alpha = 0.97f), RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}
