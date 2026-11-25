package dev.nova.tv.finder.servers

import java.net.URLDecoder

/**
 * High-precision expansion from an accepted host. Two signals:
 *  - the page's own outbound footer links (best);
 *  - third-party similar-sites adapters, crossed for agreement.
 * Markup on the adapters churns, so extraction is token-based and each host
 * seen in more than one adapter gains agreement, which raises its priority.
 */
object Similarity {

    private val noise = setOf(
        "google.com", "youtube.com", "facebook.com", "twitter.com", "instagram.com",
        "cloudflare.com", "gstatic.com", "jquery.com", "bootstrapcdn.com", "fontawesome.com",
        "googleapis.com", "doubleclick.net", "googletagmanager.com", "cloudfront.net",
        "w3.org", "schema.org", "sitelike.org", "similarsites.com", "siteslike.com",
        "hypestat.com", "statshow.com", "similarweb.com", "alexa.com", "patreon.com",
        "discord.gg", "discord.com", "t.me", "telegram.org", "reddit.com", "github.com"
    )
    private val domain = Regex("""(?<![\w.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+([a-z]{2,24}))(?![\w-])""")

    private val adapters = listOf(
        "https://www.similarsites.com/sites-like/",
        "https://www.siteslike.com/sites-like/",
        "https://hypestat.com/info/",
        "https://www.statshow.com/www/"
    )

    /** Footer-biased outbound links. Cheap, local, highest precision. */
    fun siteLinks(html: String, fromHost: String): List<String> {
        val self = registrable(fromHost)
        val tail = html.substring(html.length / 2).lowercase()   // footers live in the back half
        return domain.findAll(tail)
            .map { it.groupValues[1] }
            .mapNotNull { registrable(it) }
            .filter { it != self && it !in noise && !it.endsWith(".js") }
            .distinct()
            .take(40)
            .toList()
    }

    /** Ask every adapter; hosts appearing in 2+ are agreement > 1. */
    suspend fun similar(host: String): List<Pair<String, Int>> {
        val votes = HashMap<String, Int>()
        val self = registrable(host)
        adapters.forEach { base ->
            val r = Net.get(base + host, 240_000, mobile = false) ?: return@forEach
            if (r.status >= 400) return@forEach
            val low = r.body.lowercase()
            domain.findAll(low)
                .map { it.groupValues[1] }
                .mapNotNull { registrable(it) }
                .filter { it != self && it !in noise }
                .distinct()
                .take(60)
                .forEach { votes.merge(it, 1, Int::plus) }
        }
        return votes.entries.map { (h, n) -> h to n }.sortedByDescending { (_, n) -> n }.take(60)
    }

    /** Addon manifests point at a config host, repo or homepage; those are sites. */
    fun addonGraph(manifest: String, host: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""https?://([^"'\\\s]+)""").findAll(manifest).forEach { m ->
            val raw = m.value
            if (raw.contains(host)) return@forEach
            val r = registrable(hostOf(raw))
            if (r != null && r in noise) return@forEach
            if (r != null && r.endsWith(".vercel.app")) out += raw else if (r != null) out += "https://$r/"
        }
        return out.distinct().take(10)
    }

    fun unwrapDdg(href: String): String? = try {
        if (href.contains("uddg=")) URLDecoder.decode(href.substringAfter("uddg=").substringBefore('&'), "UTF-8") else href
    } catch (e: Exception) { null }
}
