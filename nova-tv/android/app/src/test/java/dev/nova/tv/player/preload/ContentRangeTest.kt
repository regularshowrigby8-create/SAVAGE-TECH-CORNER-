package dev.nova.tv.player.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRangeTest {
    @Test
    fun `reads the total from a 206 content range`() {
        assertEquals(4_294_967_296L, FragmentPreloader.parseContentRangeTotal("bytes 0-0/4294967296"))
    }

    @Test
    fun `unknown or missing totals are rejected`() {
        assertNull(FragmentPreloader.parseContentRangeTotal("bytes 0-0/*"))
        assertNull(FragmentPreloader.parseContentRangeTotal(null))
        assertNull(FragmentPreloader.parseContentRangeTotal("garbage"))
        assertNull(FragmentPreloader.parseContentRangeTotal("bytes 0-0/0"))
    }
}
