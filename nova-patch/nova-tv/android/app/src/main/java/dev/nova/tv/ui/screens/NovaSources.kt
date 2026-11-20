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
import dev.nova.tv.finder.pool.PoolState
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.website.SitePoolState
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
                            when {
                                state.poolBusy -> "Updating"
                                state.poolFilling -> "Stop"
                                else -> "${state.workingServers.size} working"
                            },
                            style = NovaType.meta,
                            color = if (state.poolBusy || state.poolFilling) NovaPalette.accent else NovaPalette.textMuted
                        )
                    }
                ) {
                    Column {
                        Text("Working servers", style = NovaType.body)
                        // Every number here is measured from the pool, and the
                        // status line below comes from the harvester, so
                        // "filling" is only shown while a crawl is running.
                        Text(
                            "${state.pool.size} / ${state.pool.capacity} slots"
                                + if (state.poolFilling) " · filling" else if (state.pool.full) " · full" else " · not filling",
                            style = NovaType.meta
                        )
                        PoolProgress(state.pool)
                        if (state.pool.lastError.isNotBlank()) {
                            Text(state.pool.lastError, style = NovaType.meta, color = NovaPalette.accent)
                        }
                    }
                }
            }
            state.harvest?.let { harvest ->
                item {
                    Text(
                        harvestLine(harvest),
                        style = NovaType.meta,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SecondaryButton("Scramble servers", icon = Icons.Filled.Refresh, onClick = viewModel::scrambleServers)
                    if (state.pool.canRevert) SecondaryButton("Revert", onClick = viewModel::revertServers)
                }
            }

            item { SectionLabel("Websites") }
            item {
                // The second pool. Kept visually separate from "Servers" because
                // they are not interchangeable: a server is an addon that answers
                // with streams, a website is resolved by following its pages to a
                // direct media URL.
                Column {
                    Text(
                        "${state.sites.size} / ${state.sites.capacity} discovered sites"
                            + if (state.sites.full) " · full" else "",
                        style = NovaType.meta
                    )
                    if (state.sites.rejectedTotal > 0) {
                        Text(
                            "${state.sites.rejectedTotal} dropped: they did not answer a search",
                            style = NovaType.meta
                        )
                    }
                    if (state.sites.lastError.isNotBlank()) {
                        Text(state.sites.lastError, style = NovaType.meta, color = NovaPalette.accent)
                    }
                }
            }
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
            item {
                PrimaryButton("Add addon", icon = Icons.Filled.Add, onClick = viewModel::startAddAddon)
            }
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
            UrlSheet(
                title = "Add website",
                draft = state.websiteDraft,
                placeholder = "example.com",
                onKey = viewModel::typeWebsite,
                onBackspace = viewModel::backspaceWebsite,
                onClear = { repeat(state.websiteDraft.length) { viewModel.backspaceWebsite() } },
                onSave = viewModel::saveWebsite,
                onCancel = viewModel::cancelAddWebsite,
                extra = listOf(".com" to { ".com".forEach(viewModel::typeWebsite) })
            )
        }
        AnimatedVisibility(visible = state.addingAddon, enter = fadeIn(), exit = fadeOut()) {
            UrlSheet(
                title = "Add addon",
                draft = state.addonDraft,
                placeholder = "addon.example.com",
                onKey = viewModel::typeAddon,
                onBackspace = viewModel::backspaceAddon,
                onClear = { repeat(state.addonDraft.length) { viewModel.backspaceAddon() } },
                onSave = viewModel::saveAddon,
                onCancel = viewModel::cancelAddAddon,
                extra = listOf("/manifest.json" to { "/manifest.json".forEach(viewModel::typeAddon) })
            )
        }
    }
}

@Composable
private fun UrlSheet(
    title: String,
    draft: String,
    placeholder: String,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    extra: List<Pair<String, () -> Unit>>
) {
    val firstKey = remember { FocusRequester() }
    // The sheet fades in, so the keyboard's first key may not be attached on
    // the very next frame. A single attempt used to leave focus on the button
    // *behind* the overlay, and because the overlay traps the D-pad
    // (onExit = cancelFocusChange) the sheet then looked completely dead.
    LaunchedEffect(Unit) {
        repeat(12) {
            withFrameNanos { }
            if (runCatching { firstKey.requestFocus() }.isSuccess) return@LaunchedEffect
        }
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
            Text(title, style = NovaType.display)
            QueryField(draft.ifEmpty { placeholder }, placeholder = draft.isEmpty())
            OnScreenKeyboard(
                layout = KeyboardLayout.URL,
                onKey = onKey,
                onBackspace = onBackspace,
                onClear = onClear,
                firstKey = firstKey,
                extra = extra
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Save", onClick = onSave)
                SecondaryButton("Cancel", onClick = onCancel)
            }
        }
    }
}

/** Settings: only what a viewer would actually change. */
@Composable
fun SettingsScreen(state: NovaState, viewModel: NovaViewModel) {
    // Eight rows, wired in order: Up/Down always move exactly one row and the
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
            onClick = viewModel::cycleQuality,
            modifier = Modifier.focusColumnRow(3, rows),
            trailing = { Text("${state.preferredQuality}  ·  1080p max", style = NovaType.meta) }
        ) { Text("Playback quality", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::cycleDns,
            modifier = Modifier.focusColumnRow(4, rows),
            trailing = { Text(state.dns.label, style = NovaType.meta) }
        ) { Text("DNS", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::togglePreferEnglishAudio,
            modifier = Modifier.focusColumnRow(5, rows),
            trailing = { Toggle(state.preferEnglishAudio) }
        ) { Text("Prefer English audio", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::clearHistory,
            modifier = Modifier.focusColumnRow(6, rows)
        ) { Text("Clear watch history", style = NovaType.body) }
        FocusableRow(
            onClick = viewModel::refresh,
            modifier = Modifier.focusColumnRow(7, rows),
            trailing = { Text("Nova ${BuildConfig.VERSION_NAME}", style = NovaType.meta) }
        ) { Text("Reload catalogue", style = NovaType.body) }
    }
}

private const val SETTINGS_ROWS = 8

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

/**
 * What the crawl is actually doing. Every field comes from
 * [ServerHarvester.HarvestState]; nothing here is guessed, and "stopped" always
 * carries the reason it stopped.
 */
private fun harvestLine(harvest: ServerHarvester.HarvestState): String = when {
    harvest.rateLimited > 0 && harvest.running ->
        "Rate limited ${harvest.rateLimited}× · ${harvest.found} found · ${harvest.requests} requests"
    harvest.running ->
        "${harvest.found} found · ${harvest.sourcesDone}/${harvest.sourceCount} sources done · " +
            "${harvest.requests} requests · pool ${harvest.poolSize}/${harvest.target}"
    harvest.exhausted ->
        "All ${harvest.sourceCount} sources exhausted at ${harvest.poolSize}/${harvest.target} · " +
            "${harvest.found} found in ${harvest.rounds} rounds"
    harvest.stopped.isNotEmpty() ->
        "Stopped (${harvest.stopped}) at ${harvest.poolSize}/${harvest.target}"
    else -> "Not running"
}

/** A 2 dp bar. It is a real fraction of the pool, not an animation. */
@Composable
private fun PoolProgress(pool: PoolState) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(2.dp)
            .background(NovaPalette.surfaceHigh)
    ) {
        Box(
            Modifier
                .fillMaxWidth(pool.progress)
                .height(2.dp)
                .background(NovaPalette.accent)
        )
    }
}
