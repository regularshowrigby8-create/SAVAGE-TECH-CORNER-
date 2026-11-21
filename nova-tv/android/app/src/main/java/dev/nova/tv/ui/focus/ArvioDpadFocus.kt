// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original path:
//   app/src/main/kotlin/com/arflix/tv/ui/focus/ArvioDpadFocus.kt
// Changes for Nova: package name; the unused manual bring-into-view boundary
// (deprecated BringIntoViewResponder API) was dropped. See THIRD_PARTY_NOTICES.md.
package dev.nova.tv.ui.focus

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.arvioDpadFocusGroup(
    enableFocusRestorer: Boolean = true
): Modifier {
    // Compose's focusRestorer(onRestoreFailed = { restoreFocusRequester }) captures the
    // requester at modifier-application time and, on a focus enter whose automatic restore
    // fails, calls requestFocus() ITSELF — deep inside the modifier, without a try/catch.
    // When the restore target lives in a LazyColumn row that is not yet composed/attached
    // (the case right after categories finish loading on a fresh TV entry), that internal
    // requestFocus() throws IllegalStateException: FocusRequester is not initialized and
    // crashes the app, unreachable by the runCatching guards wrapping direct calls.
    //
    // Keep focus restoration limited to Compose's own saved focus history. Callers that
    // need an initial focus target should request it after the target is attached instead
    // of supplying a fallback that Compose can invoke before a lazy item is composed.
    val restorer = if (enableFocusRestorer) Modifier.focusRestorer() else Modifier
    return this.then(restorer).focusGroup()
}

fun isArvioDpadNavigationKey(key: Key): Boolean {
    return key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionUp ||
        key == Key.DirectionDown
}

/**
 * Mirrors a horizontal D-pad key when the layout direction is RTL.
 *
 * In an RTL locale the IPTV screen's visuals are mirrored by Compose, but its
 * navigation code is written against an LTR mental model (Left = toward the
 * sidebar / earlier, Right = deeper / later). Feeding the mirrored key into that
 * code keeps focus movement pointing the same way the eye expects. Vertical and
 * non-directional keys pass through untouched.
 */
fun Key.mirrorHorizontalForRtl(isRtl: Boolean): Key {
    if (!isRtl) return this
    return when (this) {
        Key.DirectionLeft -> Key.DirectionRight
        Key.DirectionRight -> Key.DirectionLeft
        else -> this
    }
}

@Stable
class ArvioDpadRepeatGate(
    private val horizontalMinRepeatIntervalMs: Long,
    private val verticalMinRepeatIntervalMs: Long = horizontalMinRepeatIntervalMs
) {
    private var lastKeyCode: Int = Int.MIN_VALUE
    private var lastHandledAtMs: Long = 0L

    fun shouldSkip(keyCode: Int, repeatCount: Int, nowMs: Long): Boolean {
        if (repeatCount <= 0) {
            lastKeyCode = keyCode
            lastHandledAtMs = nowMs
            return false
        }

        val minRepeatIntervalMs = when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP,
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> verticalMinRepeatIntervalMs
            else -> horizontalMinRepeatIntervalMs
        }
        val skip = keyCode == lastKeyCode && nowMs - lastHandledAtMs < minRepeatIntervalMs
        if (!skip) {
            lastKeyCode = keyCode
            lastHandledAtMs = nowMs
        }
        return skip
    }

    fun reset() {
        lastKeyCode = Int.MIN_VALUE
        lastHandledAtMs = 0L
    }
}

@Composable
fun rememberArvioDpadRepeatGate(
    minRepeatIntervalMs: Long = 82L,
    horizontalMinRepeatIntervalMs: Long = minRepeatIntervalMs,
    verticalMinRepeatIntervalMs: Long = minRepeatIntervalMs
): ArvioDpadRepeatGate {
    return remember(horizontalMinRepeatIntervalMs, verticalMinRepeatIntervalMs) {
        ArvioDpadRepeatGate(
            horizontalMinRepeatIntervalMs = horizontalMinRepeatIntervalMs,
            verticalMinRepeatIntervalMs = verticalMinRepeatIntervalMs
        )
    }
}
