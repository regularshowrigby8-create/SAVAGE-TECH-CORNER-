package dev.nova.tv.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nova.tv.finder.diag.FinderTrace
import dev.nova.tv.ui.theme.NovaPalette

/**
 * What the finders actually did, in their own words.
 *
 * This exists because every attempt to fix "Website finder 0 sites" without it
 * was a guess, and guesses were wrong twice. The trace is plain text so it can
 * be read on the phone, screenshotted, or copied and pasted verbatim -- whichever
 * is easiest -- and it answers the questions the UI never did: did the WebView
 * render at all, which seed answered, what did the probe see, and what killed
 * each candidate.
 *
 * A raw log is deliberately chosen over a pretty summary: a summary would be
 * another place for the truth to get lost.
 */
@Composable
fun MobileDiagnostics(onBack: () -> Unit) {
    val context = LocalContext.current
    // Bumped to re-read the trace after a copy or a manual refresh.
    var refresh by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val lines = remember(refresh) { FinderTrace.snapshot() }
    val totals = remember(refresh) { FinderTrace.tally() }
    val dump = remember(refresh) { FinderTrace.dump() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Diagnostics", onBack = onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = {
                copyToClipboard(context, dump)
                copied = true
            }) { Text(if (copied) "Copied" else "Copy all", style = MType.label) }
            FilledTonalButton(onClick = { refresh++ }) { Text("Refresh", style = MType.label) }
            FilledTonalButton(onClick = {
                FinderTrace.clear()
                copied = false
                refresh++
            }) { Text("Clear", style = MType.label) }
        }
        Spacer(Modifier.height(8.dp))

        if (lines.isEmpty()) {
            Text(
                "Nothing recorded yet. Use Run Website or Run Addon on the Sources " +
                    "screen, then come back here.",
                style = MType.meta,
                color = NovaPalette.textMuted,
                modifier = Modifier.padding(horizontal = ScreenPadding)
            )
            return@Column
        }

        // Totals first: these are the numbers that name the fault.
        Text(
            totals,
            style = MType.meta,
            color = NovaPalette.accent,
            modifier = Modifier.padding(horizontal = ScreenPadding)
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Newest first: the interesting end of a crawl is the end.
            items(lines.reversed()) { line ->
                Text(
                    line,
                    style = MType.meta,
                    color = when {
                        line.contains("ACCEPT") -> NovaPalette.accent
                        line.contains("UNREACHABLE") || line.contains("EMPTY") ->
                            NovaPalette.textPrimary
                        else -> NovaPalette.textMuted
                    }
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Nova finder trace", text))
}
