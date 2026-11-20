// Imported from the ExoPlayer demo app
// (https://github.com/google/ExoPlayer, commit 0f7b1a6), Apache License 2.0.
// Original path: demos/main/src/main/java/com/google/android/exoplayer2/demo/TrackSelectionDialog.java
// and androidx/media3/ui/TrackSelectionView.
//
// Upstream builds a dialog of tabs, one per track type, and turns the viewer's
// choice into TrackSelectionParameters via `clearOverridesOfType` plus a single
// override. Nova keeps that conversion exactly, because it is the part that is
// easy to get wrong: clearing the overrides of one type must not disturb the
// other types' selections.
package dev.nova.tv.player.imported

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks

object TrackOptions {

    /** The three types upstream supports. */
    val SUPPORTED = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)

    /** Upstream's `willHaveContent`: an empty menu is worse than no menu. */
    fun willHaveContent(tracks: Tracks): Boolean =
        tracks.groups.any { it.type in SUPPORTED && it.length > 0 }

    /**
     * Select [trackIndex] inside [group] for [type], leaving every other type
     * alone. This is the `clearOverridesOfType` + `addOverride` pair from
     * upstream, which is what makes switching audio not switch subtitles.
     */
    fun select(
        current: TrackSelectionParameters,
        type: Int,
        group: TrackGroup,
        trackIndex: Int
    ): TrackSelectionParameters = current.buildUpon()
        .clearOverridesOfType(type)
        .setTrackTypeDisabled(type, false)
        .addOverride(TrackSelectionOverride(group, trackIndex))
        .build()

    /** Turn a whole type off, e.g. "Subtitles: Off". */
    fun disable(current: TrackSelectionParameters, type: Int): TrackSelectionParameters =
        current.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()

    /** Hand a type back to the player's adaptive selection. */
    fun automatic(current: TrackSelectionParameters, type: Int): TrackSelectionParameters =
        current.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .build()

    /**
     * Cap the video height without touching the audio or subtitle choice.
     * `Int.MAX_VALUE` clears the cap. This is the same builder call the player
     * already uses for its automatic downshift, so the manual pick and the
     * automatic one cannot disagree.
     */
    fun capHeight(current: TrackSelectionParameters, height: Int): TrackSelectionParameters =
        current.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, if (height <= 0) Int.MAX_VALUE else height)
            .build()

    /** Every track of [type] as (group, index, label) for a menu. */
    fun entries(tracks: Tracks, type: Int): List<TrackEntry> {
        val out = ArrayList<TrackEntry>()
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                out += TrackEntry(
                    group = group.mediaTrackGroup,
                    index = i,
                    label = format.label
                        ?: format.language?.uppercase()
                        ?: "${typeName(type)} ${out.size + 1}",
                    language = format.language,
                    selected = group.isTrackSelected(i)
                )
            }
        }
        return out
    }

    fun typeName(type: Int): String = when (type) {
        C.TRACK_TYPE_AUDIO -> "Audio"
        C.TRACK_TYPE_TEXT -> "Subtitle"
        C.TRACK_TYPE_VIDEO -> "Video"
        else -> "Track"
    }
}

data class TrackEntry(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val language: String?,
    val selected: Boolean
)

/** Convenience for callers that only have a [Player]. */
fun Player.selectTrack(type: Int, group: TrackGroup, index: Int) {
    trackSelectionParameters = TrackOptions.select(trackSelectionParameters, type, group, index)
}
