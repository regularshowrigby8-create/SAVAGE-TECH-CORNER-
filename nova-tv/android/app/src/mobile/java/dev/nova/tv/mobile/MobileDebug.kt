package dev.nova.tv.mobile

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nova.tv.finder.NovaDiagnostics
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.theme.NovaPalette

/**
 * The phone's copy of the self-test.
 *
 * The finder, the pool and the card maths are shared code, so the same ten
 * checks run here. The geometry block is the interesting one on a phone: it
 * reports the poster and landscape widths the phone actually computed, which is
 * how you confirm the cards are scaling to this screen rather than to a guess.
 */
@Composable
fun MobileDebug(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) {
    val report = state.diagnostics

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("Debug", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("intro") {
                Column {
                    Text(
                        "Self-test for the finder rules, the pool and the layout maths. " +
                            "No network is used, so a failure here is a code fault, not a bad connection.",
                        style = MType.meta,
                        color = NovaPalette.textMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = viewModel::runDiagnostics) {
                            Text(if (state.diagnosticsRunning) "Running\u2026" else "Run checks")
                        }
                        FilledTonalButton(onClick = onBack) { Text("Back") }
                    }
                    if (report != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(report.summary, style = MType.body)
                    }
                }
            }

            item("geometry") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(NovaPalette.surface, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Layout on this screen", style = MType.section)
                    GeometryRow("Poster width", "${PosterWidth.value.toInt()} dp")
                    GeometryRow("Landscape width", "${LandscapeWidth.value.toInt()} dp")
                }
            }

            if (report != null) {
                item("header") { Text("Checks", style = MType.section) }
                items(report.checks, key = { it.name }) { check ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(NovaPalette.surface, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(CheckColor(check.status), CircleShape))
                            Spacer(Modifier.size(8.dp))
                            Text(check.name, style = MType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(check.detail, style = MType.meta, color = NovaPalette.textMuted)
                        if (check.status != NovaDiagnostics.Status.PASS && check.hint.isNotBlank()) {
                            Text(check.hint, style = MType.meta, color = NovaPalette.danger)
                        }
                    }
                }
                item("footer") {
                    Text(
                        "Completed in ${report.elapsedMs} ms",
                        style = MType.meta,
                        color = NovaPalette.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun GeometryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MType.meta, color = NovaPalette.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MType.meta)
    }
}

private fun CheckColor(status: NovaDiagnostics.Status) = when (status) {
    NovaDiagnostics.Status.PASS -> NovaPalette.accent
    NovaDiagnostics.Status.WARN -> NovaPalette.textMuted
    NovaDiagnostics.Status.FAIL -> NovaPalette.danger
}
