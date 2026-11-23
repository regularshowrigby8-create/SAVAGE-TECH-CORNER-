package dev.nova.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nova.tv.ui.focus.arvioDpadFocusGroup
import dev.nova.tv.ui.theme.LocalReduceMotion
import dev.nova.tv.ui.theme.NovaPalette
import dev.nova.tv.ui.theme.NovaShape
import dev.nova.tv.ui.theme.NovaType

/** The two on-screen keyboards: titles, and website addresses. */
enum class KeyboardLayout(val rows: List<String>) {
    SEARCH(listOf("ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ12", "3456789", "0")),
    URL(listOf("abcdefghij", "klmnopqrst", "uvwxyz.-/:", "1234567890"))
}

/**
 * A D-pad keyboard. Typing on a remote is slow, so keys are large, wrap at
 * the edges of the focus group, and the special keys sit on the last row
 * where the thumb ends up.
 */
@Composable
fun OnScreenKeyboard(
    layout: KeyboardLayout,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
    extra: List<Pair<String, () -> Unit>> = emptyList()
) {
    val keyWidth = if (layout == KeyboardLayout.SEARCH) 52 else 46
    Column(modifier.arvioDpadFocusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        layout.rows.forEachIndexed { r, letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                letters.forEachIndexed { c, ch ->
                    val mod = if (r == 0 && c == 0 && firstKey != null) Modifier.focusRequester(firstKey) else Modifier
                    Key(ch.toString(), keyWidth, mod) { onKey(ch) }
                }
                if (r == layout.rows.lastIndex) {
                    if (layout == KeyboardLayout.SEARCH) Key("Space", keyWidth * 2 + 8) { onKey(' ') }
                    Key("Delete", keyWidth * 2 + 8) { onBackspace() }
                    Key("Clear", keyWidth * 2 + 8) { onClear() }
                    extra.forEach { (label, action) -> Key(label, keyWidth * 2 + 8) { action() } }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(label: String, width: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(width.dp).height(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NovaShape.card)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NovaPalette.surface,
            contentColor = NovaPalette.textPrimary,
            focusedContainerColor = NovaPalette.textPrimary,
            focusedContentColor = NovaPalette.background
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.1f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = if (label.length > 1) NovaType.meta.copy(color = androidx.compose.ui.graphics.Color.Unspecified) else NovaType.body)
        }
    }
}
