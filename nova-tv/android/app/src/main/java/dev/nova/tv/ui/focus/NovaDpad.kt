// Key-repeat gating follows ARVIO (https://github.com/ProdigyV21/ARVIO, commit
// 31ce7d7, Apache License 2.0): HomeScreen.kt / DetailsScreen.kt /
// SettingsScreen.kt gate held D-pad keys at 80 ms horizontally and 112 ms
// vertically. See THIRD_PARTY_NOTICES.md.
package dev.nova.tv.ui.focus

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** ARVIO's intervals for held D-pad keys. */
const val DPAD_REPEAT_HORIZONTAL_MS = 80L
const val DPAD_REPEAT_VERTICAL_MS = 112L

/**
 * Drop held-key repeats that arrive faster than focus can settle. Without it a
 * held Right on a remote that repeats every 30 ms outruns LazyRow composition:
 * focus lands on a card that is not laid out yet and jumps to another row.
 * First presses always pass, so tapping stays instant.
 */
@Composable
fun Modifier.dpadRepeatGate(): Modifier {
    val gate = rememberArvioDpadRepeatGate(
        horizontalMinRepeatIntervalMs = DPAD_REPEAT_HORIZONTAL_MS,
        verticalMinRepeatIntervalMs = DPAD_REPEAT_VERTICAL_MS
    )
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !isArvioDpadNavigationKey(event.key)) return@onPreviewKeyEvent false
        gate.shouldSkip(
            keyCode = event.nativeKeyEvent.keyCode,
            repeatCount = event.nativeKeyEvent.repeatCount,
            nowMs = SystemClock.elapsedRealtime()
        )
    }
}

/**
 * Stop focus leaving a row sideways at its last card. Without it Right on the
 * last loaded poster (while the next page is still loading) moves focus to
 * whichever card in a neighbouring row happens to be nearest.
 */
fun Modifier.holdRowEnd(isLast: Boolean): Modifier =
    if (!isLast) this else onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
    }
