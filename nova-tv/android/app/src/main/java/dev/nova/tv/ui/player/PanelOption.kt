package dev.nova.tv.ui.player

/**
 * One row in a player panel (sources, quality, audio, subtitles, speed).
 *
 * Shared rather than redeclared per surface: the TV panel and the touch panel
 * render the same options from the same player state, so they must agree on
 * the shape of a row.
 */
internal data class PanelOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onPick: () -> Unit
)
