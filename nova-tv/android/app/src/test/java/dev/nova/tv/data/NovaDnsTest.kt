package dev.nova.tv.data

import dev.nova.tv.data.remote.NovaDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a public name that resolves to the LAN is refused at connect time`() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.10", "172.16.0.5", "169.254.1.1", "100.64.0.7", "0.0.0.0")
            .forEach { assertFalse(it, NovaDns.isPublicAddress(InetAddress.getByName(it))) }
        listOf("::1", "fd00::1", "fe80::1").forEach {
            assertFalse(it, NovaDns.isPublicAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `public addresses are still allowed`() {
        assertTrue(NovaDns.isPublicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(NovaDns.isPublicAddress(InetAddress.getByName("93.184.216.34")))
        assertTrue(NovaDns.isPublicAddress(InetAddress.getByName("2606:4700::1111")))
    }
}
