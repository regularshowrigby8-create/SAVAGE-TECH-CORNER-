// DNS-over-HTTPS resolvers are ARVIO's (network/OkHttpProvider.kt,
// https://github.com/ProdigyV21/ARVIO commit 31ce7d7, Apache License 2.0):
// Cloudflare / Google / AdGuard over HTTPS with pinned bootstrap addresses,
// falling back to the system resolver. Nova adds AUTO (system first, DoH when
// the ISP resolver fails or blocks a host), a TTL cache and IPv4-first order.
package dev.nova.tv.data.remote

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DnsProvider(val label: String) {
    AUTO("Auto"),
    SYSTEM("System"),
    CLOUDFLARE("Cloudflare"),
    GOOGLE("Google"),
    ADGUARD("AdGuard")
}

/**
 * The resolver every Nova request uses, addon calls and video alike.
 *
 * Why it matters for buffering: a video host is looked up once per
 * connection, and segment hosts (HLS) rotate. A slow or poisoned ISP resolver
 * adds hundreds of milliseconds to every new connection, and some ISPs
 * return nothing for streaming hosts. AUTO keeps the fast path (system) and
 * only pays for DoH when the system answer is missing.
 */
object NovaDns : Dns {

    @Volatile var provider: DnsProvider = DnsProvider.AUTO

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val resolved = ipv4First(resolve(key))
        if (resolved.isNotEmpty()) {
            if (cache.size > MAX_ENTRIES) cache.clear()
            cache[key] = Entry(resolved, now + TTL_MS)
        }
        return resolved
    }

    fun clearCache() = cache.clear()

    private fun resolve(host: String): List<InetAddress> {
        val choice = provider
        return when (choice) {
            DnsProvider.SYSTEM -> Dns.SYSTEM.lookup(host)
            DnsProvider.AUTO -> try {
                Dns.SYSTEM.lookup(host).ifEmpty { throw UnknownHostException(host) }
            } catch (system: UnknownHostException) {
                runCatching { cloudflare.lookup(host) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { google.lookup(host) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: throw system
            }
            else -> try {
                doh(choice).lookup(host)
            } catch (selected: UnknownHostException) {
                // ARVIO: a DoH outage must not take the app offline.
                runCatching { Dns.SYSTEM.lookup(host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: throw selected
            }
        }
    }

    /**
     * Many TV boxes advertise IPv6 that does not route. OkHttp tries addresses
     * in order, so a dead v6 address costs a full connect timeout first.
     */
    internal fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
        addresses.filterIsInstance<Inet4Address>() + addresses.filterNot { it is Inet4Address }

    private fun doh(p: DnsProvider): Dns = when (p) {
        DnsProvider.GOOGLE -> google
        DnsProvider.ADGUARD -> adguard
        else -> cloudflare
    }

    private val cloudflare by lazy {
        build("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1"))
    }
    private val google by lazy {
        build("https://dns.google/dns-query", "dns.google", listOf("8.8.8.8", "8.8.4.4"))
    }
    private val adguard by lazy {
        build("https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com", listOf("94.140.14.14", "94.140.15.15"))
    }

    /** ARVIO buildDohDns: the DoH host itself resolves to fixed IPs, no chicken-and-egg. */
    private fun build(url: String, host: String, bootstrap: List<String>): Dns {
        val addresses = bootstrap.map { InetAddress.getByName(it) }
        val bootstrapDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.equals(host, ignoreCase = true)) addresses else Dns.SYSTEM.lookup(hostname)
        }
        val client = OkHttpClient.Builder()
            .dns(bootstrapDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        return DnsOverHttps.Builder()
            .client(client)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(addresses)
            .post(true)
            // Private answers for public names are a DNS rebinding attempt.
            .resolvePrivateAddresses(false)
            .resolvePublicAddresses(true)
            .build()
    }

    private const val TTL_MS = 10 * 60_000L
    private const val MAX_ENTRIES = 512
}
