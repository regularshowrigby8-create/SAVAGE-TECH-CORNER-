package dev.nova.tv.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.ShimmerBox
import dev.nova.tv.ui.screens.Shelf
import dev.nova.tv.ui.screens.ShelfStyle
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import kotlinx.coroutines.flow.distinctUntilChanged

internal val PosterWidth = 112.dp
internal val LandscapeWidth = 228.dp
internal val ScreenPadding = 16.dp
private val CardShape = RoundedCornerShape(10.dp)

/** Touch feedback: cards sink a little under the finger. */
@Composable
internal fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) 0.95f else 1f, spring(stiffness = 900f), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
internal fun PosterCard(meta: MetaPreview, onClick: () -> Unit, modifier: Modifier = Modifier.width(PosterWidth)) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(CardShape)
            .background(NovaPalette.surface)
            .pressable(onClick)
    ) {
        if (!meta.poster.isNullOrBlank()) {
            AsyncImage(
                model = meta.poster,
                contentDescription = meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // No art: the name is the only way to tell titles apart.
            Text(
                meta.name,
                style = MType.label,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp)
            )
        }
    }
}

@Composable
internal fun LandscapeCard(meta: MetaPreview, progress: Float, onClick: () -> Unit) {
    Column(Modifier.width(LandscapeWidth).pressable(onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .background(NovaPalette.surface)
        ) {
            val art = meta.background ?: meta.poster
            if (!art.isNullOrBlank()) {
                AsyncImage(model = art, contentDescription = meta.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(NovaPalette.accent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(meta.name, style = MType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One endless row. Pages in more when the last few cards come into view. */
@Composable
internal fun ShelfRow(shelf: Shelf, onOpen: (MetaPreview) -> Unit, onNearEnd: () -> Unit) {
    val rowState = rememberLazyListState()
    val landscape = shelf.style == ShelfStyle.LANDSCAPE
    LaunchedEffect(shelf.key, shelf.items.size, shelf.exhausted) {
        snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (!shelf.exhausted && shelf.items.isNotEmpty() && last >= shelf.items.size - 5) onNearEnd() }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(shelf.title, style = MType.section, modifier = Modifier.padding(horizontal = ScreenPadding))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shelf.items, key = { "${shelf.key}:${it.id}" }) { meta ->
                if (landscape) LandscapeCard(meta, shelf.progress[meta.id] ?: 0f) { onOpen(meta) }
                else PosterCard(meta, onClick = { onOpen(meta) })
            }
            if (shelf.loading || (shelf.items.isEmpty() && !shelf.exhausted)) {
                items(if (shelf.items.isEmpty()) 5 else 1) {
                    ShimmerBox(
                        if (landscape) Modifier.width(LandscapeWidth).aspectRatio(16f / 9f)
                        else Modifier.width(PosterWidth).aspectRatio(2f / 3f),
                        radius = 10.dp
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShimmerShelf(landscape: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        ShimmerBox(Modifier.padding(horizontal = ScreenPadding).width(140.dp).height(18.dp), radius = 6.dp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(5) {
                ShimmerBox(
                    if (landscape) Modifier.width(LandscapeWidth).aspectRatio(16f / 9f) else Modifier.width(PosterWidth).aspectRatio(2f / 3f),
                    radius = 10.dp
                )
            }
        }
    }
}

/** An endless poster grid, three or more columns depending on width. */
@Composable
internal fun PosterGrid(
    items: List<MetaPreview>,
    loading: Boolean,
    exhausted: Boolean,
    onOpen: (MetaPreview) -> Unit,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null
) {
    LaunchedEffect(items.size, exhausted) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (!exhausted && !loading && items.isNotEmpty() && last >= items.size - 9) onNearEnd() }
    }
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        if (header != null) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        }
        items(items, key = { it.id }) { meta ->
            PosterCard(meta, onClick = { onOpen(meta) }, modifier = Modifier.fillMaxWidth())
        }
        if (loading) {
            items(if (items.isEmpty()) 12 else 3) {
                ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f), radius = 10.dp)
            }
        }
    }
}

/** A screen title, with a back arrow for pages reached from More. */
@Composable
internal fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = if (onBack != null) 4.dp else ScreenPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Text(title, style = MType.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
internal fun Badge(text: String, accent: Boolean = false) {
    Text(
        text,
        style = MType.label,
        modifier = Modifier
            .background(if (accent) NovaPalette.accent.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MType.meta)
    }
}
