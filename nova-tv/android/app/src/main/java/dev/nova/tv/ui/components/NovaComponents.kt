package dev.nova.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
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
// Poster
// ---------------------------------------------------------------------------

/**
 * The most repeated element in the app, so its five states are defined once.
 *
 * Artwork is not loaded yet (Coil is not a dependency). Rather than show a grey
 * box that promises a picture and never delivers, the placeholder is a
 * deliberate typographic tile: initials over a surface gradient, which reads as
 * intentional at three metres and does not look like a failed request.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 168
) {
    var focused by remember { mutableStateOf(false) }
    val reduced = LocalReduceMotion.current

    Card(
        onClick = onClick,
        modifier = modifier
            .width(width.dp)
            .graphicsLayer {
                val target = if (focused && !reduced) 1.04f else 1f
                scaleX = target
                scaleY = target
            }
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(CardShape),
        colors = CardDefaults.colors(containerColor = Color.Transparent)
    ) {
        Column(
            Modifier.border(
                width = if (focused) 2.dp else 0.dp,
                color = NovaPalette.accent,
                shape = CardShape
            )
        ) {
            PosterArt(title = title, width = width)
            Spacer(Modifier.height(10.dp))
            Text(
                text = title.name,
                style = NovaType.cardTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            SecondaryLine(title)
        }
    }
}

/** Initials tile used until real artwork loads. */
@Composable
private fun PosterArt(title: MetaPreview, width: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(CardShape)
            .background(
                Brush.verticalGradient(
                    listOf(NovaPalette.surfaceHigh, NovaPalette.surface)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials(title.name),
            style = NovaType.display,
            color = NovaPalette.accent.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
    }
}

/** One line, never two: year · rating. Anything more is noise at 3m. */
@Composable
private fun SecondaryLine(title: MetaPreview) {
    val parts = buildList {
        title.year?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (title.rating > 0.0) add("%.1f".format(title.rating))
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  "),
        style = NovaType.meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
}

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
        )
    ) {
        Text(
            text = label,
            style = NovaType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp)
        )
    }
}

/** Secondary actions are outlined so they never compete with the primary. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PillShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.surfaceHigh,
            focusedContentColor = NovaPalette.textPrimary
        )
    ) {
        Text(
            text = label,
            style = NovaType.body,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
        )
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
        )
    ) {
        Text(
            text = label,
            style = NovaType.meta,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
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
fun SkeletonRow(cardCount: Int = 6, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NovaShape.card)
    ) {
        repeat(cardCount) {
            Column(Modifier.width(168.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(CardShape)
                        .background(NovaPalette.surface)
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(NovaShape.card))
                        .background(NovaPalette.surface)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(NovaShape.card))
                        .background(NovaPalette.surface.copy(alpha = 0.7f))
                )
            }
        }
    }
}

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
