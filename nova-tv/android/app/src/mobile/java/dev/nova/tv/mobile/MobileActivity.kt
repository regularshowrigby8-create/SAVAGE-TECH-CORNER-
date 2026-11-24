package dev.nova.tv.mobile

import android.app.PictureInPictureParams
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The phone and tablet entry point. Same view model, finders and player as
 * the TV build; only the shell is touch-first.
 *
 * Picture-in-picture: leaving the app while a film plays shrinks it into a
 * floating window instead of stopping it.
 */
class MobileActivity : ComponentActivity() {

    private var inPip by mutableStateOf(false)
    private val pipListener: (Boolean) -> Unit = { onPlaybackChanged(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        MobilePlayback.listener = pipListener
        setContent {
            CompositionLocalProvider(LocalPip provides PipState(inPip, ::enterPip)) {
                MobileApp()
            }
        }
    }

    override fun onDestroy() {
        if (MobilePlayback.listener === pipListener) MobilePlayback.listener = null
        super.onDestroy()
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
            // Android 12+: the system animates straight into PiP on Home.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        }.build()

    private fun onPlaybackChanged(active: Boolean) {
        runCatching { setPictureInPictureParams(pipParams(active)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Older versions have no auto-enter, so enter by hand.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && MobilePlayback.active) enterPip()
    }

    /** Float the player over other apps. Returns false when the device refuses. */
    fun enterPip(): Boolean = runCatching {
        enterPictureInPictureMode(pipParams(MobilePlayback.active))
    }.getOrDefault(false)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }
}

/** Set by the player while a film is on screen; read when the user leaves the app. */
object MobilePlayback {
    /** The activity listens so Android 12+ can auto-enter PiP only while playing. */
    var listener: ((Boolean) -> Unit)? = null

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            listener?.invoke(value)
        }
}

data class PipState(val active: Boolean = false, val enter: () -> Boolean = { false })

val LocalPip = staticCompositionLocalOf { PipState() }
