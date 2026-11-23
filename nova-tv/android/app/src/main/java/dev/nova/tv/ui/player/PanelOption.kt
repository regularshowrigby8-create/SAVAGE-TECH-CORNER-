package dev.nova.tv.ui.player

import dev.nova.tv.player.NovaPlayer
import dev.nova.tv.player.SubtitleStyle
import dev.nova.tv.player.engine.PlayerEngineState
import dev.nova.tv.player.imported.QualityLadder
import dev.nova.tv.player.imported.SpeedStep
import dev.nova.tv.player.imported.SubtitleOffsetController
import dev.nova.tv.ui.screens.NovaViewModel
import dev.nova.tv.ui.screens.PlayerPanel
import kotlin.math.abs

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

/** "Off", "+1.5s", "-0.8s": the delay rows and the touch chrome both use it. */
internal fun subtitleOffsetLabel(ms: Long): String = when {
    ms == 0L -> "Off"
    ms > 0 -> "+" + "%.1f".format(ms / 1000f) + "s"
    else -> "%.1f".format(ms / 1000f) + "s"
}

internal fun sourcePanelOptions(
    session: NovaPlayer.Session,
    onPick: (Int) -> Unit
): List<PanelOption> = session.sources.mapIndexed { i, stream ->
    val tried = i in session.tried && i != session.index
    PanelOption(
        label = stream.label,
        detail = when {
            i == session.index -> "Playing"
            tried -> "Failed"
            else -> null
        },
        selected = i == session.index
    ) { onPick(i) }
}

internal fun qualityPanelOptions(
    session: NovaPlayer.Session,
    qualityCapHeight: Int,
    onPick: (Int, Int) -> Unit
): List<PanelOption> = buildList {
    add(PanelOption("Auto", if (qualityCapHeight == 0) "Adaptive" else "Cap off", qualityCapHeight == 0) {
        onPick(session.index, 0)
    })
    QualityLadder.rungs(session.sources, session.index).forEach { rung ->
        val height = rung.label.removeSuffix("p").toIntOrNull() ?: 0
        add(PanelOption(rung.label, rung.detail, rung.streamIndex == session.index && height == qualityCapHeight) {
            onPick(rung.streamIndex, height)
        })
    }
}

internal fun subtitleDelayOptions(
    offsetMs: Long,
    onStep: (Long) -> Unit,
    onReset: () -> Unit
): List<PanelOption> = buildList {
    add(PanelOption("Delay later", subtitleOffsetLabel(offsetMs), false) { onStep(SubtitleOffsetController.STEP_MS) })
    add(PanelOption("Delay earlier", subtitleOffsetLabel(offsetMs), false) { onStep(-SubtitleOffsetController.STEP_MS) })
    if (offsetMs != 0L) add(PanelOption("Reset delay", subtitleOffsetLabel(offsetMs), false, onReset))
}

/**
 * Every row of [panel], built once for both surfaces.
 *
 * The panels used to build this list in two separate `when (panel)` blocks, so
 * a row added on TV silently did not exist on a phone (and the two drifted on
 * labels and detail text). There is now one list, and a surface only decides
 * how to draw it.
 *
 * Kept out of Compose on purpose: it reads player state and returns plain rows,
 * which is what makes a behaviour difference between the surfaces impossible
 * rather than merely unlikely.
 */
internal fun panelOptions(
    panel: PlayerPanel,
    player: NovaPlayer,
    viewModel: NovaViewModel,
    engine: PlayerEngineState,
    session: NovaPlayer.Session,
    subtitleStyle: SubtitleStyle,
    subtitleOffset: SubtitleOffsetController,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onAddSubtitleFile: () -> Unit
): List<PanelOption> = when (panel) {
    PlayerPanel.SOURCES -> sourcePanelOptions(session) { i ->
            player.selectSource(i)
            viewModel.closePlayerPanel()
    }

    PlayerPanel.QUALITY -> qualityPanelOptions(session, player.qualityCapHeight()) { index, height ->
            if (index != session.index) player.selectSource(index)
            player.setQualityCap(height)
            viewModel.closePlayerPanel()
        }

    PlayerPanel.AUDIO -> engine.audioTracks.map { track ->
        val detail = listOfNotNull(
            track.language?.uppercase(),
            track.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
            track.mimeType?.substringAfter('/')
        ).joinToString("  ·  ").ifBlank { null }
        PanelOption(track.displayLabel(), detail, track.index == engine.selectedAudioIndex) {
            player.engine.selectAudioTrack(track.index)
            viewModel.closePlayerPanel()
        }
    }

    PlayerPanel.SPEED -> SpeedStep.entries.map { step ->
        val on = abs(engine.playbackSpeed - step.value) < 0.01f
        PanelOption(step.label, if (on) "Playing" else null, on) {
            player.setRate(step.value)
            viewModel.setPlaybackRate(step.value)
            viewModel.closePlayerPanel()
        }
    }

    PlayerPanel.SUBTITLES -> buildList {
        add(
            PanelOption("Off", null, engine.selectedSubtitleTrack == null) {
                player.engine.selectSubtitleTrack(null)
                viewModel.closePlayerPanel()
            }
        )
        add(PanelOption("Add subtitle file…", "From this device", false, onAddSubtitleFile))
        // The engines are asked again on demand: a title that had no captions
        // at Play time often gains them a minute later.
        add(
            PanelOption(
                "Refresh subtitles",
                if (session.subtitles.isEmpty()) "Searching…" else "${session.subtitles.size} found",
                false
            ) { viewModel.reloadSubtitles() }
        )
        addAll(subtitleDelayOptions(offsetMs,
            onStep = { step ->
                subtitleOffset.step(step)
                onOffsetChange(subtitleOffset.offsetMs)
            },
            onReset = {
                subtitleOffset.offsetMs = 0L
                subtitleOffset.reset()
                onOffsetChange(0L)
            }
        ))
        // Style stays on screen while it is adjusted, unlike a track pick.
        add(
            PanelOption("Caption style", subtitleStyle.tone.label, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextTone())
            }
        )
        add(
            PanelOption("Caption size", subtitleStyle.sizeLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextSize())
            }
        )
        add(
            PanelOption("Caption box", subtitleStyle.backgroundLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextBackground())
            }
        )
        add(
            PanelOption("Caption height", subtitleStyle.bottomLabel, false) {
                viewModel.setSubtitleStyle(subtitleStyle.nextBottom())
            }
        )
        engine.subtitleTracks.forEach { track ->
            add(
                PanelOption(
                    track.displayLabel(),
                    if (track.isExternal) "Sidecar" else null,
                    track.id == engine.selectedSubtitleTrack?.id
                ) {
                    player.engine.selectSubtitleTrack(track)
                    viewModel.closePlayerPanel()
                }
            )
        }
        // Sidecar tracks can be taken back out; embedded ones cannot.
        session.subtitles.forEach { ref ->
            add(
                PanelOption("Remove ${ref.label}", ref.detail, false) {
                    player.removeSubtitle(ref.url)
                    viewModel.closePlayerPanel()
                }
            )
        }
    }
}
