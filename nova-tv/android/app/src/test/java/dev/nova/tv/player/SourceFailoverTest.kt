package dev.nova.tv.player

import dev.nova.tv.data.model.Stream
import dev.nova.tv.player.engine.nextFailoverIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** One dead source must move to the next distinct one, never loop. */
class SourceFailoverTest {

    private val a = Stream(url = "https://a.example/1.m3u8")
    private val b = Stream(url = "https://b.example/1.m3u8")
    private val c = Stream(url = "https://c.example/1.mp4")

    @Test
    fun `moves to the next untried source`() {
        assertEquals(1, nextFailoverIndex(listOf(a, b, c), 0, setOf(0)))
        assertEquals(2, nextFailoverIndex(listOf(a, b, c), 1, setOf(0, 1)))
    }

    @Test
    fun `wraps around to sources before the start`() {
        assertEquals(0, nextFailoverIndex(listOf(a, b, c), 2, setOf(2)))
    }

    @Test
    fun `stops when everything was tried`() {
        assertEquals(-1, nextFailoverIndex(listOf(a, b, c), 2, setOf(0, 1, 2)))
        assertEquals(-1, nextFailoverIndex(emptyList(), 0, emptySet()))
    }

    @Test
    fun `a mirror of the failed url is not a new attempt`() {
        val mirror = a.copy(name = "Mirror")
        assertEquals(2, nextFailoverIndex(listOf(a, mirror, b), 0, setOf(0)))
    }
}
