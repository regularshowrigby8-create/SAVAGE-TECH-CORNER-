package dev.nova.tv.finder.translated

import kotlin.random.Random

/**
 * Rotating browser identity: UA, language, timezone, client hints, XFF.
 *
 * Translation of `Fingerprint` in both Python scripts. The two scripts use
 * slightly different pools, so the pools are constructor parameters with the
 * servers.py values as defaults; [ServerFinder] and [AddonFinder] pass their
 * own lists, exactly as their Python originals do.
 */
class FinderFingerprint(
    private val uas: List<String> = UAS_SERVER,
    private val langs: List<String> = LANGS_SERVER,
    private val xffPatterns: List<String> = XFF_SERVER,
    private val timezones: List<String> = TZ_SERVER,
    private val random: Random = Random.Default
) {
    var ua: String = ""
        private set
    var lang: String = ""
        private set
    var tz: String = ""
        private set
    var xff: String = ""
        private set
    var chrome: String? = null
        private set

    init {
        rotate()
    }

    fun rotate() {
        ua = uas.random(random)
        lang = langs.random(random)
        tz = timezones.random(random)
        xff = xffPatterns.random(random)
            .format(random.nextInt(1, 255), random.nextInt(1, 255))
        val m = Regex("(?:Chrome|Edg)/(\\d+)").find(ua)
        chrome = if (m != null && "Firefox" !in ua) m.groupValues[1] else null
    }

    /**
     * servers.py `Fingerprint.headers`: full document header set with a
     * matching referer / origin.
     */
    fun headersDocument(url: String, referer: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua
        h["Accept"] = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = lang
        // Deliberately NOT set. OkHttp adds `Accept-Encoding: gzip` itself and
        // transparently decompresses the response; setting the header by hand
        // disables that, and OkHttp 4.12 has no brotli decoder. The result was
        // that every server answering with gzip/br handed the finder compressed
        // bytes, `String(bytes)` contained no `href=` at all, and every surface
        // logged zero: `search -> 0 host(s) of 0 link(s)`, `ct -> 0`,
        // `rapiddns -> 0`, `wayback -> 0`. Measured on Bing: 0 hrefs raw vs 93
        // decoded. Letting OkHttp negotiate is the whole fix.
        h["Referer"] = referer ?: FinderHelpers.originOf(url) + "/"
        h["Origin"] = FinderHelpers.originOf(url)
        h["DNT"] = "1"
        h["Upgrade-Insecure-Requests"] = "1"
        h["Sec-Fetch-Dest"] = "document"
        h["Sec-Fetch-Mode"] = "navigate"
        h["Sec-Fetch-User"] = "?1"
        h["Sec-Fetch-Site"] = if (referer != null && FinderHelpers.hostOf(referer) == FinderHelpers.hostOf(url)) "same-origin" else "cross-site"
        h["X-Forwarded-For"] = xff
        h["X-Real-IP"] = xff
        h["Priority"] = "u=0, i"
        h["Connection"] = "keep-alive"
        chrome?.let { c ->
            h["sec-ch-ua"] = "\"Chromium\";v=\"$c\", \"Google Chrome\";v=\"$c\", \"Not?A_Brand\";v=\"24\""
            h["sec-ch-ua-mobile"] = if ("Mobile" in ua) "?1" else "?0"
            h["sec-ch-ua-platform"] = when {
                "Android" in ua -> "\"Android\""
                "iPhone" in ua -> "\"iOS\""
                else -> "\"Windows\""
            }
        }
        return h
    }

    /**
     * addons.py `Fingerprint.headers`: CORS-shaped header set matching the
     * rotated identity.
     */
    fun headersCors(url: String, referer: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua
        h["Accept"] = "application/json,text/html;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = lang
        // Deliberately NOT set. OkHttp adds `Accept-Encoding: gzip` itself and
        // transparently decompresses the response; setting the header by hand
        // disables that, and OkHttp 4.12 has no brotli decoder. The result was
        // that every server answering with gzip/br handed the finder compressed
        // bytes, `String(bytes)` contained no `href=` at all, and every surface
        // logged zero: `search -> 0 host(s) of 0 link(s)`, `ct -> 0`,
        // `rapiddns -> 0`, `wayback -> 0`. Measured on Bing: 0 hrefs raw vs 93
        // decoded. Letting OkHttp negotiate is the whole fix.
        h["Referer"] = referer ?: FinderHelpers.originOf(url) + "/"
        h["Origin"] = FinderHelpers.originOf(url)
        h["X-Forwarded-For"] = xff
        h["X-Real-IP"] = xff
        h["DNT"] = "1"
        h["Sec-Fetch-Dest"] = "empty"
        h["Sec-Fetch-Mode"] = "cors"
        h["Sec-Fetch-Site"] = "cross-site"
        chrome?.let { c ->
            h["sec-ch-ua"] = "\"Chromium\";v=\"$c\", \"Google Chrome\";v=\"$c\", \"Not?A_Brand\";v=\"24\""
            h["sec-ch-ua-mobile"] = if ("Mobile" in ua) "?1" else "?0"
            h["sec-ch-ua-platform"] = when {
                "Android" in ua -> "\"Android\""
                "iPhone" in ua -> "\"iOS\""
                else -> "\"Windows\""
            }
        }
        return h
    }

    companion object {
        /** servers.py `UAS` (10 entries). */
        val UAS_SERVER = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        )

        /** addons.py `UAS` (7 entries). */
        val UAS_ADDON = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        )

        /** servers.py `LANGS`. */
        val LANGS_SERVER = listOf(
            "en-US,en;q=0.9",
            "en-GB,en;q=0.8",
            "en-US,en;q=0.9,es;q=0.6",
            "de-DE,de;q=0.9,en;q=0.8"
        )

        /** addons.py `LANGS`. */
        val LANGS_ADDON = listOf(
            "en-US,en;q=0.9",
            "en-GB,en;q=0.8",
            "en-US,en;q=0.9,de;q=0.5"
        )

        /** servers.py `XFF`. */
        val XFF_SERVER = listOf("104.16.%d.%d", "35.180.%d.%d", "172.67.%d.%d", "89.187.%d.%d", "146.70.%d.%d")

        /** addons.py `XFF`. */
        val XFF_ADDON = listOf("104.16.%d.%d", "35.180.%d.%d", "172.67.%d.%d", "146.70.%d.%d")

        /** servers.py `TZ`. */
        val TZ_SERVER = listOf("America/New_York", "Europe/London", "Europe/Berlin", "Asia/Tokyo", "America/Los_Angeles")
    }
}
