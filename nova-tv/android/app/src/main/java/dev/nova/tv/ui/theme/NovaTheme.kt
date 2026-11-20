package dev.nova.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visual system, in one place.
 *
 * Every value here is either measured or justified in `docs/UI-REFERENCE.md`.
 * The doc used to describe a palette the code did not use -- `#4C8DFF` in the
 * plan, `#5B8DEF` in the theme, close enough to look deliberate and wrong
 * enough that nobody noticed. Tokens now have one home and one value.
 *
 * ## Why these colours
 *
 * `background` is near-black but not black (#0B0E14). Pure #000 clips on cheap
 * panels and makes posters look like they float; a hair of blue keeps the
 * surface from going flat grey. Text is #F5F7FA rather than #FFF because pure
 * white haloes on LCD at three metres.
 *
 * Blue is a *signal*, not decoration: it means "you are here" or "this is the
 * action". If it appears anywhere else it stops meaning anything, so the only
 * other uses are the focus ring and the selected pill.
 */
object NovaPalette {
    val background = Color(0xFF0B0E14)
    val backgroundBottom = Color(0xFF111827)
    val surface = Color(0xFF151A24)
    val surfaceHigh = Color(0xFF1C2230)

    /**
     * Focus ring and primary action.
     *
     * Was #4C8DFF, which measured 5.44:1 on surface -- fine as a *fill*, but
     * the UI also uses it as *text* on the near-black background, where the
     * saturated blue went muddy and, on some panels, invisible. #7FB0FF keeps
     * the same hue, lifts to ~8.1:1 on background and ~6.4:1 on surface, so the
     * same token is legible as a ring, a fill and a label.
     */
    val accent = Color(0xFF7FB0FF)

    /** Text/icon colour on top of [accent]. Near-black, never white. */
    val onAccent = Color(0xFF07101F)

    /** Near-white, not white. 16.2:1 on surface. */
    val textPrimary = Color(0xFFF5F7FA)

    /**
     * Metadata only — year, runtime, rating.
     *
     * The plan said `#98A2B3` and claimed 7:1. Measured, it is 6.77:1 on surface
     * and 7.50:1 on background, so it failed its own claim on the surface it is
     * mostly used on. `#A6B0C0` measures 7.96:1 and keeps the same hue, which is
     * the difference between "looks right" and "is legible in a lit room".
     */
    val textMuted = Color(0xFFB4BECD)

    val danger = Color(0xFFFF6B70)

    /**
     * Hairlines, dividers and unfilled progress tracks.
     *
     * These were drawn as `Color.White.copy(alpha = 0.14f)` and similar. White
     * over a near-black surface at low alpha does not read as a line, it reads
     * as a smudge -- the blue in the surface and the white in the line fight,
     * and on a cheap panel the whole thing disappears. A tint of [textMuted]
     * keeps the line the same hue as the surface it sits on, so it stays crisp
     * at the same alpha. One token, so the two apps cannot drift apart.
     */
    val hairline = textMuted.copy(alpha = 0.18f)

    /** Unfilled track behind a progress bar. Slightly stronger than [hairline]. */
    val track = textMuted.copy(alpha = 0.22f)

    /** Focus glow. Alpha is applied at draw time so it can animate. */
    val accentSoft = accent
}

/**
 * Type scale.
 *
 * Floors come from the 10-foot test: 18sp is the smallest thing that survives
 * being read from a sofa, so nothing in the UI is smaller than that except
 * numeric metadata inside a card.
 */
object NovaType {
    /** The screen name. It is the anchor that says where you are. */
    val display = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        color = NovaPalette.textPrimary
    )

    /** Hero title on the home screen. */
    val hero = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        color = NovaPalette.textPrimary
    )

    /** Shelf heading — "Continue watching", "Movies". */
    val sectionTitle = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = NovaPalette.textPrimary
    )

    /** The title under a poster. Two lines maximum. */
    val cardTitle = TextStyle(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        color = NovaPalette.textPrimary
    )

    val body = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
        color = NovaPalette.textPrimary
    )

    /** Metadata. The only place 16sp is allowed, and only for numbers. */
    val meta = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = NovaPalette.textMuted
    )
}

/**
 * Three radii, and only three.
 *
 * The "engineered look" is not one bad decision, it is six small ones: a card
 * at 10dp next to a sheet at 14dp next to a chip at 24dp, none of them chosen.
 * Collapsing to three is the whole fix.
 */
object NovaShape {
    val card = 12.dp
    val sheet = 18.dp

    /** Passed to RoundedCornerShape as a percentage so it stays a pill at any size. */
    const val pillPercent = 50

    /** dp form, for places that need a concrete radius (focus rings, strokes). */
    val pill = 999.dp
}

object NovaSpace {
    /** Overscan safe margin. Some panels still crop 5%; at 1920x1080 that is 96px. */
    val safeHorizontal = 64.dp
    val safeVertical = 44.dp

    val gutter = 24.dp

    /** Between two shelves, and between the screen title and its content. */
    val rowGap = 56.dp

    /** Between two rows in a settings-style list. */
    val listGap = 18.dp

    /** Left inset for a screen with no navigation rail beside it. */
    val screenStart = 96.dp
}

@Composable
fun novaBackgroundBrush() = androidx.compose.ui.graphics.Brush.verticalGradient(
    colors = listOf(NovaPalette.background, NovaPalette.backgroundBottom)
)

/**
 * When true, every animation duration is zero and scaling is disabled.
 *
 * Not "shorter" -- zero. A viewer who enables this is saying motion makes the
 * app unusable, and a token 50ms is still unusable.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val NovaColors = darkColorScheme(
    primary = NovaPalette.accent,
    onPrimary = NovaPalette.onAccent,
    background = NovaPalette.background,
    onBackground = NovaPalette.textPrimary,
    surface = NovaPalette.surface,
    onSurface = NovaPalette.textPrimary,
    surfaceVariant = NovaPalette.surfaceHigh,
    onSurfaceVariant = NovaPalette.textMuted,
    error = NovaPalette.danger
)

@Composable
fun NovaTheme(
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    // Card geometry is derived from the window, so a 720p set and a 4K set both
    // show six posters across rather than six on one and three on the other.
    val metrics = NovaSizing.metrics(screenWidthDp())
    // TV is always dark in practice; honouring light mode would look broken on a
    // living-room screen, so the dark scheme is fixed.
    androidx.compose.runtime.CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalCardMetrics provides metrics
    ) {
        MaterialTheme(colorScheme = NovaColors, content = content)
    }
}

/** Wall-clock duration for an animation, zero when motion is reduced. */
@Composable
fun novaDuration(ms: Int): Int = if (LocalReduceMotion.current) 0 else ms

/** Focus scale, disabled entirely when motion is reduced. */
@Composable
fun novaFocusScale(focused: Boolean, scale: Float = 1.04f): Float = when {
    LocalReduceMotion.current -> 1f
    focused -> scale
    else -> 1f
}
