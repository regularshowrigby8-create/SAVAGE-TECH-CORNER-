package dev.nova.tv.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette

/**
 * Same palette as the TV build, phone-sized type. The TV scale starts at
 * 18 sp for a sofa three metres away; a phone is read at arm's length.
 */
object MType {
    val hero = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = NovaPalette.textPrimary, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = NovaPalette.textPrimary)
    val section = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = NovaPalette.textPrimary)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = NovaPalette.textPrimary)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = NovaPalette.textMuted)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = NovaPalette.textPrimary)
    val wordmark = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = NovaPalette.accent)
}

private val Scheme = darkColorScheme(
    primary = NovaPalette.accent,
    // White on #4C8DFF is ~2.6:1. Dark ink on the accent is ~7:1.
    onPrimary = NovaPalette.background,
    primaryContainer = NovaPalette.accent.copy(alpha = 0.24f),
    onPrimaryContainer = NovaPalette.textPrimary,
    secondaryContainer = NovaPalette.accent.copy(alpha = 0.22f),
    onSecondaryContainer = NovaPalette.textPrimary,
    background = NovaPalette.background,
    onBackground = NovaPalette.textPrimary,
    surface = NovaPalette.background,
    onSurface = NovaPalette.textPrimary,
    surfaceVariant = NovaPalette.surfaceHigh,
    onSurfaceVariant = NovaPalette.textMuted,
    surfaceContainerLowest = NovaPalette.background,
    surfaceContainerLow = NovaPalette.surface,
    surfaceContainer = NovaPalette.surface,
    surfaceContainerHigh = NovaPalette.surfaceHigh,
    surfaceContainerHighest = NovaPalette.surfaceHigh,
    outline = NovaPalette.textMuted.copy(alpha = 0.4f),
    outlineVariant = Color.White.copy(alpha = 0.08f),
    error = NovaPalette.danger
)

@Composable
fun MobileTheme(reduceMotion: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion, content = content)
    }
}
