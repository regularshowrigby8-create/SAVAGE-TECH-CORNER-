// Shimmer animation after ARVIO's ShimmerState / SkeletonBox
// (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7,
// app/src/main/kotlin/com/arflix/tv/ui/components/SkeletonLoader.kt),
// Apache License 2.0. Changes for Nova: Nova palette, reduce-motion support,
// and only the poster/landscape/hero shapes Nova draws.
package dev.nova.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape

/** One shared sweep for every placeholder on screen (ARVIO ShimmerState). */
@Composable
fun shimmerBrush(): Brush {
    if (LocalReduceMotion.current) return SolidColor(NovaPalette.surface)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    return Brush.linearGradient(
        colors = listOf(NovaPalette.surface, NovaPalette.surfaceHigh, NovaPalette.surface),
        start = Offset(x - 600f, 0f),
        end = Offset(x, 0f)
    )
}

@Composable
fun ShimmerBox(modifier: Modifier, radius: Dp = NovaShape.card) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(shimmerBrush()))
}

/** A row of poster placeholders in the exact size of the real cards. */
@Composable
fun ShimmerPosterRow(count: Int = 7, cardWidth: Dp = PosterWidth, modifier: Modifier = Modifier) {
    Column(modifier) {
        ShimmerBox(Modifier.width(180.dp).height(22.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
            repeat(count) { ShimmerBox(Modifier.width(cardWidth).aspectRatio(2f / 3f)) }
        }
    }
}

@Composable
fun ShimmerHero(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ShimmerBox(Modifier.width(420.dp).height(44.dp))
        ShimmerBox(Modifier.width(260.dp).height(20.dp))
        ShimmerBox(Modifier.fillMaxWidth(0.42f).height(56.dp))
        Spacer(Modifier.height(4.dp))
        ShimmerBox(Modifier.width(150.dp).height(52.dp), radius = NovaShape.pill)
    }
}
