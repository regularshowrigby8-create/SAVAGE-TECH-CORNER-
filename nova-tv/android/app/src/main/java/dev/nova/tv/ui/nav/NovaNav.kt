package dev.nova.tv.ui.nav

/**
 * One navigation engine for the whole app.
 *
 * Before this file Nova had two BACK handlers that disagreed: the shell's
 * `BackHandler` called `viewModel.back()`, which stopped playback on the first
 * press, and `PlayerSurface` intercepted KEYCODE_BACK in `onPreviewKeyEvent`
 * with its own two-press rule and its own `chromeVisible` flag. Which one won
 * depended on focus, so BACK sometimes ended a film instantly and sometimes
 * needed two presses, and an unused `leaveArmed` flag recorded the intent of a
 * third design.
 *
 * Now every surface is a layer on one stack, BACK is resolved here from a
 * snapshot of that stack, and the shell's BackHandler is the only key path
 * that acts on it. The function is pure so the order is unit-tested
 * (`NovaNavTest`), not just described.
 */
enum class Layer {
    /** Rail destination (Home, Movies, ...). Always at the bottom. */
    ROUTE,
    /** A title's detail overlay. */
    DETAIL,
    /** Fullscreen playback. */
    PLAYER,
    /** Sources / Audio / Subtitles panel over the player. */
    PLAYER_PANEL,
    /** A sheet over a screen: the detail's source list or the add-website keyboard. */
    SHEET
}

enum class BackAction {
    CLOSE_PLAYER_PANEL,
    /** First BACK while watching only reveals the controls. */
    SHOW_PLAYER_CHROME,
    STOP_PLAYBACK,
    CLOSE_SHEET,
    CLOSE_DETAIL,
    CLEAR_QUERY,
    GO_HOME,
    /** Nothing left to close; let the system leave the app. */
    EXIT
}

data class NavSnapshot(
    val atHome: Boolean,
    val detailOpen: Boolean,
    val playing: Boolean,
    val panelOpen: Boolean,
    val chromeVisible: Boolean,
    val queryNotEmpty: Boolean,
    val sheetOpen: Boolean = false
) {
    /** The stack, bottom first, as the UI draws it. */
    val stack: List<Layer>
        get() = buildList {
            add(Layer.ROUTE)
            if (detailOpen) add(Layer.DETAIL)
            if (sheetOpen && !playing) add(Layer.SHEET)
            if (playing) add(Layer.PLAYER)
            if (playing && panelOpen) add(Layer.PLAYER_PANEL)
        }
}

fun resolveBack(s: NavSnapshot): BackAction = when (s.stack.last()) {
    Layer.PLAYER_PANEL -> BackAction.CLOSE_PLAYER_PANEL
    Layer.PLAYER -> if (s.chromeVisible) BackAction.STOP_PLAYBACK else BackAction.SHOW_PLAYER_CHROME
    Layer.SHEET -> BackAction.CLOSE_SHEET
    Layer.DETAIL -> BackAction.CLOSE_DETAIL
    Layer.ROUTE -> when {
        s.queryNotEmpty -> BackAction.CLEAR_QUERY
        !s.atHome -> BackAction.GO_HOME
        else -> BackAction.EXIT
    }
}
