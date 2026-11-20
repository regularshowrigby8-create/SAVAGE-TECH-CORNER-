package dev.nova.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Card geometry, derived from the screen instead of hard-coded.
 *
 * ## Why this exists
 *
 * The rows used a fixed `PosterWidth = 140.dp`. On a 1080p television that is
 * a 960 dp canvas, so a poster row showed four and a half cards -- the fifth
 * was always sliced by the screen edge, which reads as a bug rather than as
 * "there is more to the right". On a 720p set the same 140 dp is proportionally
 * larger again and only three fit. The cards were not "too big" so much as
 * *unscaled*: one number was being asked to serve every panel size.
 *
 * The fix is to decide how many cards a row should show, then divide the space
 * that is actually available. That makes the card a function of the screen, so
 * a 4K panel and a 720p panel both show the same *number* of cards at the same
 * *relative* size, which is what a viewer perceives as correct.
 *
 * ## The numbers
 *
 * [visiblePosters] is the design intent: six posters across a TV row. Six is
 * the point where a row still reads as a browsable strip without the artwork
 * becoming thumbnails; five felt sparse and seven made titles unreadable at
 * three metres. Landscape cards (Continue Watching) are 16:9 stills, so four
 * fit where six posters do.
 *
 * The result is clamped, because a divided value can still go wrong: on a very
 * small window six cards would be 60 dp and the artwork would be noise, so
 * [MIN_POSTER] and [MAX_POSTER] bracket it. The clamps are a safety net, not the
 * design -- on every real television the computed value lands between them.
 */
data class CardMetrics(
    /** Poster width for shelf rows. */
    val posterWidth: Dp,
    /** 16:9 still width for Continue Watching. */
    val landscapeWidth: Dp,
    /** Episode still width in the detail rail. */
    val episodeWidth: Dp,
    /** Gap between cards, horizontally and vertically. */
    val gap: Dp,
    /** Columns for the poster grid (Browse, My List). */
    val gridColumns: Int,
    /** Left inset that clears the navigation rail. */
    val contentStart: Dp,
    /** How many posters a shelf row is designed to show. */
    val visiblePosters: Int
) {
    /** Width a row occupies once the rail and the end fade are removed. */
    val rowWidth: Dp get() = posterWidth * visiblePosters + gap * (visiblePosters - 1)
}

object NovaSizing {

    /** Six across is the design intent for a poster row on a television. */
    const val VISIBLE_POSTERS = 6

    /** Four 16:9 stills occupy the same width as six 2:3 posters. */
    const val VISIBLE_LANDSCAPE = 4

    private val MIN_POSTER = 104.dp
    private val MAX_POSTER = 168.dp
    private val MIN_LANDSCAPE = 232.dp
    private val MAX_LANDSCAPE = 340.dp
    private val MIN_EPISODE = 196.dp
    private val MAX_EPISODE = 288.dp

    /** The rail is 96 dp wide; content starts 28 dp clear of it. */
    private val RAIL_WIDTH = 96.dp
    private val RAIL_CLEARANCE = 28.dp

    /** Space kept at the right edge so the last card is never flush. */
    private val END_INSET = 64.dp

    /**
     * Compute metrics for a canvas [screenWidth] dp wide.
     *
     * Pure and total: every branch returns a usable value, so a caller never
     * has to null-check geometry.
     */
    fun metrics(screenWidth: Dp): CardMetrics {
        val width = if (screenWidth.value > 0f) screenWidth else 960.dp
        val contentStart = RAIL_WIDTH + RAIL_CLEARANCE
        val gap = when {
            width < 720.dp -> 12.dp
            width < 1100.dp -> 18.dp
            else -> 24.dp
        }
        val available = (width - contentStart - END_INSET).coerceAtLeast(MIN_POSTER)
        val posters = VISIBLE_POSTERS
        val rawPoster = (available - gap * (posters - 1)) / posters
        val poster = rawPoster.coerceIn(MIN_POSTER, MAX_POSTER)

        val rawLandscape = (available - gap * (VISIBLE_LANDSCAPE - 1)) / VISIBLE_LANDSCAPE
        val landscape = rawLandscape.coerceIn(MIN_LANDSCAPE, MAX_LANDSCAPE)

        // Episode stills sit in a narrower column than a full row, so they are
        // sized off the landscape card rather than the row width.
        val episode = (landscape * 0.82f).coerceIn(MIN_EPISODE, MAX_EPISODE)

        // Grid columns follow the same division, so Browse and My List show the
        // same card size as the home rows instead of a different one.
        val columns = ((available + gap) / (poster + gap)).roundToInt().coerceIn(4, 9)

        return CardMetrics(
            posterWidth = poster,
            landscapeWidth = landscape,
            episodeWidth = episode,
            gap = gap,
            gridColumns = columns,
            contentStart = contentStart,
            visiblePosters = posters
        )
    }
}

/**
 * The metrics for the current window.
 *
 * `LocalConfiguration.screenWidthDp` is already in dp, so this needs no density
 * conversion and stays correct when the viewer changes display size in system
 * settings. `staticCompositionLocalOf` because the value only changes on a
 * configuration change, which recomposes the whole tree anyway.
 */
val LocalCardMetrics = staticCompositionLocalOf { NovaSizing.metrics(960.dp) }

/** Shorthand: `NovaCards.posterWidth` instead of `LocalCardMetrics.current.posterWidth`. */
object NovaCards {
    val posterWidth: Dp
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.posterWidth

    val landscapeWidth: Dp
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.landscapeWidth

    val episodeWidth: Dp
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.episodeWidth

    val gap: Dp
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.gap

    val gridColumns: Int
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.gridColumns

    val contentStart: Dp
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.contentStart

    val visiblePosters: Int
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current.visiblePosters

    /** All metrics at once, for a composable that needs several values. */
    val metrics: CardMetrics
        @Composable @ReadOnlyComposable get() = LocalCardMetrics.current
}

/** The window width in dp, as Compose sees it. */
@Composable
@ReadOnlyComposable
fun screenWidthDp(): Dp = LocalConfiguration.current.screenWidthDp.dp
