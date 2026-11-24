package dev.nova.tv.data

import dev.nova.tv.data.remote.NovaDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class NovaDnsTest {
    @Test
    fun `IPv4 answers come first so a broken IPv6 route cannot stall the start`() {
        val v6 = InetAddress.getByName("2606:4700::1111")
        val v4 = InetAddress.getByName("1.1.1.1")
        val sorted = NovaDns.ipv4First(listOf(v6, v4))
        assertTrue(sorted.first() is Inet4Address)
        assertEquals(2, sorted.size)
    }
}
