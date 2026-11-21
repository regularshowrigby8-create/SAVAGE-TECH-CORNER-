package dev.nova.tv.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.ui.components.CardGap
import dev.nova.tv.ui.components.ErrorState
import dev.nova.tv.ui.components.LandscapeCard
import dev.nova.tv.ui.components.PosterCard
import dev.nova.tv.ui.components.RegisterContentEntry
import dev.nova.tv.ui.components.ShimmerHero
import dev.nova.tv.ui.components.ShimmerPosterRow
import dev.nova.tv.ui.focus.PivotScroll
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.focus.holdRowEnd
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaSpace
import dev.nova.tv.ui.theme.NovaType
import kotlinx.coroutines.delay

/**
 * Home: a spotlight that follows focus over endless rows.
 *
 * The backdrop and title at the top always describe the focused card, so the
 * viewer never needs a caption under a poster. Before anything is focused it
 * rotates through the featured titles. Rows load as the viewer scrolls down,
 * and every row pages in more titles as focus nears its end.
 */
@Composable
fun HomeScreen(state: NovaState, viewModel: NovaViewModel) = SpotlightShelves(
    shelves = state.shelves,
    hero = state.hero,
    loading = state.homeLoading,
    error = state.homeError,
    viewModel = viewModel,
    onRetry = viewModel::refresh,
    onMoreShelves = viewModel::loadMoreShelves
)

/**
 * Dual Audio: the same spotlight and endless rows, filled with titles made
 * outside the English-speaking world. Their sources rank English-plus-original
 * releases first, and the player starts on the English track.
 */
@Composable
fun DualAudioScreen(state: NovaState, viewModel: NovaViewModel) = SpotlightShelves(
    shelves = state.dualShelves,
    hero = state.dualShelves.firstOrNull()?.items.orEmpty().filter { !it.background.isNullOrBlank() }.take(6),
    loading = state.dualLoading || (state.dualShelves.isEmpty() && state.homeLoading),
    error = null,
    viewModel = viewModel,
    onRetry = viewModel::loadDualAudio,
    onMoreShelves = viewModel::loadMoreDualShelves
)

@Composable
private fun SpotlightShelves(
    shelves: List<Shelf>,
    hero: List<MetaPreview>,
    loading: Boolean,
    error: String?,
    viewModel: NovaViewModel,
    onRetry: () -> Unit,
    onMoreShelves: () -> Unit
) {
    var focused by remember { mutableStateOf<MetaPreview?>(null) }
    var spotlight by remember { mutableStateOf<MetaPreview?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    val reduced = LocalReduceMotion.current

    // Settle before swapping artwork, so a fast scroll does not flash images.
    LaunchedEffect(focused) {
        if (focused != null) {
            delay(SPOTLIGHT_SETTLE_MS)
            spotlight = focused
        }
    }
    // Nothing focused yet: rotate the featured titles.
    LaunchedEffect(hero, focused == null) {
        if (focused != null || hero.isEmpty()) return@LaunchedEffect
        while (true) {
            spotlight = hero[rotation % hero.size]
            if (reduced) break
            delay(HERO_ROTATE_MS)
            rotation++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(spotlight)

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .padding(start = ContentStart, top = NovaSpace.safeVertical + 40.dp, end = NovaSpace.safeHorizontal)
                    .height(SPOTLIGHT_HEIGHT)
            ) {
                if (loading && spotlight == null) ShimmerHero() else Spotlight(spotlight)
            }

            when {
                loading && shelves.isEmpty() -> Column(
                    Modifier.padding(start = ContentStart, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(36.dp)
                ) {
                    repeat(2) { ShimmerPosterRow() }
                }
                shelves.isEmpty() -> ErrorState(
                    title = "Can't load right now",
                    detail = error ?: "Check the connection and try again.",
                    onRetry = onRetry
                )
                else -> Shelves(shelves, viewModel, onMoreShelves, onFocused = { focused = it })
            }
        }
    }
}

@Composable
private fun Shelves(shelves: List<Shelf>, viewModel: NovaViewModel, onMoreShelves: () -> Unit, onFocused: (MetaPreview) -> Unit) {
    val first = remember { FocusRequester() }
    RegisterContentEntry(first)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    PivotScroll(parentFraction = 0f) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(NovaSpace.rowGap)
        ) {
            itemsIndexed(shelves, key = { _, s -> s.key }) { index, shelf ->
                if (index >= shelves.size - 3) LaunchedEffect(shelves.size) { onMoreShelves() }
                ShelfRow(
                    shelf = shelf,
                    firstFocus = if (index == 0) first else null,
                    onOpen = viewModel::openTitle,
                    onFocused = onFocused,
                    onNearEnd = { viewModel.loadMoreInShelf(shelf.key) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
internal fun ShelfRow(
    shelf: Shelf,
    firstFocus: FocusRequester?,
    onOpen: (MetaPreview) -> Unit,
    onFocused: (MetaPreview) -> Unit,
    onNearEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            shelf.title,
            style = NovaType.sectionTitle,
            modifier = Modifier.padding(start = ContentStart, bottom = 18.dp)
        )
        PivotScroll(parentFraction = 0.05f) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().arvioDpadFocusGroup(),
                contentPadding = PaddingValues(start = ContentStart, end = 220.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(CardGap)
            ) {
                itemsIndexed(shelf.items, key = { _, m -> m.id }) { i, meta ->
                    if (i >= shelf.items.size - PAGE_AHEAD) LaunchedEffect(shelf.items.size) { onNearEnd() }
                    val mod = (if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .holdRowEnd(isLast = i == shelf.items.lastIndex && !shelf.exhausted)
                    when (shelf.style) {
                        ShelfStyle.POSTER -> PosterCard(meta, onClick = { onOpen(meta) }, modifier = mod, onFocused = { onFocused(meta) })
                        ShelfStyle.LANDSCAPE -> LandscapeCard(
                            meta, progress = shelf.progress[meta.id] ?: 0f,
                            onClick = { onOpen(meta) }, modifier = mod, onFocused = { onFocused(meta) }
                        )
                    }
                }
            }
        }
    }
}

/** Full-bleed artwork for the spotlight, faded into the page on two sides. */
@Composable
private fun Backdrop(meta: MetaPreview?) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        Crossfade(targetState = meta?.background ?: meta?.poster, animationSpec = tween(600), label = "backdrop") { url ->
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to NovaPalette.background,
                    0.35f to NovaPalette.background.copy(alpha = 0.85f),
                    0.7f to Color.Transparent
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.3f to Color.Transparent, 0.62f to NovaPalette.background.copy(alpha = 0.9f), 0.8f to NovaPalette.background)
            )
        )
    }
}

@Composable
private fun Spotlight(meta: MetaPreview?) {
    AnimatedContent(
        targetState = meta,
        transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(220)) },
        contentKey = { it?.id },
        label = "spotlight"
    ) { m ->
        if (m == null) return@AnimatedContent
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitleArt(m)
            if (m.shortInfo.isNotBlank()) Text(m.shortInfo, style = NovaType.meta, color = NovaPalette.textPrimary.copy(alpha = 0.8f))
            if (m.description.isNotBlank()) {
                Text(m.description, style = NovaType.meta, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The clear logo when there is one; the name in the hero style otherwise. */
@Composable
internal fun TitleArt(meta: MetaPreview, maxHeight: androidx.compose.ui.unit.Dp = 96.dp) {
    var logoFailed by remember(meta.id) { mutableStateOf(meta.logo.isNullOrBlank()) }
    if (!logoFailed) {
        AsyncImage(
            model = meta.logo,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = Modifier.heightIn(max = maxHeight).widthIn(max = 420.dp)
        )
    } else {
        Text(meta.name, style = NovaType.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(2.dp))
}

private val SPOTLIGHT_HEIGHT = 250.dp
private const val SPOTLIGHT_SETTLE_MS = 220L
private const val HERO_ROTATE_MS = 8_000L
private const val PAGE_AHEAD = 8
