package dev.nova.tv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nova.tv.BuildConfig
import dev.nova.tv.data.remote.SubtitleProvider
import dev.nova.tv.finder.FinderKind
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.Route
import dev.nova.tv.ui.theme.NovaPalette

/** The More tab: the pages that do not need a tab of their own. */
@Composable
fun MoreScreen(onOpen: (Route) -> Unit, onDownloads: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp)) {
        Text("NOVA", style = MType.wordmark, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp))
        MoreRow("Downloads", Icons.Filled.KeyboardArrowDown, onDownloads)
        MoreRow("My List", Icons.Filled.Favorite) { onOpen(Route.MY_LIST) }
        MoreRow("Dual Audio", Icons.Filled.Star) { onOpen(Route.DUAL_AUDIO) }
        MoreRow("Sources", Icons.Filled.Search) { onOpen(Route.SOURCES) }
        MoreRow("Settings", Icons.Filled.Settings) { onOpen(Route.SETTINGS) }
    }
}

@Composable
private fun MoreRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = NovaPalette.textMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MType.body, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = NovaPalette.textMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobileSources(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sources", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Section("Finders") }
            items(FinderKind.entries, key = { it.name }) { kind ->
                val running = kind in state.finderRunning
                val found = state.finderReports[kind]?.candidates?.size
                SettingRow(kind.label, onClick = { viewModel.toggleFinder(kind) }) {
                    when {
                        running -> Text("Searching", style = MType.meta.copy(color = NovaPalette.accent))
                        kind == FinderKind.ZEROPLAY -> Text("${state.sitePool.size} sites", style = MType.meta)
                        kind == FinderKind.STREMIO -> Text("${state.pool.size} servers", style = MType.meta)
                        found != null -> Text("$found found", style = MType.meta)
                    }
                    Spacer(Modifier.width(10.dp))
                    NovaSwitch(kind in state.finders) { viewModel.toggleFinder(kind) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FinderKind.entries.filter { it in state.finders }.forEach { kind ->
                        FilledTonalButton(onClick = { viewModel.runFinder(kind) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run ${kind.label.substringBefore(' ')}", style = MType.label)
                        }
                    }
                }
            }

            item { Section("Server pools") }
            // Two pools, two finders: switch one finder off above and the other
            // pool keeps serving playback on its own.
            item {
                SettingRow("Addon pool", onClick = viewModel::fillServers) {
                    if (state.poolBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NovaPalette.accent)
                    else Text("${state.pool.size} / ${state.pool.capacity}", style = MType.meta)
                }
            }
            item {
                SettingRow("Addon servers working", onClick = null) {
                    Text("${state.workingServers.size}", style = MType.meta)
                }
            }
            items(state.workingServers.take(6), key = { "srv:" + it }) { name ->
                SettingRow(name, onClick = null) { Text("Working", style = MType.meta.copy(color = NovaPalette.accent)) }
            }
            item {
                SettingRow("Website pool", onClick = viewModel::findWebsites) {
                    Text("${state.sitePool.size} sites", style = MType.meta)
                }
            }
            // The finder's own tally of why sites were dropped. Without it a
            // flat "0 sites" is indistinguishable from "no network" and the
            // only way to tell was to guess -- which is how this got guessed at
            // three times.
            if (state.siteRejections.isNotEmpty()) {
                item {
                    Text(
                        "Last round: ${state.siteRejections}",
                        style = MType.meta.copy(color = NovaPalette.textMuted),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            item {
                SettingRow("Websites working", onClick = null) {
                    Text("${state.sitePool.working}", style = MType.meta)
                }
            }
            items(state.workingSites.take(6), key = { "site:" + it }) { name ->
                SettingRow(name, onClick = null) { Text("Working", style = MType.meta.copy(color = NovaPalette.accent)) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::findWebsites, enabled = !state.poolBusy) { Text("Find sites", style = MType.label) }
                    FilledTonalButton(onClick = viewModel::fillServers, enabled = !state.poolBusy) { Text("Fill addons", style = MType.label) }
                    FilledTonalButton(onClick = viewModel::scrambleServers, enabled = !state.poolBusy) { Text("Scramble", style = MType.label) }
                    if (state.pool.canRevert) {
                        FilledTonalButton(onClick = viewModel::revertServers, enabled = !state.poolBusy) { Text("Revert", style = MType.label) }
                    }
                }
            }
            if (state.workingServers.isEmpty() && state.sitePool.working == 0) {
                item {
                    Text(
                        "Nothing has answered yet. Find sites, fill addons or scramble to bring new sources in.",
                        style = MType.meta,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            item { Section("Websites") }
            item {
                Button(onClick = viewModel::startAddWebsite) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add website", style = MType.label)
                }
            }
            items(state.websites, key = { "w:" + it.url }) { site ->
                ItemRow(site.name, site.url) { viewModel.removeWebsite(site.url) }
            }

            item { Section("Subtitle sources") }
            items(SubtitleProvider.entries, key = { "sub:" + it.name }) { provider ->
                SettingRow(provider.label, onClick = { viewModel.toggleSubtitleProvider(provider) }) {
                    Text(provider.detail, style = MType.meta)
                    Spacer(Modifier.width(10.dp))
                    NovaSwitch(provider in state.subtitleProviders) { viewModel.toggleSubtitleProvider(provider) }
                }
            }

            item { Section("Installed") }
            item {
                Button(onClick = viewModel::startAddAddon) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add addon", style = MType.label)
                }
            }
            items(state.addons, key = { "a:" + it.baseUrl }) { addon ->
                if (addon.isBuiltIn) {
                    SettingRow(addon.name, onClick = null) { Text("Built in", style = MType.meta) }
                } else {
                    ItemRow(addon.name, addon.baseUrl) { viewModel.removeAddon(addon.baseUrl) }
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    if (state.addingWebsite) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAddWebsite,
            title = { Text("Add website", style = MType.title) },
            text = {
                OutlinedTextField(
                    value = state.websiteDraft,
                    onValueChange = viewModel::setWebsiteDraft,
                    singleLine = true,
                    placeholder = { Text("example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveWebsite, enabled = state.websiteDraft.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelAddWebsite) { Text("Cancel") } },
            containerColor = NovaPalette.surface
        )
    }

    if (state.addingAddon) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAddAddon,
            title = { Text("Add addon", style = MType.title) },
            text = {
                OutlinedTextField(
                    value = state.addonDraft,
                    onValueChange = viewModel::setAddonDraft,
                    singleLine = true,
                    placeholder = { Text("addon.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveAddon, enabled = state.addonDraft.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelAddAddon) { Text("Cancel") } },
            containerColor = NovaPalette.surface
        )
    }
}

@Composable
fun MobileSettings(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack = onBack)
        Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow("Autoplay next episode", onClick = viewModel::toggleAutoplay) { NovaSwitch(state.autoplayNext) { viewModel.toggleAutoplay() } }
            SettingRow("Reduce motion", onClick = { viewModel.setReduceMotion(!state.reduceMotion) }) {
                NovaSwitch(state.reduceMotion) { viewModel.setReduceMotion(it) }
            }
            SettingRow("Video quality", onClick = viewModel::cycleQuality) { Text(if (state.preferredQuality == "auto") "Best available" else state.preferredQuality, style = MType.meta) }
            SettingRow("Anti-buffering", onClick = viewModel::cyclePreload) { Text(state.preload.label, style = MType.meta) }
            SettingRow("DNS", onClick = viewModel::cycleDns) { Text(state.dns.label, style = MType.meta) }
            SettingRow("Prefer English audio", onClick = viewModel::togglePreferEnglishAudio) {
                NovaSwitch(state.preferEnglishAudio) { viewModel.togglePreferEnglishAudio() }
            }
            SettingRow("Clear watch history", onClick = viewModel::clearHistory) {}
            SettingRow("Reload catalogue", onClick = viewModel::refresh) { Text("Nova ${BuildConfig.VERSION_NAME}", style = MType.meta) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MType.section, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
}

@Composable
private fun SettingRow(label: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(NovaPalette.surface, RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun ItemRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(NovaPalette.surface, RoundedCornerShape(14.dp)).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove $title", tint = NovaPalette.textMuted) }
    }
}

@Composable
private fun NovaSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = NovaPalette.accent, checkedThumbColor = NovaPalette.textPrimary)
    )
}
