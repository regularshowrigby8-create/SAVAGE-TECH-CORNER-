package dev.nova.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Text
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.FinderSourceReport
import dev.nova.tv.ui.components.FilterChip
import dev.nova.tv.ui.components.FocusableRow
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaType

/**
 * Source Finder: the ZeroPlay server-finder, as a Nova screen.
 *
 * ZeroPlay's `lib/server-finder/core.js` searches public addon directories
 * for Stremio stream addons. Nova has no JavaScript runtime, so the same
 * rules live in Kotlin (`finder/FinderRules.kt`, `finder/SourceFinder.kt`).
 * The rule that matters is kept exactly: discovery never installs anything.
 * A candidate is added only after it returns a playable stream for a real
 * title, and its streams then feed the one player like any other source.
 */
@Composable
fun FinderPane(state: NovaState, viewModel: NovaViewModel) {
    val finder = state.finder
    val report = finder.report

    TvLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column {
                Text("Source Finder", style = NovaType.display)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Searches stremio-addons.net, the Cinemeta addon catalogue and GitHub for stream " +
                        "addons. A source is only added after it plays a real title.",
                    style = NovaType.meta,
                    maxLines = 2
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FinderRules.SOURCE_IDS.forEach { id ->
                        FilterChip(
                            label = directoryName(id),
                            selected = id in finder.enabledSources
                        ) { viewModel.toggleFinderSource(id) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(if (finder.running) "Searching…" else "Run finder") { viewModel.runFinder() }
                    if (!report?.candidates.isNullOrEmpty()) {
                        SecondaryButton("Test all & add") { viewModel.testCandidates(report!!.candidates) }
                    }
                }
                finder.message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = NovaType.body, color = NovaPalette.accent)
                }
            }
        }

        if (report != null) {
            item { Text("Directories", style = NovaType.sectionTitle, modifier = Modifier.padding(top = 10.dp)) }
            items(report.sources, key = { "dir-" + it.id }) { dir -> DirectoryRow(dir) }

            if (report.candidates.isNotEmpty()) {
                item { Text("Candidates", style = NovaType.sectionTitle, modifier = Modifier.padding(top = 10.dp)) }
                items(report.candidates, key = { it.manifestUrl }) { candidate ->
                    CandidateRow(candidate, finder.statusOf(candidate)) {
                        viewModel.testCandidates(listOf(candidate))
                    }
                }
            }
        }
    }
}

private fun directoryName(id: String): String = when (id) {
    "stremio-addons" -> "stremio-addons.net"
    "catalog-graph" -> "Cinemeta catalogue"
    "github" -> "GitHub"
    else -> id
}

@Composable
private fun DirectoryRow(dir: FinderSourceReport) {
    val color = when (dir.status) {
        FinderSourceReport.Status.COMPLETE -> NovaPalette.accent
        FinderSourceReport.Status.PARTIAL -> NovaPalette.textPrimary
        FinderSourceReport.Status.FAILED -> NovaPalette.danger
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(directoryName(dir.id), style = NovaType.body)
        Text(dir.status.name.lowercase(), style = NovaType.meta, color = color)
        Text(
            "${dir.seen} seen · ${dir.checked} checked · ${dir.candidates} candidate(s)",
            style = NovaType.meta
        )
        if (dir.error.isNotBlank()) {
            Text(dir.error, style = NovaType.meta, color = NovaPalette.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CandidateRow(candidate: FinderCandidate, status: CandidateStatus, onTest: () -> Unit) {
    val (label, color) = when (status) {
        CandidateStatus.Untested -> "OK to test & add" to NovaPalette.textMuted
        CandidateStatus.Testing -> "Testing…" to NovaPalette.textPrimary
        is CandidateStatus.Verified -> "Plays ${status.title} (${status.streams})" to NovaPalette.accent
        is CandidateStatus.NoStreams -> "Nothing playable for ${status.title}" to NovaPalette.danger
        CandidateStatus.Installed -> "Added" to NovaPalette.accent
    }
    FocusableRow(
        onClick = { if (status != CandidateStatus.Testing && status != CandidateStatus.Installed) onTest() },
        trailing = { Text(label, style = NovaType.meta, color = color, maxLines = 1) }
    ) {
        Column {
            Text(candidate.name, style = NovaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    "from ${directoryName(candidate.from)}",
                    candidate.types.joinToString("/").ifBlank { null },
                    candidate.stars.takeIf { it > 0 }?.let { "★ $it" },
                    candidate.baseUrl.removePrefix("https://")
                ).joinToString("  ·  "),
                style = NovaType.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (candidate.description.isNotBlank()) {
                Text(candidate.description, style = NovaType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
