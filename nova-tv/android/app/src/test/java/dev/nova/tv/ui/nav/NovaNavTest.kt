package dev.nova.tv.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** BACK peels exactly one layer, top of the stack first. */
class NovaNavTest {

    private fun snap(
        atHome: Boolean = true,
        detail: Boolean = false,
        playing: Boolean = false,
        panel: Boolean = false,
        chrome: Boolean = false,
        query: Boolean = false
    ) = NavSnapshot(atHome, detail, playing, panel, chrome, query)

    @Test
    fun `panel closes before the player`() {
        assertEquals(BackAction.CLOSE_PLAYER_PANEL, resolveBack(snap(detail = true, playing = true, panel = true, chrome = true)))
    }

    @Test
    fun `first back while watching only reveals the controls`() {
        assertEquals(BackAction.SHOW_PLAYER_CHROME, resolveBack(snap(detail = true, playing = true, chrome = false)))
        assertEquals(BackAction.STOP_PLAYBACK, resolveBack(snap(detail = true, playing = true, chrome = true)))
    }

    @Test
    fun `detail closes before the route changes`() {
        assertEquals(BackAction.CLOSE_DETAIL, resolveBack(snap(atHome = false, detail = true, query = true)))
    }

    @Test
    fun `route level clears the query, then goes home, then exits`() {
        assertEquals(BackAction.CLEAR_QUERY, resolveBack(snap(atHome = false, query = true)))
        assertEquals(BackAction.GO_HOME, resolveBack(snap(atHome = false)))
        assertEquals(BackAction.EXIT, resolveBack(snap()))
    }

    @Test
    fun `a panel flag without playback is ignored`() {
        assertEquals(listOf(Layer.ROUTE), snap(panel = true).stack)
    }
}
