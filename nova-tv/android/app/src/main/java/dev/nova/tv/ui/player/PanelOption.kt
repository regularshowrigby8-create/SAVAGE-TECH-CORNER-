package dev.nova.tv.ui.player

/**
 * One option in a player panel.
 *
 * The TV and mobile players draw the same Sources / Audio / Subtitles panels,
 * so the row model lives here once rather than as two file-private copies that
 * drift apart.
 */
internal data class PanelOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onPick: () -> Unit
)
