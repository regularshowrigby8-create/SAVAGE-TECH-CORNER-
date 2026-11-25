package dev.nova.tv.finder.servers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Low precision, wide horizon. Every adapter is optional and failing one is
 * never fatal. Nothing here trusts a whole page: URLs are extracted, then
 * gated, then verified.
 */
object FreshSearch {

    private val queries = listOf(
        "\"best free streaming sites\" 2026",
        "best sites to watch movies free 2026",
        "new free movie streaming site no signup",
        "free streaming site watch series online 2026"
    )
    private val subs = listOf("StremioAddons", "Stremio", "Piracy", "addons4kodi", "animepiracy", "FREEMEDIAHECKYEAH")
    private val tlds = listOf("to", "com", "net", "ag", "am", "cx", "is", "ru", "io", "su", "st", "ws", "pw", "cc", "top", "vip", "nl", "se", "la", "bar", "win", "biz", "app")
    private val link = Regex("""href="(https?://[^"]+)"""")
    private val noise = setOf(
        "google.com", "youtube.com", "facebook.com", "twitter.com", "x.com", "reddit.com",
        "instagram.com", "pinterest.com", "wikipedia.org", "cloudflare.com", "gstatic.com",
        "bing.com", "microsoft.com", "duckduckgo.com", "quora.com", "medium.com", "github.com",
        "amazon.com", "netflix.com", "primevideo.com", "disneyplus.com", "hulu.com", "max.com"
    )

    suspend fun listicles(): List<String> =
        queries.flatMap { q ->
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            val pages = listOf(
                Net.get("https://html.duckduckgo.com/html/?q=$enc&df=y", 300_000),
                Net.get("https://www.bing.com/search?q=$enc&qft=+filterui:age-lt43200", 300_000, mobile = false),
                Net.get("https://search.marginalia.nu/search?query=$enc", 300_000, mobile = false)
            )
            pages.mapNotNull { it?.body }
        }.flatMap { body ->
            link.findAll(body).map { it.groupValues[1] }
                .mapNotNull { Similarity.unwrapDdg(it) }
                .toList()
        }.mapNotNull { registrable(hostOf(it)) }.filter { it !in noise }.distinct()

    suspend fun reddit(limit: Int = 100): List<String> =
        subs.flatMap { sub ->
            val r = Net.get("https://www.reddit.com/r/$sub/new.json?limit=$limit", 400_000)
                ?: return@flatMap emptyList<String>()
            val dest = Regex(""""url_overridden_by_dest"\s*:\s*"([^"]+)"""")
            dest.findAll(r.body).map { it.groupValues[1] }.toList() +
                link.findAll(r.body).map { it.groupValues[1].replace("\\/", "/") }.toList()
        }.mapNotNull { registrable(hostOf(it)) }.filter { it !in noise }.distinct()

    suspend fun github(token: String?): List<String> {
        if (token.isNullOrBlank()) return emptyList()
        val queries = listOf("topic:stremio-addon", "stremio+addon+catalog", "filename:manifest.json+stremio")
        return queries.flatMap { q ->
            val r = Net.get(
                "https://api.github.com/search/repositories?q=$q&per_page=50&sort=updated",
                400_000, mobile = false
            ) ?: return@flatMap emptyList<String>()
            val homepage = Regex(""""homepage"\s*:\s*"([^"]+)"""")
            homepage.findAll(r.body).mapNotNull { it.groupValues[1].takeIf { h -> h.startsWith("http") } }.toList()
        }.mapNotNull { registrable(hostOf(it)) }.distinct()
    }

    /** Domains first seen in the archive inside the window: these are new, not stale. */
    suspend fun cdxyRecent(stems: List<String>): List<String> {
        val from = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now() - 90L * 86_400_000))
        return stems.take(25).flatMap { stem ->
            val r = Net.get(
                "http://web.archive.org/cdx/search/cdx?url=$stem&matchType=domain&from=$from&fl=original&collapse=urlkey&limit=400",
                500_000, mobile = false
            ) ?: return@flatMap emptyList<String>()
            r.body.lineSequence().mapNotNull { registrable(hostOf(it)) }.toList()
        }.distinct()
    }

    fun stems(acceptedHosts: List<String>): List<String> =
        acceptedHosts.mapNotNull { registrable(it)?.substringBefore('.') }
            .filter { it.length in 3..24 && it.none { c -> c.isDigit() } }.distinct()

    fun sweep(stems: List<String>): List<String> =
        stems.flatMap { s -> tlds.map { "$s.$it" } }
}
