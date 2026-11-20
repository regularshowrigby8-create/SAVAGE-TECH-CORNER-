package dev.nova.tv.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.nova.tv.finder.NovaDiagnostics
import dev.nova.tv.ui.components.PrimaryButton
import dev.nova.tv.ui.components.SecondaryButton
import dev.nova.tv.ui.theme.NovaCards
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType

/**
 * Debug: the on-device self-test.
 *
 * Every claim about the finder used to be a paragraph of reasoning. This screen
 * turns each one into a line that says PASS or FAIL with the number it measured,
 * so a bug report can be a screenshot instead of an argument.
 *
 * It also prints the geometry the app actually computed for this television,
 * which is the fastest way to confirm the cards are scaling rather than being
 * a fixed size that happens to look right on one panel.
 */
@Composable
fun DebugScreen(state: NovaState, viewModel: NovaViewModel) {
    val report = state.diagnostics
    val metrics = NovaCards.metrics

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NovaSpace.screenStart,
            end = NovaSpace.safeHorizontal,
            top = NovaSpace.safeVertical,
            bottom = 140.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("title") {
            Column {
                Text("Debug", style = NovaType.display)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Self-test for the finder rules, the pool and the layout maths. " +
                        "No network is used, so a failure here is a code fault, not a bad connection.",
                    style = NovaType.body,
                    color = NovaPalette.textMuted
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        if (state.diagnosticsRunning) "Running\u2026" else "Run checks",
                        onClick = viewModel::runDiagnostics
                    )
                    SecondaryButton("Back to settings", onClick = { viewModel.navigate(Route.SETTINGS) })
                }
                if (report != null) {
                    Spacer(Modifier.height(18.dp))
                    ResultBanner(report)
                }
            }
        }

        item("geometry") {
            SectionCard("Layout on this screen") {
                MetricRow("Poster width", "${metrics.posterWidth.value.toInt()} dp")
                MetricRow("Landscape width", "${metrics.landscapeWidth.value.toInt()} dp")
                MetricRow("Episode width", "${metrics.episodeWidth.value.toInt()} dp")
                MetricRow("Gap", "${metrics.gap.value.toInt()} dp")
                MetricRow("Grid columns", "${metrics.gridColumns}")
                MetricRow("Posters per row", "${metrics.visiblePosters}")
                MetricRow("Content start", "${metrics.contentStart.value.toInt()} dp")
            }
        }

        if (report != null) {
            item("checks-header") {
                Text("Checks", style = NovaType.sectionTitle, modifier = Modifier.padding(top = 12.dp))
            }
            items(report.checks, key = { it.name }) { check ->
                CheckRow(check)
            }
            item("footer") {
                Text(
                    "Completed in ${report.elapsedMs} ms",
                    style = NovaType.meta,
                    color = NovaPalette.textMuted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            item("empty") {
                Text(
                    "Not run yet.",
                    style = NovaType.body,
                    color = NovaPalette.textMuted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultBanner(report: NovaDiagnostics.Report) {
    val tone = when {
        report.failed > 0 -> NovaPalette.danger
        report.warned > 0 -> NovaPalette.accent
        else -> Color(0xFF6FCF97)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(10.dp).background(tone, CircleShape))
        Text(report.summary, style = NovaType.body, color = NovaPalette.textPrimary)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NovaPalette.surface, RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Text(title, style = NovaType.sectionTitle)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = NovaType.meta, color = NovaPalette.textMuted)
        Text(value, style = NovaType.meta, color = NovaPalette.textPrimary)
    }
}

@Composable
private fun CheckRow(check: NovaDiagnostics.Check) {
    val tone = when (check.status) {
        NovaDiagnostics.Status.PASS -> Color(0xFF6FCF97)
        NovaDiagnostics.Status.WARN -> NovaPalette.accent
        NovaDiagnostics.Status.FAIL -> NovaPalette.danger
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(NovaPalette.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            Text(check.name, style = NovaType.body, color = NovaPalette.textPrimary, modifier = Modifier.weight(1f))
            Text(
                check.status.name,
                style = NovaType.meta,
                color = tone,
                modifier = Modifier
                    .background(tone.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            check.detail,
            style = NovaType.meta,
            color = NovaPalette.textMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp)
        )
        if (check.hint.isNotBlank() && check.status != NovaDiagnostics.Status.PASS) {
            Spacer(Modifier.height(4.dp))
            Text(
                check.hint,
                style = NovaType.meta,
                color = tone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
    }
}
