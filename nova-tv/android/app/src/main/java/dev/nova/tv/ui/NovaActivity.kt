package dev.nova.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.nova.tv.ui.screens.NovaApp

/**
 * The single activity. Compose owns everything above it.
 *
 * The theme is applied inside [NovaApp] rather than here, because the
 * `reduceMotion` setting has to be read before the theme can honour it, and that
 * value lives in the view model. Wrapping the app in a theme here meant the
 * setting existed but could never reach the animations it controls -- one of the
 * write-only properties the audit script reports.
 */
class NovaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaApp()
        }
    }
}
