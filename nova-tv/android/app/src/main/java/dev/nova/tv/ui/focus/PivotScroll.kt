package dev.nova.tv.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * Pivot scrolling, the Google TV / ARVIO row behaviour: the focused item is
 * held at a fixed fraction of the viewport instead of the list scrolling only
 * when focus hits the edge. Rows glide and focus stays where the eye is.
 *
 * Formula after the Android TV "Scroll with focus" guide
 * (developer.android.com/training/tv/playback/compose/scroll).
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotSpec(private val parentFraction: Float, private val childFraction: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = parentFraction * containerSize - childFraction * size
        val target = if (size <= containerSize && containerSize - leading < size) containerSize - size else leading
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(parentFraction: Float, childFraction: Float = 0f, content: @Composable () -> Unit) {
    val spec = remember(parentFraction, childFraction) { PivotSpec(parentFraction, childFraction) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
