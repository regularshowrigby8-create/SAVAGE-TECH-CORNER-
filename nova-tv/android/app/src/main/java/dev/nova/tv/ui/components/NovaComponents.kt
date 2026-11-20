package dev.nova.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaType

/**
 * Shared UI parts.
 *
 * Two rules hold for everything in this file:
 *
 *  1. Nothing is decorative. If a thing looks pressable it is pressable, and if
 *     it cannot be pressed it does not look like it can.
 *  2. Every focusable element has a focused state. On a TV with no cursor, an
 *     element that does not show focus is an element the viewer cannot use.
 */

private val CardShape = RoundedCornerShape(NovaShape.card)
private val SheetShape = RoundedCornerShape(NovaShape.sheet)
private val PillShape = RoundedCornerShape(percent = NovaShape.pillPercent)

// ---------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------

/** The focus ring every focusable element uses: 3dp, high contrast, never colour alone. */
val FocusRingWidth = 3.dp

/**
 * Which card should take focus back when an overlay closes.
 *
 * Without this, closing the detail overlay removed the focused node and left
 * focus nowhere; the next D-pad press landed on whatever Compose found first,
 * usually the rail, and the viewer lost their place in the row.
 */
data class FocusRestore(val id: String?, val consume: () -> Unit)

val LocalFocusRestore = staticCompositionLocalOf { FocusRestore(null) {} }

/**
 * Bridges the shell's rail to whichever screen is showing.
 *
 * The rail sits beside the content, so pressing Right on it used to rely on
 * geometric search: focus went to whichever row happened to sit at the same
 * height as the icon, which on Settings meant landing on "DNS" instead of the
 * first row. The screen registers its first control and the rail hands focus
 * straight to it.
 */
class ContentEntry {
    private var owner: Any? = null
    private var request: (() -> Boolean)? = null

    fun register(owner: Any, request: () -> Boolean) {
        this.owner = owner
        this.request = request
    }

    fun unregister(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            this.request = null
        }
    }

    /** True when something on screen took focus. */
    fun focusFirst(): Boolean = request?.invoke() == true
}

val LocalContentEntry = staticCompositionLocalOf { ContentEntry() }

/** Register [requester] as "the first thing on this screen" for the rail. */
@Composable
fun RegisterContentEntry(requester: FocusRequester) {
    val entry = LocalContentEntry.current
    DisposableEffect(entry, requester) {
        entry.register(requester) { runCatching { requester.requestFocus() }.isSuccess }
        onDispose { entry.unregister(requester) }
    }
}

/**
 * One requester per row of a vertical list, for [focusColumnRow].
 */
@Composable
fun rememberFocusColumn(count: Int): List<FocusRequester> = remember(count) {
    List(count) { FocusRequester() }
}

/**
 * Deterministic Up/Down movement inside a settings-style list.
 *
 * Geometric D-pad search treats every focusable on screen as a candidate, so
 * with rows of different heights and the rail directly to the left, one press
 * could skip a row or leave the list sideways. Wiring the neighbours by index
 * makes each press move exactly one row; Right is cancelled because there is
 * nothing to the right of a full-width row.
 */
fun Modifier.focusColumnRow(index: Int, order: List<FocusRequester>): Modifier {
    if (index !in order.indices) return this
    return this
        .focusRequester(order[index])
        .focusProperties {
            up = order.getOrNull(index - 1) ?: FocusRequester.Cancel
            down = order.getOrNull(index + 1) ?: FocusRequester.Cancel
            right = FocusRequester.Cancel
        }
}

/** Request focus on [requester] when [id] is the one being restored. */
@Composable
fun RestoreFocusFor(id: String, requester: FocusRequester) {
    val restore = LocalFocusRestore.current
    LaunchedEffect(restore.id) {
        if (restore.id != null && restore.id == id) {
            // One frame so the node is attached after the overlay leaves.
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) restore.consume()
        }
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** Poster size used by every row, so rows and placeholders line up exactly. */
val PosterWidth = 184.dp
val LandscapeWidth = 360.dp
val CardGap = 28.dp

/**
 * Poster only, no caption: the artwork carries the title. A caption under
 * every poster doubled the visual noise of a row and made scrolling feel like
 * reading a list. The name appears only when there is no artwork to show.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 0 fills the parent (grid cells). */
    width: Dp = PosterWidth,
    onFocused: () -> Unit = {}
) {
    val requester = remember { FocusRequester() }
    RestoreFocusFor(title.id, requester)
    NovaCard(
        onClick = onClick,
        modifier = (if (width > 0.dp) modifier.width(width) else modifier.fillMaxWidth())
            .aspectRatio(2f / 3f)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Artwork(url = title.poster, fallback = title.name)
    }
}

/** Continue Watching: a wide still with a thin progress bar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LandscapeCard(
    title: MetaPreview,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LandscapeWidth,
    onFocused: () -> Unit = {}
) {
    val requester = remember { FocusRequester() }
    RestoreFocusFor(title.id, requester)
    NovaCard(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
    ) {
        Box(Modifier.fillMaxSize()) {
            Artwork(url = title.background ?: title.poster, fallback = title.name)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
            )
            Text(
                text = title.name,
                style = NovaType.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
            )
            ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** An episode: still, number, name, progress if started. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodeCard(
    number: Int,
    name: String,
    thumbnail: String?,
    selected: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.width(260.dp)) {
        NovaCard(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize()) {
                Artwork(url = thumbnail, fallback = "$number")
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(PillShape)
                            .background(NovaPalette.accent)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) { Text("Up next", style = NovaType.meta, color = NovaPalette.background) }
                }
                if (progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$number. $name",
            style = NovaType.meta,
            color = if (selected) NovaPalette.textPrimary else NovaPalette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(NovaPalette.accent))
    }
}

/** The one card treatment: spring scale, 3dp ring, soft glow. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NovaCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReduceMotion.current
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(CardShape),
        colors = CardDefaults.colors(containerColor = NovaPalette.surface, focusedContainerColor = NovaPalette.surfaceHigh),
        scale = CardDefaults.scale(focusedScale = if (reduced) 1f else 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(FocusRingWidth, NovaPalette.textPrimary), shape = CardShape)
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = NovaPalette.accent.copy(alpha = 0.35f), elevation = 16.dp))
    ) { content() }
}

/** Artwork over a shimmer; the name shows only when there is no image. */
@Composable
fun Artwork(url: String?, fallback: String, modifier: Modifier = Modifier) {
    var state by remember(url) { mutableStateOf(if (url.isNullOrBlank()) ArtState.FAILED else ArtState.LOADING) }
    Box(modifier.fillMaxSize().clip(CardShape), contentAlignment = Alignment.Center) {
        when (state) {
            ArtState.LOADING -> Box(Modifier.fillMaxSize().background(shimmerBrush()))
            ArtState.FAILED -> Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NovaPalette.surfaceHigh, NovaPalette.surface))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallback,
                    style = NovaType.cardTitle,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
            ArtState.READY -> Unit
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = fallback,
                contentScale = ContentScale.Crop,
                onSuccess = { state = ArtState.READY },
                onError = { state = ArtState.FAILED },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private enum class ArtState { LOADING, READY, FAILED }

/** "The Bear" -> "TB"; "Dune" -> "D". Two letters maximum so it never wraps. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * One primary action per surface. Filled, because a primary action that is not
 * visually dominant is not a primary action.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PillShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = NovaPalette.accent,
            contentColor = NovaPalette.background,
            focusedContainerColor = NovaPalette.textPrimary,
            focusedContentColor = NovaPalette.background
        ),
        scale = focusScale(),
        border = focusBorder(PillShape)
    ) {
        ButtonContent(label, icon, NovaType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

/** Secondary actions are outlined so they never compete with the primary. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** A switched-on toggle (Preload): tinted fill and accent outline. */
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PillShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (active) NovaPalette.accent.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (active) NovaPalette.accent else NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        ),
        scale = focusScale(),
        // Outlined at rest so it reads as a button; the ring thickens on focus.
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(1.dp, if (active) NovaPalette.accent else NovaPalette.textMuted.copy(alpha = 0.5f)),
                shape = PillShape
            ),
            focusedBorder = Border(BorderStroke(FocusRingWidth, NovaPalette.accent), shape = PillShape)
        )
    ) {
        ButtonContent(label, icon, NovaType.body)
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?, style: androidx.compose.ui.text.TextStyle) {
    Row(
        Modifier.padding(start = if (icon != null) 24.dp else 32.dp, end = 32.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            androidx.tv.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text = label, style = style, maxLines = 1)
    }
}

/** A filter chip. Selected is a state, not a decoration: it reflects a real filter. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PillShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NovaPalette.accent.copy(alpha = 0.18f) else NovaPalette.surface,
            contentColor = if (selected) NovaPalette.accent else NovaPalette.textMuted,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        ),
        scale = focusScale(),
        border = focusBorder(PillShape)
    ) {
        Text(
            text = label,
            style = NovaType.meta,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun focusScale() = androidx.tv.material3.ClickableSurfaceDefaults.scale(
    focusedScale = if (LocalReduceMotion.current) 1f else 1.05f
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun focusBorder(shape: androidx.compose.ui.graphics.Shape) = androidx.tv.material3.ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(FocusRingWidth, NovaPalette.accent), shape = shape)
)

/**
 * A whole row as one focus target.
 *
 * Settings and Addons rows used to be plain Rows with a small button at the
 * far right, so focus jumped between tiny pills and the row itself never
 * showed where the viewer was. The row is now the target, and [trailing]
 * shows the value or action it triggers.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(CardShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = NovaPalette.surface,
            contentColor = NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = if (LocalReduceMotion.current) 1f else 1.02f
        ),
        border = focusBorder(CardShape)
    ) {
        Row(
            Modifier.padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.weight(1f)) { content() }
            trailing()
        }
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

/**
 * Loading, drawn in the shape of the content that is coming.
 *
 * A centred spinner admits the layout was not planned, and content that jumps
 * when it arrives reads as a bug at three metres.
 */
@Composable
fun SkeletonRow(cardCount: Int = 7, modifier: Modifier = Modifier) = ShimmerPosterRow(cardCount, modifier = modifier)

/**
 * The empty state.
 *
 * Three parts, always: what is missing, why, and one thing to do about it. A
 * blank grid tells the viewer the app is broken; this tells them the truth.
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = NovaType.sectionTitle)
            Text(
                message,
                style = NovaType.meta,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(520.dp)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/**
 * The error state.
 *
 * It names the source that failed, because "something went wrong" costs the
 * viewer a support round-trip and a named source costs them nothing.
 */
@Composable
fun ErrorState(
    title: String,
    detail: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = NovaType.sectionTitle, color = NovaPalette.danger)
            Text(
                detail,
                style = NovaType.meta,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(560.dp)
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Try again", onClick = onRetry)
        }
    }
}

/** A small legend dot: filled = answered, hollow = nothing, so the viewer is never lied to. */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(8.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (active) NovaPalette.accent else NovaPalette.surfaceHigh)
    )
}

/** Section heading used above every shelf row. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = NovaType.sectionTitle,
        modifier = modifier.padding(bottom = 12.dp)
    )
}
