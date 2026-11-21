package dev.nova.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.nova.tv.BuildConfig
import dev.nova.tv.finder.FinderKind
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.RegisterContentEntry
import dev.nova.tv.ui.components.focusColumnRow
import dev.nova.tv.ui.components.rememberFocusColumn
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.components.StatusDot
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

/**
 * Sources: the three finders, the viewer's websites, and installed addons.
 * Everything works with no setup; this screen exists to switch things off
 * or add a favourite website.
 */
@Composable
fun SourcesScreen(state: NovaState, viewModel: NovaViewModel) {
    // The website sheet held focus; when it closes, hand focus back to its button.
    val addSite = remember { FocusRequester() }
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.addingWebsite) {
        if (sheetWasOpen && !state.addingWebsite) {
            withFrameNanos { }
            runCatching { addSite.requestFocus() }
        }
        sheetWasOpen = state.addingWebsite
    }
    // The rail hands focus to the first finder (see RegisterContentEntry), and the
    // screen takes focus on its own when the route is clicked in the rail.
    val firstRow = remember { FocusRequester() }
    RegisterContentEntry(firstRow)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRow.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().arvioDpadFocusGroup(),
            contentPadding = PaddingValues(start = ContentStart, end = NovaSpace.safeHorizontal, top = NovaSpace.safeVertical, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(NovaSpace.listGap)
        ) {
            item { Text("Sources", style = NovaType.display) }
            item { SectionLabel("Finders") }
            items(FinderKind.entries, key = { it.name }) { kind ->
                val on = kind in state.finders
                val running = kind in state.finderRunning
                val found = state.finderReports[kind]?.candidates?.size
                FocusableRow(
                    onClick = { viewModel.toggleFinder(kind) },
                    modifier = if (kind == FinderKind.entries.first()) Modifier.focusRequester(firstRow) else Modifier,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (running) Text("Searching", style = NovaType.meta, color = NovaPalette.accent)
                            else if (found != null) Text("$found found", style = NovaType.meta)
                            Toggle(on)
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(on)
                        Spacer(Modifier.width(14.dp))
                        Text(kind.label, style = NovaType.body)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FinderKind.entries.filter { it in state.finders }.forEach { kind ->
                        SecondaryButton("Run ${kind.label.substringBefore(' ')}", icon = Icons.Filled.Refresh) { viewModel.runFinder(kind) }
                    }
                }
            }

            item { SectionLabel("Servers") }
            item {
                FocusableRow(
                    onClick = viewModel::fillServers,
                    trailing = {
                        Text(
                            if (state.poolBusy) "Updating" else "${state.workingServers.size} working",
                            style = NovaType.meta,
                            color = if (state.poolBusy) NovaPalette.accent else NovaPalette.textMuted
                        )
                    }
                ) {
                    Column {
                        Text("Working servers", style = NovaType.body)
                        // The finders share this pool; whatever is switched on
                        // keeps filling it until every slot is used.
                        Text(
                            "${state.pool.size} / ${state.pool.capacity} slots · filled by the finders that are on",
                            style = NovaType.meta
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton("Scramble servers", icon = Icons.Filled.Refresh, onClick = viewModel::scrambleServers)
                    if (state.pool.canRevert) SecondaryButton("Revert", onClick = viewModel::revertServers)
                }
            }

            item { SectionLabel("Websites") }
            item { PrimaryButton("Add website", icon = Icons.Filled.Add, modifier = Modifier.focusRequester(addSite), onClick = viewModel::startAddWebsite) }
            items(state.websites, key = { it.url }) { site ->
                FocusableRow(onClick = { viewModel.removeWebsite(site.url) }, trailing = { Text("Remove", style = NovaType.meta) }) {
                    Column {
                        Text(site.name, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(site.url, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item { SectionLabel("Installed") }
            items(state.addons, key = { it.baseUrl }) { addon ->
                if (addon.isBuiltIn) {
                    // Built-ins cannot be removed, so they are not a focus stop.
                    Row(
                        Modifier.fillMaxWidth().background(NovaPalette.surface, RoundedCornerShape(dev.nova.tv.ui.theme.NovaShape.card)).padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(addon.name, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("Built in", style = NovaType.meta)
                    }
                } else {
                    FocusableRow(
                        onClick = { viewModel.removeAddon(addon.baseUrl) },
                        trailing = { Text("Remove", style = NovaType.meta) }
                    ) {
                        Text(addon.name, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.addingWebsite, enter = fadeIn(), exit = fadeOut()) {
            AddWebsiteSheet(state, viewModel)
        }
    }
}

@Composable
private fun AddWebsiteSheet(state: NovaState, viewModel: NovaViewModel) {
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(NovaPalette.background.copy(alpha = 0.94f))
            .focusProperties { onExit = { cancelFocusChange() } }
            .arvioDpadFocusGroup(enableFocusRestorer = false),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Add website", style = NovaType.display)
            QueryField(state.websiteDraft.ifEmpty { "example.com" }, placeholder = state.websiteDraft.isEmpty())
            OnScreenKeyboard(
                layout = KeyboardLayout.URL,
                onKey = viewModel::typeWebsite,
                onBackspace = viewModel::backspaceWebsite,
                onClear = { repeat(state.websiteDraft.length) { viewModel.backspaceWebsite() } },
                firstKey = firstKey,
                extra = listOf(".com" to { ".com".forEach(viewModel::typeWebsite) })
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Save", onClick = viewModel::saveWebsite)
                SecondaryButton("Cancel", onClick = viewModel::cancelAddWebsite)
            }
        }
    }
}

/** Settings: only what a viewer would actually change. */
@Composable
fun SettingsScreen(state: NovaState, viewModel: NovaViewModel) {
    // Seven rows, wired in order: Up/Down always move exactly one row and the
    // rail's Right lands on Autoplay rather than the row level with its icon.
    val rows = rememberFocusColumn(SETTINGS_ROWS)
    RegisterContentEntry(rows.first())
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { rows.first().requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = ContentStart, end = NovaSpace.safeHorizontal, top = NovaSpace.safeVertical)
            .arvioDpadFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(NovaSpace.listGap)
    ) {
        Text("Settings", style = NovaType.display)
        Spacer(Modifier.height(NovaSpace.rowGap - NovaSpace.listGap))
        FocusableRow(
            onClick = viewModel::toggleAutoplay,
            modifier = Modifier.focusColumnRow(0, rows),
            trailing = { Toggle(state.autoplayNext) }
        ) { Text("Autoplay next episode", style = NovaType.body) }
        FocusableRow(
            onClick = { viewModel.setReduceMotion(!state.reduceMotion) },
            modifier = Modifier.focusColumnRow(1, rows),
            trailing = { Toggle(state.reduceMotion) }
        ) { Text("Reduce motion", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::cyclePreload,
            modifier = Modifier.focusColumnRow(2, rows),
            trailing = { Text(state.preload.label, style = NovaType.meta) }
        ) { Text("Anti-buffering", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::cycleDns,
            modifier = Modifier.focusColumnRow(3, rows),
            trailing = { Text(state.dns.label, style = NovaType.meta) }
        ) { Text("DNS", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::togglePreferEnglishAudio,
            modifier = Modifier.focusColumnRow(4, rows),
            trailing = { Toggle(state.preferEnglishAudio) }
        ) { Text("Prefer English audio", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::clearHistory,
            modifier = Modifier.focusColumnRow(5, rows)
        ) { Text("Clear watch history", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::refresh,
            modifier = Modifier.focusColumnRow(6, rows),
            trailing = { Text("Nova ${BuildConfig.VERSION_NAME}", style = NovaType.meta) }
        ) { Text("Reload catalogue", style = NovaType.body) }
    }
}

private const val SETTINGS_ROWS = 7

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = NovaType.sectionTitle, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
}

/** A switch drawn to match the rest of the app. */
@Composable
internal fun Toggle(on: Boolean) {
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .background(if (on) NovaPalette.accent else NovaPalette.surfaceHigh, RoundedCornerShape(50))
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(22.dp).background(NovaPalette.textPrimary, RoundedCornerShape(50)))
    }
}
