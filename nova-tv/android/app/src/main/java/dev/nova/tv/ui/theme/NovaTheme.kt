package dev.nova.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Background = Color(0xFF0B0E11)
private val Surface = Color(0xFF161A1F)
private val Primary = Color(0xFF5B8DEF)
private val OnBackground = Color(0xFFE8ECF1)
private val Muted = Color(0xFF9AA6B2)

private val NovaColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted
)

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    // TV is always dark in practice; honouring light mode would look broken on a
    // living-room screen, so the dark scheme is fixed.
    MaterialTheme(colorScheme = NovaColors, content = content)
}
