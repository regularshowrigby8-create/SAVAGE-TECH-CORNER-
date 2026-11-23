package dev.nova.tv.finder.translated

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.round

/**
 * Pure helpers shared by the two translated Python finders.
 *
 * Line-for-line translations of the helper functions in `servers.py`
 * (server finder) and `addons.py` (add-on finder). No Android, no network,
 * so every function here is unit-tested on the JVM.
 *
 * Python -> Kotlin mapping used throughout the translated finders:
 *  - `datetime.now(timezone.utc).isoformat(timespec="seconds")` -> [now]
 *  - `urlsplit(url).hostname` -> [hostOf]
 *  - `f"{p.scheme}://{p.netloc}"` -> [originOf]
 *  - `norm(url)` -> [norm]
 *  - `provider_of(url)` -> [providerOf]
 *  - `quality_of(text)` -> [qualityOf]
 *  - `mirrors(url)` -> [mirrors]
 *  - `hashlib.sha1(x.encode()).hexdigest()[:16]` -> [sha1_16]
 *  - `score_of(...)` in each script -> [scoreOfServer] / [scoreOfAddon]
 */
object FinderHelpers {

    /** Hard ceiling on validated entries. Nothing above the cap is ever written. */
    const val CAP = 6000

    /** Seconds between checker sweeps. */
    const val CHECK_INTERVAL = 300

    /** Simultaneous headless pages in the Python scripts; kept for parity. */
    const val RENDER_SLOTS = 4

    /** Bytes pulled before a host counts as buffering. */
    const val PROBE_BYTES = 262_144

    /** Read size during a buffer probe. */
    const val CHUNK = 32_768

    /** Clamp so a huge sitemap cannot dominate the rank. */
    const val MAX_CATALOGUE = 250_000

    /** Consecutive failures before quarantine. */
    const val FAILS_TO_DEAD = 2

    /** UTC timestamp, second precision, ISO 8601. Python: `now()`. */
    fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** Lower-cased hostname without the leading www., '' when unparsable. */
    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase()?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    /** `scheme://netloc`, e.g. `https://example.com:8443`. */
    fun originOf(url: String): String {
        val p = runCatching { URI(url) }.getOrNull() ?: return ""
        val scheme = p.scheme ?: "https"
        val authority = p.rawAuthority ?: p.host ?: return ""
        return "$scheme://$authority"
    }

    /**
     * Canonical form: collapsed slashes, no trailing slash, no fragment.
     * Python: `norm(url)`.
     */
    fun norm(url: String): String {
        val raw = url.trim()
        val p = runCatching { URI(raw) }.getOrNull() ?: return raw
        val scheme = p.scheme ?: "https"
        val authority = (p.rawAuthority ?: "").lowercase()
        var path = p.rawPath.orEmpty().replace(Regex("/{2,}"), "/").trimEnd('/')
        if (path.isEmpty()) path = "/"
        val query = p.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authority$path$query"
    }

    /** Resolve [raw] against [base], like Python `urljoin(base, raw)`. */
    fun urljoin(base: String, raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return base
        if (clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true)
        ) return clean
        return runCatching { URI(base).resolve(clean).toString() }.getOrDefault(clean)
    }

    /**
     * Host fragment -> canonical provider name. Anything not listed falls back
     * to the registrable label of the host. Python: `provider_of(url)`.
     */
    fun providerOf(url: String): String {
        val h = hostOf(url)
        for ((key, name) in PROVIDERS) {
            if (key in h) return name
        }
        val labels = h.split(".")
        return if (labels.size >= 2) labels[labels.size - 2] else h
    }

    /** (label, tier) for the strongest quality token found in text. */
    fun qualityOf(text: String): Pair<String, Int> {
        for ((rx, label, tier) in QUALITY) {
            if (rx.containsMatchIn(text)) return label to tier
        }
        return "unknown" to 0
    }

    /** Host prefixes worth trying; `m.`/`api.` are how these anime sites split. */
    val MIRROR_PREFIXES = listOf("", "www.", "m.", "api.")

    /** www / m / api variants of a host. Python: `mirrors(url)`. */
    fun mirrors(url: String): Set<String> {
        val out = LinkedHashSet<String>()
        out += norm(url)
        val p = runCatching { URI(url) }.getOrNull() ?: return out
        val bare = (p.rawAuthority ?: "").removePrefix("www.")
        val scheme = p.scheme ?: "https"
        val query = p.rawQuery?.let { "?$it" }.orEmpty()
        // A root URL must keep its trailing slash. On device, mirrors of
        // `https://hianime.to` (no path) rendered a 2 KB blank page, while
        // `https://hianime.to/` from the same seed rendered 258 KB with the
        // catalogue in it -- the site redirects `/` properly only when the
        // slash is present, and every path-less mirror was wasted work.
        // Likewise `9anime.org.lv` yielded 0 candidates where
        // `www.9anime.org.lv/` yielded 1, so the bare form is kept too rather
        // than treated as equivalent.
        val path = p.rawPath.orEmpty().ifEmpty { "/" }
        for (pre in MIRROR_PREFIXES) {
            val host = if (pre.isEmpty()) bare else "$pre$bare"
            out += norm("$scheme://$host$path$query")
        }
        return out
    }

    /** `hashlib.sha1(x.encode()).hexdigest()[:16]`. */
    fun sha1_16(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * servers.py `score_of`: rank is 55% catalogue (log-scaled), 30% quality
     * tier, 15% buffer, minus latency.
     */
    fun scoreOfServer(catalogue: Int, tier: Int, bufferScore: Int, ttfb: Int): Double {
        val cat = log10(1.0 + maxOf(catalogue, 0)) / log10(1.0 + MAX_CATALOGUE)
        return round((0.55 * cat * 100 + 0.30 * min(tier, 400) / 4.0 + 0.15 * bufferScore - ttfb / 120.0) * 100) / 100.0
    }

    /**
     * addons.py `score_of`: rank is 45% catalogue (log-scaled), 25% quality,
     * 15% stream breadth, 15% buffer, minus latency.
     */
    fun scoreOfAddon(catalogue: Int, tier: Int, streams: Int, bufferScore: Int, ttfb: Int): Double {
        val cat = log10(1.0 + maxOf(catalogue, 0)) / log10(1.0 + MAX_CATALOGUE)
        return round(
            (0.45 * cat * 100 + 0.25 * min(tier, 400) / 4.0 + 0.15 * min(streams, 60) / 0.6 + 0.15 * bufferScore - ttfb / 120.0) * 100
        ) / 100.0
    }

    /** Buffer-health score shared by both probers: 0..100. */
    fun bufferScore(ttfbMs: Int, stalls: Int, jitterMs: Int, kbps: Int): Int {
        val raw = 100 - ttfbMs / 40 - stalls * 12 - jitterMs / 25 + min(kbps / 60, 35)
        return raw.coerceIn(0, 100)
    }

    /** Host fragment -> canonical provider name. servers.py `PROVIDERS`. */
    val PROVIDERS: Map<String, String> = mapOf(
        "vidstream" to "vidstream",
        "vidcloud" to "vidcloud",
        "filemoon" to "filemoon",
        "mp4upload" to "mp4upload",
        "streamtape" to "streamtape",
        "dood" to "doodstream",
        "doodstream" to "doodstream",
        "vidhide" to "vidhide",
        "streamwish" to "streamwish",
        "megacloud" to "megacloud",
        "rabbitstream" to "rabbitstream",
        "ok.ru" to "okru",
        "bunny" to "bunnycdn",
        "streamsb" to "streamsb",
        "vidsrc" to "vidsrc",
        "upstream" to "upstream",
        "streamlare" to "streamlare",
        "mixdrop" to "mixdrop",
        "voe" to "voe",
        "luluvdo" to "luluvdo",
        "streamhub" to "streamhub",
        "vidlink" to "vidlink",
        "embedsito" to "embedsito",
        "mega.nz" to "mega"
    )

    /** servers.py `QUALITY`, in order: first match wins. */
    val QUALITY: List<Triple<Regex, String, Int>> = listOf(
        Triple(Regex("2160|4k|uhd", RegexOption.IGNORE_CASE), "2160p", 400),
        Triple(Regex("hdr|dolby.?vision|\\bdv\\b", RegexOption.IGNORE_CASE), "HDR", 60),
        Triple(Regex("1080|fhd|full.?hd", RegexOption.IGNORE_CASE), "1080p", 300),
        Triple(Regex("720|\\bhd\\b", RegexOption.IGNORE_CASE), "720p", 200),
        Triple(Regex("576|480|\\bsd\\b|cam", RegexOption.IGNORE_CASE), "480p", 100)
    )

    /** addons.py `QUALITY`, in order: first match wins. */
    val QUALITY_ADDON: List<Triple<Regex, String, Int>> = listOf(
        Triple(Regex("2160|4k|uhd", RegexOption.IGNORE_CASE), "2160p", 400),
        Triple(Regex("hdr|dolby.?vision|\\bdv\\b", RegexOption.IGNORE_CASE), "HDR", 60),
        Triple(Regex("1080|fhd", RegexOption.IGNORE_CASE), "1080p", 300),
        Triple(Regex("720", RegexOption.IGNORE_CASE), "720p", 200),
        Triple(Regex("480|sd\\b", RegexOption.IGNORE_CASE), "480p", 100)
    )

    /** (label, tier) using the addons.py quality table. */
    fun qualityOfAddon(text: String): Pair<String, Int> {
        for ((rx, label, tier) in QUALITY_ADDON) {
            if (rx.containsMatchIn(text)) return label to tier
        }
        return "unknown" to 0
    }
}
