package dev.nova.tv.mobile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nova.tv.download.DownloadJob
import dev.nova.tv.download.DownloadStatus
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.ui.screens.NovaState
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.theme.NovaPalette

/** Offline downloads: progress, pause/resume, delete. */
@Composable
fun MobileDownloads(state: NovaState, viewModel: NovaViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads", onBack = onBack)
        if (state.downloads.isEmpty()) {
            EmptyNote("Downloads you start appear here.\nOpen a title and tap Download.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.downloads, key = { it.id }) { job -> DownloadRow(job, viewModel) }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun DownloadRow(job: DownloadJob, viewModel: NovaViewModel) {
    Column(Modifier.fillMaxWidth().background(NovaPalette.surface, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (job.subtitle.isNotBlank()) {
                    Text(job.subtitle, style = MType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(statusLabel(job), style = MType.meta.copy(color = if (job.status == DownloadStatus.FAILED) NovaPalette.accent else NovaPalette.textMuted))
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { job.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = NovaPalette.accent,
            trackColor = Color.White.copy(alpha = 0.14f)
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(sizeText(job), style = MType.meta, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                when (job.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        TextButton(onClick = { viewModel.pauseDownload(job.id) }) { Text("Pause", style = MType.label) }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                        TextButton(onClick = { viewModel.resumeDownload(job.id) }) { Text("Resume", style = MType.label) }
                    DownloadStatus.DONE -> Unit
                }
                TextButton(onClick = { viewModel.removeDownload(job.id) }) { Text("Delete", style = MType.label) }
            }
        }
        if (job.status == DownloadStatus.FAILED && job.error.isNotBlank()) {
            Text(job.error, style = MType.meta.copy(color = NovaPalette.accent), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun statusLabel(job: DownloadJob): String = when (job.status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.RUNNING -> "${(job.progress * 100).toInt()}%  ·  ${job.connections} connections"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.DONE -> "Downloaded"
    DownloadStatus.FAILED -> "Failed"
}

private fun sizeText(job: DownloadJob): String {
    val done = FinderRules.formatSize(job.bytes).ifBlank { "0 MB" }
    return if (job.totalBytes > 0) "$done / ${FinderRules.formatSize(job.totalBytes)}" else done
}
