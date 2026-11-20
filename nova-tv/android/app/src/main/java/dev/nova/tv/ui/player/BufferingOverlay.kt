package dev.nova.tv.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nova.tv.player.BufferingStatus
import dev.nova.tv.ui.theme.NovaPalette
import kotlin.math.roundToInt

/**
 * The one buffering indicator, shared by the remote and the touch player.
 *
 * A spinner says "something is happening"; a counted ring says how far along
 * it is, which is the difference between waiting and wondering. The arc fills
 * as the buffer or the cold-start ramp advances, and the label names the
 * reason when there is one ("Switching to a faster source").
 */
@Composable
fun BufferingOverlay(
    status: BufferingStatus,
    modifier: Modifier = Modifier,
    ringSize: Dp = 104.dp
) {
    val target = (status.percent / 100f).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 360, easing = LinearEasing),
        label = "buffer-percent"
    )

    Column(
        modifier
            .background(NovaPalette.background.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(ringSize)) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = NovaPalette.textPrimary.copy(alpha = 0.18f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                if (progress > 0f) {
                    drawArc(
                        color = NovaPalette.accent,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "${(progress * 100).roundToInt()}%",
                color = NovaPalette.textPrimary,
                fontSize = (ringSize.value * 0.24f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = status.label,
            color = NovaPalette.textPrimary.copy(alpha = 0.85f),
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
