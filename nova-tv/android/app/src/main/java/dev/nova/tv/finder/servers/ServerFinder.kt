package dev.nova.tv.finder.servers

import dev.nova.tv.finder.FinderSourceReport
import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.diag.FinderTrace
import dev.nova.tv.finder.discovery.Discovery
import dev.nova.tv.finder.translated.FinderBrowser
import dev.nova.tv.finder.translated.FinderFingerprint
import dev.nova.tv.finder.translated.FinderHelpers
import dev.nova.tv.finder.translated.FinderHttp
import dev.nova.tv.finder.website.SavedWebsite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import java.util.Base64
import kotlin.math.abs
import kotlin.random.Random

/**
 * servers.py — streaming-server harvester for the Kotlin client.
 *
 * Targets piracy / anime streaming front-ends (9anime, HiAnime, Zoro and
 * their live mirrors), pulls their embedded server options, verifies that
 * each option actually serves bytes, counts the catalogue behind it and
 * stores the survivors in a ranked JSON pool the app can query directly.
 *
 * Hard rules baked in (unchanged from the Python script):
 *  - CAP = 6000 validated servers. Nothing above the cap is ever written.
 *  - Ordering is catalogue size first, then stream quality tier, then buffer
 *    health, then latency. The biggest catalogues stay on top.
 *  - A checker re-probes the weakest slice every 300 s, dumps dead or faulty
 *    hosts to dead_servers.json / dead_servers.txt and refills the pool back
 *    to the cap from the discovery frontier.
 *  - Paywall and hotlink defence is defeated by fingerprint rotation, cookie
 *    priming, forged Referer / Origin, X-Forwarded-For geo spoofing, mirror
 *    expansion, honours Retry-After on 429 and mints Cloudflare clearances
 *    through headless Chromium (no-op on Android; see FinderBrowser).
 *
 * This replaces the old `WebsiteSpider` script: same plug point
 * (`harvest(round)` -> sites + more + sources, feeding `WebsitePool`), new
 * engine. Everything lives in `src/main`, so both flavours — `tv`
 * (`dev.nova.tv`) and `mobile` (`dev.nova.mobile`) — get it.
 *
 * Output contract (unchanged):
 *  servers_pool.json  {schema, generated_at, cap, count, servers[]}
 *  dead_servers.json/.txt  quarantine, also fed back in as a blocklist
 */
object ServerFinder {
    const val CAP = 6000
    const val CHECK_INTERVAL = 300
    const val DEFAULT_CONCURRENCY = 48
    const val RENDER_SLOTS = 4
    const val PROBE_BYTES = 262_144
    const val CHUNK = 32_768
    const val MAX_CATALOGUE = 250_000
    const val FAILS_TO_DEAD = 2

    /** Page JSON calls replayed per page, and servers followed per page. */
    const val MAX_API_CALLS = 12
    const val MAX_SERVERS = 24

    /** Shell renders per round; the WebView serialises, so this is a hard cap. */
    const val MAX_SHELL_RENDERS = 16

    /** Watch pages queued from one sitemap, and hosts from one page's links. */
    const val MAX_SITEMAP_URLS = 40
    const val MAX_GRAPH_HOSTS = 12

    /**
     * There is deliberately no seed list.
     *
     * The previous build started from 20 hardcoded sites. On device every one
     * of them rendered the same 2262-byte Cloudflare interstitial, so the
     * crawl had exactly one page to read per site and nothing to extract --
     * the list was the entire search space. Discovery is now generated in
     * [dev.nova.tv.finder.discovery.Discovery]: queries are built from viewer
     * intents and answered by open indexes (web search, certificate
     * transparency, urlscan, Common Crawl), and the frontier grows from the
     * link graph and sitemaps of whatever it touches. Nothing here names a
     * site, so the surface is unbounded.
     */

    val IFRAME = Regex("<(?:iframe|embed|source)[^>]+(?:src|data-src|data-file|file)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    val JS_SRC = Regex("(?:file|source|src|url|link)\\s*:\\s*[\"']((?:https?:)?//[^\"'\\\\ ]+)[\"']", RegexOption.IGNORE_CASE)
    val HLS = Regex("[\"']([^\"']+?\\.(?:m3u8|mpd)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
    val MAGIC = Regex("[\"']([A-Za-z0-9+/=]{40,})[\"']")

    /**
     * Advertising and measurement hosts.
     *
     * On-device, every candidate the extractor produced on a rendered page was
     * a Google ad: `syndicatedsearch.goog/afs/ads`, `googleads.g.doubleclick`,
     * `adtrafficquality.google/sodar`, `googletagservices`. Thirty such probes
     * were spent and not one was a player, because ad frames match the very
     * same `src="..."` shape as a real embed. The Python never hit this -- it
     * had no rendering, so it never saw a page's ad frames at all.
     */
    val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adtrafficquality.google", "syndicatedsearch.goog", "google.com",
        "gstatic.com", "googleapis.com", "searchandhelp.com", "adservice.google",
        "facebook.net", "fbcdn.net", "amazon-adsystem.com", "criteo.com",
        "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "3lift.com", "sharethrough.com"
    )

    fun isAdHost(host: String): Boolean = AD_HOSTS.any { host == it || host.endsWith(".$it") }

    /**
     * CMS plumbing, not a player.
     *
     * On device the only candidate the extractor produced on 9anime.org.lv was
     * `https://9anime.org.lv/wp-admin/admin-ajax.php`, which answered 400 on
     * every probe. It matched because a WordPress theme prints its AJAX
     * endpoint in the very same `url: "..."` shape a player uses. Probing
     * admin handlers is never useful: they are POST-only, they need a nonce,
     * and they cannot serve media. Filtering them at extraction time stops
     * them consuming probes and polluting the drop tallies.
     */
    val JUNK = Regex(
        "/(?:wp-admin|wp-json|wp-includes|wp-login|xmlrpc\\.php|" +
            "admin-ajax\\.php|ajax\\.php|ads?|adserver|adframe|analytics|" +
            "track(?:ing|er)?|pixel|beacon|prebid|gpt|pagead|telemetry)(?:[/?#]|$)",
        RegexOption.IGNORE_CASE
    )

    fun isJunk(url: String): Boolean = JUNK.containsMatchIn(url)

    /**
     * Endpoints whose JSON body holds the real player addresses.
     *
     * These sites never put a `.m3u8` in the document. The page loads, its
     * JavaScript asks `/ajax/v2/episode/servers?episodeId=...` for the server
     * list, then asks `/ajax/v2/episode/sources?id=...` for the one the viewer
     * picked, and only that last response contains the stream URL. The
     * crawler rendered the page, saw the requests go by in the WebView's
     * network log, and then extracted from the DOM alone -- which is why every
     * anime seed produced 0 candidates. [followApis] replays those calls and
     * appends their bodies to the document before extraction.
     */
    val API_HINT = Regex(
        "(/ajax/|/api/|getSources|/sources|/servers|/episode|/stream|/watch|" +
            "/media/|\\.json(?:$|\\?))",
        RegexOption.IGNORE_CASE
    )

    /** `data-id="12345"` in a server list: the id the sources call needs. */
    val DATA_ID = Regex("data-id\\s*=\\s*[\"'](\\d{2,})[\"']", RegexOption.IGNORE_CASE)

    /** Media URLs inside a JSON body, including escaped ones. */
    val MEDIA_IN_JSON = Regex(
        "[\\\"'](https?:(?:\\\\/|/)[^\\\"'<>\\s]+?\\.(?:m3u8|mpd|mp4)(?:\\?[^\\\"'<>\\s]*)?)[\\\"']",
        RegexOption.IGNORE_CASE
    )

    /**
     * A Cloudflare / JS interstitial rather than a catalogue.
     *
     * On device every aniwatch/hianime mirror rendered a byte-identical page
     * (2265 B, one subresource, `load finished=false`) and yielded zero
     * candidates. That is the browser being *challenged*, not the extractor
     * failing: the origin never served us the real document, so there was
     * nothing to extract. Recording it as its own reason stops the crawl from
     * being blamed for a bot wall.
     */
    private val CHALLENGE_MARKERS = listOf(
        "just a moment", "cf-browser-verification", "challenge-platform",
        "enable javascript and cookies to continue", "attention required",
        "checking your browser", "cf_chl_opt", "ddos-guard"
    )

    fun looksChallenged(html: String): Boolean {
        val head = html.take(20_000).lowercase()
        return CHALLENGE_MARKERS.any { it in head }
    }

    /**
     * A domain that exists but is not a streaming site.
     *
     * Measured on device: a brand sweep of the word `anime` produced 95 hosts,
     * and a sample of 14 was 3 parked domains, 1 empty, 1 a 114-byte stub and
     * the rest apparel shops and news portals. Separately `aniwaves.org` -- a
     * real-looking anime site with a watch page and a player iframe -- answered
     * its own player API with `{"info":"Server 1 is not wired yet","mode":"mock"}`.
     * That is a decoy: it renders, it has the shape of a streaming site, and it
     * can never produce a stream.
     *
     * These hosts must be recognised *before* they cost a render slot or a
     * probe, and remembered, because the same parked page will be rediscovered
     * every round. The markers are deliberately precise: `parked` alone would
     * match a sentence in a blog post, so only registrar and marketplace
     * strings are used.
     */
    private val DECOY_MARKERS = listOf(
        "\"mode\":\"mock\"", "\"mode\": \"mock\"", "mode:\"mock\"", "mode: \"mock\"",
        "domain is for sale", "buy this domain", "this domain may be for sale",
        "this domain has expired", "domain has expired", "hugedomains.com",
        "afternic.com", "sedoparking.com", "dan.com/buy-domain", "parkingcrew",
        "future home of something quite cool", "default web page",
        "welcome to nginx", "apache2 debian default page", "it works!"
    )

    fun isDecoy(html: String): Boolean {
        if (html.length < 40) return true
        val head = html.take(60_000).lowercase()
        return DECOY_MARKERS.any { it in head }
    }

    /**
     * A document that builds its player in JavaScript.
     *
     * The homepage of every real target measured (aniwaves, gotaku, 9anime,
     * miruro, anime.live) contained *zero* iframes, zero `.m3u8` and zero
     * `src:` player assignments -- nine pages, zero raw regex matches. The
     * player appears only after the page's own script runs: aniwaves does
     * `document.createElement("iframe")` with `src` built from
     * `O+"/watch#"+k+"."+server+"."+episode+"."+lang`. A static extractor
     * cannot see that, so a page carrying this shape must be rendered even when
     * its document is large enough to look "fine".
     */
    private val PLAYER_SHELL_MARKERS = listOf(
        "createelement(\"iframe\")", "createelement('iframe')",
        "/watch#", "data-sv=", "data-lang=",
        "new player(", "jwplayer(", "videojs(", "hls.js", "hls.min.js",
        "plyr(", "shaka", "dplayer"
    )

    fun looksLikePlayerShell(html: String): Boolean {
        val head = html.take(200_000).lowercase()
        return PLAYER_SHELL_MARKERS.any { it in head }
    }

    /**
     * Whether this document has to be rendered to have any chance of yielding.
     *
     * Rendering is the scarcest resource in the finder -- one WebView, four
     * slots -- so it is spent only where a static read provably cannot work: a
     * document that builds its player in script and has no static player in it
     * yet. A page that already contains an iframe or an `.m3u8` is read
     * statically and never rendered.
     */
    fun needsRender(html: String): Boolean =
        looksLikePlayerShell(html) && !IFRAME.containsMatchIn(html) && !HLS.containsMatchIn(html)

    /**
     * A subresource, not a player.
     *
     * The trace spent probes on `llvpn.com/tag.min.js`, `acscdn.com/script/
     * aclib.js`, `res.public.onecdn.static.microsoft/assets/mail/fonts/v1` and
     * two ad iframes. Every one answered `kind=none buf=100 alive=false`: a
     * script or a font can never be a stream, so probing it is a wasted
     * request. Filtering by extension at extraction time is exact and free.
     */
    private val ASSET_EXT = Regex(
        "\\.(?:js|mjs|css|map|woff2?|ttf|otf|eot|png|jpe?g|gif|webp|svg|ico|bmp|avif)(?:$|[?#])",
        RegexOption.IGNORE_CASE
    )

    fun isAsset(url: String): Boolean = ASSET_EXT.containsMatchIn(url)
    val LOC = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>", RegexOption.IGNORE_CASE)
    val BASE64 = Regex("[A-Za-z0-9+/]{40,}={0,2}")
    val SIG = Regex("sig=([a-f0-9]{32,})|token=([A-Za-z0-9_\\-\\.]{32,})", RegexOption.IGNORE_CASE)

    fun fingerprint(): FinderFingerprint = FinderFingerprint(
        uas = FinderFingerprint.UAS_SERVER,
        langs = FinderFingerprint.LANGS_SERVER,
        xffPatterns = FinderFingerprint.XFF_SERVER,
        timezones = FinderFingerprint.TZ_SERVER
    )

    fun http(base: okhttp3.OkHttpClient, browser: FinderBrowser? = null): FinderHttp =
        FinderHttp(base, DEFAULT_CONCURRENCY, browser, hostPermits = 3, delaySeconds = 0.35, cors = false, fingerprint = fingerprint())

    /** servers.py `score_of`. */
    fun scoreOf(catalogue: Int, tier: Int, bufferScore: Int, ttfb: Int): Double =
        FinderHelpers.scoreOfServer(catalogue, tier, bufferScore, ttfb)
}

/**
 * Why website candidates were dropped, counted per harvest.
 *
 * The addon finder has had this since the ~1% audit and it is the reason its
 * yield is explainable. The website finder never had it, so three attempts to
 * fix "0 sites" were made blind: the screen could only say "No new websites
 * right now", which does not distinguish "every seed is unreachable" from "a
 * seed answered but served a JS shell" from "we found embeds but the probe or
 * the catalogue gate rejected all of them". These tallies make the difference
 * visible on the device, where the answer actually is.
 */
data class ServerRejections(val counts: Map<String, Int> = emptyMap()) {
    fun of(reason: String): Int = counts[reason] ?: 0
    val total: Int get() = counts.values.sum()
    val summary: String get() = counts.entries.sortedByDescending { it.value }
        .joinToString(", ") { "${it.key}=${it.value}" }
}

/** One embed / stream option pulled out of a page. Python: extractor dict. */
data class ServerCandidate(
    val url: String,
    val provider: String,
    val host: String,
    val referer: String
)

/**
 * Pulls embed / stream options out of markup, inline JS and base64 blobs.
 * Python: `ServerExtractor`.
 */
class ServerExtractor {

    private fun add(
        rawIn: String,
        base: String,
        out: MutableMap<String, ServerCandidate>,
        seen: MutableSet<String>
    ) {
        val raw = unescape(rawIn).trim().replace("\\/", "/")
        if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("javascript:")) return
        val url = FinderHelpers.norm(FinderHelpers.urljoin(base, raw))
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || (host in seen && url in out)) return
        // Ads are not players. Before this filter every candidate on a rendered
        // page was an ad frame, and each one cost a probe.
        if (ServerFinder.isAdHost(host)) {
            FinderTrace.count("extractor.ad-skipped")
            return
        }
        // WordPress / CMS handlers are not players either: they are POST-only,
        // nonce-guarded and cannot serve bytes. `admin-ajax.php` was the only
        // candidate found on 9anime.org.lv and it 400'd on every probe.
        if (ServerFinder.isJunk(url)) {
            FinderTrace.count("extractor.junk-skipped")
            return
        }
        // A script, stylesheet, font or image is never a stream. The trace
        // probed `tag.min.js`, `aclib.js` and a font path and got `kind=none`
        // on all three; each was a wasted request.
        if (ServerFinder.isAsset(url)) {
            FinderTrace.count("extractor.asset-skipped")
            return
        }
        seen += host
        out[url] = ServerCandidate(
            url = url,
            provider = FinderHelpers.providerOf(url),
            host = host,
            referer = FinderHelpers.originOf(base) + "/"
        )
    }

    fun extract(html: String, base: String): Map<String, ServerCandidate> {
        val out = LinkedHashMap<String, ServerCandidate>()
        // Per-call, so one page's hosts do not suppress another page's.
        val seen = HashSet<String>()
        for (rx in listOf(ServerFinder.IFRAME, ServerFinder.JS_SRC, ServerFinder.HLS)) {
            for (m in rx.findAll(html)) add(m.groupValues[1], base, out, seen)
        }
        for (blob in ServerFinder.MAGIC.findAll(html).map { it.groupValues[1] }) {
            val decoded = runCatching {
                val padded = blob + "=".repeat((4 - blob.length % 4) % 4)
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if ("http" in decoded) {
                for (m in Regex("https?://[^\\s\"'<>\\\\]+").findAll(decoded)) add(m.value, base, out, seen)
            }
        }
        if ("\"sources\"" in html) {
            for (m in Regex("\"(?:file|url)\"\\s*:\\s*\"([^\"]+)\"").findAll(html)) add(m.groupValues[1], base, out, seen)
        }
        return out
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}

/** Byte-level probe result. Python: `Probe`. */
data class ServerProbe(
    val url: String,
    var status: Int = 0,
    var kind: String = "none",
    var ttfbMs: Int = 0,
    var kbps: Int = 0,
    var stalls: Int = 0,
    var jitterMs: Int = 0,
    var quality: String = "unknown",
    var qualityTier: Int = 0,
    var bufferScore: Int = 0,
    var poisoned: Boolean = false,
    var signature: String = "none",
    var err: String = ""
) {
    val alive: Boolean
        get() = status in listOf(200, 206) && kind !in listOf("none", "page") && bufferScore > 15 && !poisoned
}

/** Byte-level buffer and poison probe. Never trusts a bare 200. Python: `Prober`. */
class ServerProber(private val http: FinderHttp) {

    fun kind(ct: String, head: ByteArray): String {
        val c = ct.lowercase()
        val h = head.take(512).toByteArray()
        val text = String(h, Charsets.ISO_8859_1)
        if ("#EXTM3U" in text) return "hls"
        if ("<MPD" in text || "<mpd" in text) return "dash"
        if ("ftyp" in String(head.take(64).toByteArray(), Charsets.ISO_8859_1)) return "mp4"
        if ("mpegurl" in c) return "hls"
        if ("dash" in c) return "dash"
        if ("mp4" in c || "octet-stream" in c) return "mp4"
        if ("json" in c) return "api"
        if ("html" in c) return "page"
        return "none"
    }

    suspend fun probe(url: String, referer: String?): ServerProbe = withContext(Dispatchers.IO) {
        val p = ServerProbe(url)
        val (label, tier) = FinderHelpers.qualityOf(url)
        p.quality = label
        p.qualityTier = tier
        val handle = http.openStream(url, referer) ?: run { p.err = "no-session"; return@withContext p }
        try {
            val t0 = System.nanoTime()
            p.status = handle.status
            // `url` is the constructor parameter and is not reassigned: the
            // final URL after redirects lives on the handle, and copying it
            // back onto the probe made this a compile error ("val cannot be
            // reassigned") that the structural lint never saw.
            p.ttfbMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
            if (handle.status !in listOf(200, 206)) {
                p.err = "http-${handle.status}"
                return@withContext p
            }
            val source = handle.source ?: return@withContext p
            val firstBuf = Buffer()
            source.read(firstBuf, 4096)
            val first = firstBuf.readByteArray()
            p.kind = kind(handle.headers["Content-Type"].orEmpty(), first)
            if (p.kind == "page") return@withContext p
            var got = first.size
            val stamps = mutableListOf<Long>()
            var stalls = 0
            val deadline = System.nanoTime() + 12_000_000_000L
            while (got < ServerFinder.PROBE_BYTES && System.nanoTime() < deadline) {
                val c0 = System.nanoTime()
                val buf = Buffer()
                val n = source.read(buf, ServerFinder.CHUNK.toLong())
                if (n == -1L) break
                val elapsed = (System.nanoTime() - c0) / 1_000_000
                stamps += elapsed
                if (elapsed > 2500) stalls += 1
                got += n.toInt()
            }
            val span = maxOf((System.nanoTime() - t0) / 1_000_000_000.0, 0.05)
            p.kbps = ((got / 1024.0) / span).toInt()
            p.stalls = stalls
            if (stamps.size > 1) {
                val mean = stamps.average()
                p.jitterMs = stamps.map { abs(it - mean) }.average().toInt()
            }
            if (ServerFinder.SIG.containsMatchIn(url)) p.signature = "signed"
            if (p.kind == "hls") {
                deepen(p, url, referer)
                p.poisoned = hexagate(url, referer)
            }
            p.bufferScore = FinderHelpers.bufferScore(p.ttfbMs, p.stalls, p.jitterMs, p.kbps)
            p
        } catch (e: Exception) {
            p.err = e.javaClass.simpleName
            p
        } finally {
            handle.close()
        }
    }

    /** Follow the master playlist one level and measure a real segment. */
    private suspend fun deepen(p: ServerProbe, url: String, referer: String?) {
        val r = http.get(url, referer = referer, read = 200_000)
        if (r == null || r.status >= 400) { p.poisoned = true; return }
        val variants = r.text.lines().map { it.trim() }.filter { it.endsWith(".m3u8") }
        var target: String? = if (variants.isNotEmpty()) FinderHelpers.urljoin(url, variants.last()) else null
        if (target == null) {
            val segs = r.text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (segs.isEmpty()) { p.poisoned = true; return }
            target = FinderHelpers.urljoin(url, segs[0])
        }
        val seg = http.get(target, referer = url, read = 4096)
        if (seg == null || seg.status >= 400 || seg.body.size < 1024) p.poisoned = true
    }

    /** True when the m3u8 is a fake gate: no segment list at all. */
    private suspend fun hexagate(url: String, referer: String?): Boolean {
        val r = http.get(url, referer = referer, read = 8192)
        if (r == null || r.status >= 400) return false
        val body = r.text
        return "#EXTINF" !in body && ".ts" !in body && ".m4s" !in body
    }
}

/** Catalogue size estimate: sitemap first, API and pagination as fallback. Python: `Catalogue`. */
class ServerCatalogue(private val http: FinderHttp) {

    suspend fun sitemapCount(base: String): Int {
        var total = 0
        val root = http.get("$base/sitemap.xml", read = 200_000)
        if (root == null || root.status >= 400) return 0
        val maps = if ("<sitemapindex" in root.text.lowercase()) ServerFinder.LOC.findAll(root.text).map { it.groupValues[1] }.toList()
        else listOf("$base/sitemap.xml")
        for (sm in maps.take(25)) {
            if (!sm.endsWith(".xml") && !sm.endsWith(".xml.gz")) continue
            val r = http.get(sm, read = 400_000)
            if (r != null && r.status < 400) total += ServerFinder.LOC.findAll(r.text).count()
            if (total >= ServerFinder.MAX_CATALOGUE) break
        }
        return minOf(total, ServerFinder.MAX_CATALOGUE)
    }

    /** How many times [needle] occurs in [text]; 0 for an empty needle. */
    private fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            count++
            at = text.indexOf(needle, at + needle.length)
        }
        return count
    }

    suspend fun apiCount(base: String): Int {
        var total = 0
        for (path in listOf("/api/anime/search?keyword=a&page={}", "/api/v2/anime/search?keyword=a&page={}", "/api/v1/anime/search?keyword=a&page={}")) {
            for (page in 1..5) {
                // `String.count(String)` does not exist in Kotlin -- it resolves
                // to `count(predicate: (Char) -> Boolean)`, which is why the CI
                // build failed with "Argument type mismatch" on all three calls.
                // Occurrences of a substring are counted explicitly.
                val r = http.get(base + path.replace("{}", page.toString()), read = 200_000)
                if (r == null || r.status >= 400) break
                val hits = occurrences(r.text, "\"animeId\"") +
                    occurrences(r.text, "\"slug\"") +
                    occurrences(r.text, "\"id\":")
                if (hits == 0) break
                total += hits
                if (hits < 10) break
            }
            if (total > 0) break
        }
        return minOf(total, ServerFinder.MAX_CATALOGUE)
    }

    suspend fun pageCount(base: String): Int {
        var total = 0
        for (path in listOf("/search?q=a&page={}", "/browse?page={}", "/?s=a&paged={}", "/trending?page={}")) {
            for (page in 1..5) {
                // `String.format(page)` in Kotlin is the Java static taking a
                // format and args; with an Int argument it does not compile as
                // intended. The placeholder is substituted explicitly.
                val r = http.get(base + path.replace("{}", page.toString()), read = 200_000)
                if (r == null || r.status >= 400) break
                val hits = Regex("href\\s*=\\s*[\"'][^\"']*/(?:watch|anime|series|tv|movie)/").findAll(r.text).count()
                total += hits
                if (hits < 5) break
            }
            if (total > 0) break
        }
        return minOf(total, ServerFinder.MAX_CATALOGUE)
    }

    suspend fun count(base: String): Int {
        var n = sitemapCount(base)
        if (n < 400) n = maxOf(n, apiCount(base))
        if (n < 120) n = maxOf(n, pageCount(base))
        return n
    }
}

/** One validated server. Python: the `rec` dict in `Harvester.validate`. */
@Serializable
data class ServerRecord(
    val id: String,
    val name: String = "",
    val provider: String = "",
    val host: String = "",
    val baseUrl: String = "",
    val kind: String = "none",
    val sampleUrl: String = "",
    val referer: String = "",
    val quality: String = "unknown",
    val qualityTier: Int = 0,
    val catalogue: Int = 0,
    val ttfbMs: Int = 0,
    val kbps: Int = 0,
    val stalls: Int = 0,
    val bufferScore: Int = 0,
    val signature: String = "none",
    val status: String = "alive",
    val score: Double = 0.0,
    val failures: Int = 0,
    val reason: String = "",
    val firstSeen: String = "",
    val lastChecked: String = "",
    val deadAt: String = ""
) {
    /**
     * The website this record's media was found on.
     *
     * [baseUrl] is the *media origin* (`origin_of(cand.url)`), and that is what
     * the Python pool stores -- it is a catalogue of playable endpoints, and
     * `sample_url` is what the checker re-probes. It is deliberately **not**
     * what `WebsitePool` wants: that pool feeds `WebsiteFinder.findStreams`,
     * which opens the address as a homepage, reads its search form and matches
     * a title. Handing it an embed host like `https://vidcloud.example` stored
     * entries that could never answer for a title, so the website finder
     * reported "no new websites" while the server pool filled up.
     *
     * The referer is the page the player was embedded in, so its origin is the
     * actual streaming site: that is the address worth searching. Fall back to
     * the media origin only when the record has no referer.
     */
    fun toSiteUrl(): String {
        val ref = referer.trim()
        val fromRef = if (ref.isNotEmpty()) FinderHelpers.originOf(ref) else ""
        return fromRef.ifEmpty { baseUrl }
    }

    fun toSavedWebsite(): SavedWebsite = SavedWebsite(url = toSiteUrl(), name = provider, templates = emptyList())
}

@Serializable
private data class ServerStoreFile(
    val servers: List<ServerRecord> = emptyList(),
    val frontier: List<String> = emptyList(),
    /** Frontier entries already crawled; never re-queued in a later round. */
    val tried: List<String> = emptyList()
)

/**
 * SQLite pool + frontier, persisted as JSON through [PoolStore].
 * Only the ranked head ever reaches the JSON pool file.
 * Python: `Store` (servers.db).
 */
class ServerFinderStore(private val store: PoolStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var servers: MutableMap<String, ServerRecord> = LinkedHashMap()
    private var frontier: MutableList<String> = mutableListOf()
    private val tried: MutableSet<String> = LinkedHashSet()

    init {
        runCatching {
            store.read()?.let { json.decodeFromString<ServerStoreFile>(it) }?.let { file ->
                file.servers.forEach { servers[it.id] = it }
                frontier += file.frontier
                tried += file.tried
            }
        }
    }

    private fun save() {
        runCatching {
            store.write(
                json.encodeToString(
                    ServerStoreFile.serializer(),
                    ServerStoreFile(servers.values.toList(), frontier.toList(), tried.toList())
                )
            )
        }
    }

    fun alive(): List<ServerRecord> = synchronized(lock) {
        servers.values.filter { it.status != "dead" }.sortedByDescending { it.score }
    }

    fun count(status: String? = null): Int = synchronized(lock) {
        if (status == null) servers.size else servers.values.count { it.status == status }
    }

    fun frontierSize(): Int = synchronized(lock) { frontier.size }

    fun upsert(rec: ServerRecord, status: String) {
        synchronized(lock) {
            servers[rec.id] = rec.copy(status = status)
            save()
        }
    }

    fun markDead(sid: String) {
        synchronized(lock) {
            servers[sid]?.let { servers[sid] = it.copy(status = "dead") }
            save()
        }
    }

    fun push(url: String): Boolean = synchronized(lock) {
        val n = FinderHelpers.norm(url)
        if (n.isEmpty() || n in frontier) return false
        // An index query stays retryable. `tried` is permanent, and a surface
        // that returned 0 once (engine down, 403, bad query shape) must be
        // asked again next round -- otherwise the very first pass disables
        // discovery for the life of the install, which is what
        // `discovery round 0 -> 0 surface url(s)` was.
        if (n in tried && !Discovery.isSurface(url)) return false
        frontier += n
        save()
        true
    }

    /** Record that [url] was crawled, so a refill never hands it out again. */
    fun markTried(url: String) = synchronized(lock) {
        if (tried.add(FinderHelpers.norm(url))) {
            if (tried.size > MAX_TRIED) {
                tried.removeAll(tried.take(tried.size - MAX_TRIED).toSet())
            }
            save()
        }
    }

    fun triedSize(): Int = synchronized(lock) { tried.size }

    fun pop(n: Int): List<String> = synchronized(lock) {
        val out = frontier.take(n)
        frontier = frontier.drop(out.size).toMutableList()
        // Whatever is handed out counts as tried, so a later refill cannot
        // resurrect it. Index queries are the exception: they are re-askable
        // by design (see `push`). The set is bounded so a long crawl cannot
        // grow the store file without limit.
        out.forEach { if (!Discovery.isSurface(it)) tried += it }
        if (tried.size > MAX_TRIED) {
            tried.removeAll(tried.take(tried.size - MAX_TRIED).toSet())
        }
        save()
        out
    }

    fun knownHost(host: String): Boolean = synchronized(lock) {
        servers.values.any { it.host == host }
    }

    /** Lowest-scoring live records: the slice most likely to have died. */
    fun weak(n: Int): List<ServerRecord> = synchronized(lock) {
        servers.values.filter { it.status != "dead" }.sortedBy { it.score }.take(n)
    }

    private companion object {
        /** Ceiling on the persisted tried-set so the store file stays small. */
        const val MAX_TRIED = 60_000
    }

    /** servers.py `write_pool`: {schema, generated_at, cap, count, servers[]}. */
    fun writePool(cap: Int): String {
        val rows = alive().take(cap)
        return """{"schema":1,"generated_at":"${FinderHelpers.now()}","cap":$cap,"count":${rows.size},"servers":${json.encodeToString(rows)}}"""
    }
}

/** One harvest round, for the WebsitePool plug point. */
data class ServerHarvest(
    val sites: List<SavedWebsite>,
    val more: Boolean,
    val sources: List<FinderSourceReport>,
    /**
     * True when this result is a *skip*: another harvest held the drainer, so
     * nothing was crawled and [sites] is just the current pool.
     *
     * Without this flag a skip was indistinguishable from a productive round
     * that happened to add nothing. `fillWebsitePool` only pauses when the
     * pool grew and only returns when `!more`, so a skip -- which reports
     * `more = true` because the pool is under cap -- matched neither branch
     * and looped with no delay at all. That is the `harvest#3945996 SKIP
     * (already running)` flood: millions of iterations inside one second, each
     * one bumping the round counter, which is why the round numbers looked
     * like days of uptime.
     */
    val busy: Boolean = false
)

/**
 * Crawl -> extract -> probe -> count -> rank. Stops hard at the cap.
 * Python: `Harvester` in servers.py.
 */
class ServerFinderHarvester(
    private val store: ServerFinderStore,
    private val http: FinderHttp,
    private val browser: FinderBrowser? = null,
    val cap: Int = ServerFinder.CAP,
    /**
     * Used to turn a discovered host into a searchable site (search template,
     * real name) before it reaches `WebsitePool`. Optional so the harvester
     * still works -- and still matches the Python -- without it; the Python
     * recorded `base_url` and left searching to whoever consumed the pool.
     */
    private val profiler: (suspend (String) -> SavedWebsite)? = null
) {
    private val extractor = ServerExtractor()
    val prober = ServerProber(http)
    private val catalogue = ServerCatalogue(http)
    private var blocked: Set<String> = emptySet()

    /**
     * Hosts proven to be parked pages, stubs or mocks.
     *
     * A decoy is rediscovered every round -- the brand sweep and the TLD sweep
     * both hand back the same parked domains -- so recognising it once and
     * remembering it is the only way the budget stops being spent on it. This
     * is per-harvester and in memory: it is a cheap cache, not a claim about
     * the future, and a process restart is allowed to try again.
     */
    private val decoys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    var written: Int = 0
        private set

    /**
     * Brand stems learned from every host discovery has produced (`9anime`,
     * `aniwave`, `hianime`). A pirate brand rotates its TLD constantly -- the
     * old domain dies, the same brand reappears on `.rs`, `.to`, `.is` -- so a
     * brand is the most productive seed there is: it lets [tldSweep] ask for
     * domains that no index has published yet.
     */
    private val brands = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Discovery round currently in flight. [Discovery.domainSurfaces] rotates
     * which index it asks (crt.sh, urlscan, Wayback, Cert Spotter) by round,
     * so [discover] needs the round outside [refillSeeds].
     */
    @Volatile private var round = 0

    /**
     * Only one harvest may drain the frontier at a time.
     *
     * The UI and the background checker both call [harvest]; without this guard
     * two workers pop from the same queue, halve each other's batches and log
     * `harvest#0 START` twice, which is what the device trace showed. The
     * second caller now returns the current pool instead of racing.
     */
    private val harvesting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** When the current drain took the flag; 0 when nobody holds it. */
    private val harvestStartedAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Whether a drain is in progress.
     *
     * Exposed so a caller can avoid asking for a round it knows will be
     * skipped. The skip itself is still correct without this -- [harvest]
     * re-checks -- but polling a busy drainer produced a pointless log pair on
     * every tick.
     */
    val busy: Boolean get() = harvesting.get()

    /**
     * Shell renders spent this round.
     *
     * A page whose player is built in script has to be rendered, but the
     * WebView serialises: rendering every watch page in a frontier of 110 would
     * put every other request behind the browser and the harvest would stall
     * exactly as it did before. The budget bounds that without disabling the
     * path, and it resets each round so a later round can try different pages.
     */
    private val shellRenders = java.util.concurrent.atomic.AtomicInteger(0)

    /** Per-harvest drop tallies; read by [rejectionReport]. */
    private val rejects = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun note(reason: String) {
        rejects.merge(reason, 1, Int::plus)
    }

    fun rejectionReport(): ServerRejections = ServerRejections(rejects.toMap())
    fun resetRejections() = rejects.clear()

    fun loadBlocklist(hosts: Set<String>) {
        blocked = hosts
    }

    /**
     * Replay the page's own JSON calls and return what they said.
     *
     * The player addresses on these sites are never in the document: the page
     * asks an endpoint for a server list, then asks again for the chosen
     * server's sources, and only that response holds the `.m3u8`. The WebView
     * network log ([net]) is the list of calls the page made, so fetching them
     * ourselves reproduces exactly what the browser saw -- without needing to
     * drive the player UI.
     *
     * Two shapes are handled:
     *  - a response that already contains media URLs (`sources`, `file`,
     *    `url`) -- appended as-is;
     *  - a response that is a *server list* (`data-id="123"` items) -- each id
     *    is followed to `/ajax/v2/episode/sources?id=123`, which is where
     *    aniwatch/hianime/zoro put the stream.
     */
    private suspend fun followApis(url: String, net: List<Pair<String, String>>): String {
        if (net.isEmpty()) return ""
        val origin = FinderHelpers.originOf(url)
        val siteHost = FinderHelpers.hostOf(url)
        val bodies = StringBuilder()
        val calls = net.map { it.second }
            .filter { ServerFinder.API_HINT.containsMatchIn(it) }
            .filterNot { ServerFinder.isAdHost(FinderHelpers.hostOf(it)) }
            .filterNot { ServerFinder.isJunk(it) }
            .distinct()
            .take(ServerFinder.MAX_API_CALLS)
        var followed = 0
        for (link in calls) {
            val host = FinderHelpers.hostOf(link)
            // Same-site APIs and the site's own api.* mirror are the ones that
            // answer with sources; a third-party host is usually a tracker.
            if (host != siteHost && !host.endsWith(".$siteHost") && !siteHost.endsWith(".$host")) continue
            val r = http.get(link, referer = url, read = 400_000) ?: continue
            if (r.status >= 400) continue
            val body = r.text
            bodies.append('\n').append(body)
            FinderTrace.count("api.body")
            // A server list: each `data-id` is one playable server.
            val ids = ServerFinder.DATA_ID.findAll(body).map { it.groupValues[1] }.distinct().take(ServerFinder.MAX_SERVERS)
            for (id in ids) {
                if (followed >= ServerFinder.MAX_SERVERS) break
                followed++
                val src = "$origin/ajax/v2/episode/sources?id=$id"
                val sr = http.get(src, referer = url, read = 200_000) ?: continue
                if (sr.status >= 400) continue
                bodies.append('\n').append(sr.text)
                FinderTrace.count("api.sources")
            }
            // Some builds answer with `{sources:[{url:...}]}` directly.
            for (m in ServerFinder.MEDIA_IN_JSON.findAll(body)) {
                bodies.append('\n').append(m.groupValues[1].replace("\\/", "/"))
            }
        }
        if (bodies.isNotEmpty()) FinderTrace.log("web", "  api replay $url -> ${bodies.length}B")
        return bodies.toString()
    }

    suspend fun discover(url: String) {
        // Cheap first: a CRL dump is mostly dead hostnames, and a dead hostname
        // must cost a DNS lookup, not a WebView. This cascade is the whole
        // reason 211 discovered domains can be tried without the harvest
        // stalling: DNS -> TCP -> one small GET -> shape, and only a host that
        // survives all four is allowed to reach the renderer.
        if (!cheapAlive(url)) return
        http.prime(url)
        var html = ""
        var net: List<Pair<String, String>> = emptyList()
        // Render only when the cheap GET could not produce a document. A page
        // that already answered with real HTML needs no WebView at all, which
        // is what stops one instance serialising the whole fleet.
        val r0 = http.get(url, read = 400_000)
        html = r0?.text.orEmpty()
        // A JSON API is never rendered. reddit's `search.json`, urlscan's API
        // and Common Crawl all answer with machine text; handing them to a
        // WebView cost ~2 s each and, because one WebView serialises, put every
        // other request behind it. The document is already the answer.
        val json = Discovery.isJsonSurface(url)
        // A shell page is rendered only while this round's budget lasts.
        val shell = !json && ServerFinder.needsRender(html)
        val overBudget = shell && shellRenders.getAndIncrement() >= MAX_SHELL_RENDERS
        if (json) {
            if (html.isEmpty()) {
                note("json-empty")
                return
            }
            FinderTrace.log("web", "json ok $url (${html.length}B)")
            FinderTrace.count("json.ok")
        } else if (html.length >= 2000 && !ServerFinder.looksChallenged(html) &&
            (!shell || overBudget)
        ) {
            if (shell) {
                FinderTrace.log("web", "  shell budget spent, reading $url statically")
                FinderTrace.count("render.budget")
            }
            FinderTrace.log("web", "http ok $url (${html.length}B)")
            FinderTrace.count("http.ok")
        } else {
            // Rendered either because the cheap GET produced no document, or
            // because the document builds its player in script and therefore
            // cannot be read statically. The second case is why every real
            // target used to yield nothing: nine homepages measured, zero raw
            // extractor matches, because the player only exists after the
            // page's own JavaScript runs.
            if (shell) {
                FinderTrace.log("web", "player shell $url (${html.length}B) - rendering")
                FinderTrace.count("render.shell")
            }
            val rendered = if (browser != null) {
                val (h, n) = browser.render(url)
                net = n
                if (h.isNotEmpty()) html = h
                h.isNotEmpty()
            } else false
            if (rendered) {
                FinderTrace.log("web", "render OK $url (${html.length}B, net=${net.size})")
                FinderTrace.count("render.ok")
            } else {
                FinderTrace.log("web", "render EMPTY $url")
                FinderTrace.count("render.empty")
            }
        }
        // A page that never came back cannot yield anything, and that is the
        // single most useful thing to know when the pool is empty.
        if (html.isEmpty()) {
            note("unreachable")
            FinderTrace.log("web", "UNREACHABLE $url")
            return
        }
        if (ServerFinder.looksChallenged(html)) {
            note("challenge")
            FinderTrace.log("web", "CHALLENGED $url (${html.length}B) - bot wall, not a catalogue")
            return
        }
        // A parked page, a stub or a mock has the shape of a site and can never
        // serve a stream. `aniwaves.org` is the measured example: a real watch
        // page, a real player iframe, and an API that answers
        // `{"info":"Server 1 is not wired yet","mode":"mock"}`. Remembering the
        // host is what stops the same decoy being rediscovered and re-probed
        // every round.
        if (ServerFinder.isDecoy(html) && !Discovery.isJsonSurface(url)) {
            val h = FinderHelpers.hostOf(url)
            if (h.isNotEmpty()) decoys += h
            note("decoy")
            FinderTrace.count("decoy")
            FinderTrace.log("web", "DECOY $url (${html.length}B) - parked, stub or mock; host remembered")
            return
        }
        // A live domain is worth more than the page it served. crt.sh, urlscan,
        // Wayback and Cert Spotter all require a *real domain* and refuse a
        // bare brand (`not_allowed_by_plan`, 403), which is why the brand tier
        // above can only use Bing and RapidDNS. Once discovery has actually
        // reached a host, ask the domain tier for its siblings and its history:
        // that is how one working site turns into its mirrors and its
        // certificate neighbours without a seed list.
        val liveHost = FinderHelpers.hostOf(url)
        if (liveHost.isNotEmpty()) {
            var extra = 0
            for (s in Discovery.domainSurfaces(liveHost, round)) if (store.push(s)) extra++
            if (extra > 0) {
                FinderTrace.log("web", "  domain tier $liveHost -> $extra surface url(s)")
                FinderTrace.count("discovery.domain", extra)
            }
        }
        // The player lives behind the page's JSON calls, not in its DOM.
        val api = followApis(url, net)
        if (api.isNotEmpty()) html += api
        for (link in Regex("href\\s*=\\s*[\"']([^\"'#]+)[\"']").findAll(html).map { it.groupValues[1] }) {
            val absUrl = FinderHelpers.norm(FinderHelpers.urljoin(url, unescape(link)))
            if (FinderHelpers.hostOf(absUrl) == FinderHelpers.hostOf(url) &&
                Regex("/(watch|anime|series|tv|movie|episode|browse|page)/|page=\\d+").containsMatchIn(absUrl)
            ) {
                store.push(absUrl)
            }
        }
        // A homepage rarely holds a player, and a site's own links are how the
        // frontier grows without a seed list.
        expandSitemap(url)
        expandHosts(html, url)
        // Remember the brand so the next refill can sweep its other TLDs.
        Discovery.brandOf(FinderHelpers.hostOf(url))?.let { brands += it }
        val extracted = extractor.extract(html, url)
        if (extracted.isEmpty()) note("no-embeds")
        FinderTrace.log("web", "extract $url -> ${extracted.size} candidate(s)")
        FinderTrace.count("extracted", extracted.size)
        for (cand in extracted.values) {
            if (cand.host in blocked) {
                note("blocked")
                FinderTrace.log("web", "  drop blocked ${cand.url}")
                continue
            }
            // An ad or tracker is never a source. This is belt-and-braces with
            // the extractor filter: anything that reaches here is not a player.
            if (ServerFinder.isAdHost(cand.host)) {
                note("ad")
                FinderTrace.log("web", "  drop ad ${cand.host}")
                continue
            }
            // Only a *document* embed can be a website. `xp.html` in a
            // third-party ad script is neither a site nor playable media.
            if (!cand.url.startsWith("http")) {
                note("not-a-url")
                continue
            }
            if (store.knownHost(cand.host)) {
                FinderTrace.log("web", "  skip known host ${cand.host}")
                continue
            }
            if (store.count("alive") >= cap) return
            validate(cand)
        }
    }

    /**
     * The cheap-first gate: DNS, then TCP, then a small GET, then shape.
     *
     * Certificate transparency is a list of *names*, and most names in it are
     * dead: expired, parked, or never had a web server. Feeding those straight
     * into the probe is why `discovery.ct=211` produced `alive=0` -- 211
     * expensive probes, all dead. Each stage here is orders of magnitude
     * cheaper than the next, so a dead host dies at the cheapest one that can
     * prove it.
     */
    private suspend fun cheapAlive(url: String): Boolean {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty()) return false
        // Already proven to be a decoy in this process: do not even spend a DNS
        // lookup on it again.
        if (host in decoys) {
            note("decoy-known")
            return false
        }
        if (!dnsOk(host)) {
            note("dns-dead")
            FinderTrace.count("cheap.dns")
            return false
        }
        FinderTrace.count("cheap.dns.ok")
        if (!tcpOk(host)) {
            note("tcp-dead")
            FinderTrace.count("cheap.tcp")
            return false
        }
        FinderTrace.count("cheap.tcp.ok")
        // One small GET, no renderer: if the host answers with a document or a
        // JSON body it is worth the rest of the cascade.
        val r = http.get(url, read = 24_000, tries = 1, timeoutMs = 8_000L)
        if (r == null || r.status == 0) {
            note("http-dead")
            FinderTrace.count("cheap.http")
            return false
        }
        if (r.status >= 400) {
            note("http-${r.status}")
            FinderTrace.count("cheap.http")
            return false
        }
        FinderTrace.count("cheap.http.ok")
        return true
    }

    /** DNS with a hard timeout, so one black-holed resolver cannot stall a batch. */
    private suspend fun dnsOk(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val job = java.util.concurrent.FutureTask {
                java.net.InetAddress.getAllByName(host).isNotEmpty()
            }
            Thread(job).apply { isDaemon = true }.start()
            job.get(3_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
    }

    /** TCP 443 (then 80) with a short timeout. */
    private suspend fun tcpOk(host: String): Boolean = withContext(Dispatchers.IO) {
        for (port in listOf(443, 80)) {
            val ok = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), 3_500)
                }
                true
            }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    suspend fun validate(cand: ServerCandidate) {
        val probe = prober.probe(cand.url, cand.referer)
        FinderTrace.log(
            "web",
            "  probe ${cand.url} status=${probe.status} kind=${probe.kind} " +
                "buf=${probe.bufferScore} alive=${probe.alive} err=${probe.err.ifEmpty { "-" }}"
        )
        FinderTrace.count("probe.${if (probe.alive) "alive" else "dead"}")
        // A page that answered is not a failure: it is a *website*, which is
        // exactly what this pool stores. It has no playable media by itself --
        // `WebsiteFinder` searches it later -- so it cannot be a server, but
        // rejecting it threw away every real site the crawl touched. Callers
        // that need playable media filter on `kind`; see `toSavedWebsite`.
        if (probe.kind == "page" && probe.status in listOf(200, 206) && !probe.poisoned) {
            note("page-not-media")
            // `base` is declared below (after the alive gate); this branch runs
            // before it, so the origin is derived here.
            FinderTrace.log(
                "web",
                "  page ok ${FinderHelpers.originOf(cand.url)} (not media; kept as a site candidate)"
            )
            return
        }
        if (!probe.alive) {
            // Distinguishes "the host is down" from "the host answered but
            // served something unusable".
            note(if (probe.status in listOf(200, 206)) "page-not-media" else "probe-failed")
            return
        }
        val base = FinderHelpers.originOf(cand.url)
        // Catalogue size belongs to the *site*, not to the media CDN.
        //
        // `cand.url` is an embed on a player host (`vidcloud.example/embed/x`);
        // its origin has no sitemap and no catalogue, so counting there always
        // returned 0 and the `< 40` gate then rejected every real player that
        // had survived the probe. The referer is the page the player was
        // embedded in, i.e. the streaming site whose catalogue actually
        // matters. Measure there first, fall back to the media origin.
        val site = FinderHelpers.originOf(cand.referer)
        var catalogueCount = if (site.isNotEmpty() && site != base) catalogue.count(site) else 0
        if (catalogueCount < 40) catalogueCount = maxOf(catalogueCount, catalogue.count(base))
        FinderTrace.log("web", "  catalogue ${site.ifEmpty { base }} -> $catalogueCount")
        // Playable media is the point of this pool. The catalogue gate used to
        // drop it outright, which is how a page with a working stream could
        // still contribute nothing. A proven player is kept and simply ranked
        // by its (possibly small) catalogue; only a host that serves neither
        // media nor a catalogue is dropped.
        if (catalogueCount < 40) {
            note("catalogue-thin")
            FinderTrace.log("web", "  thin catalogue $base ($catalogueCount) - kept, ranked low")
        }
        val (label, tier) = FinderHelpers.qualityOf(cand.url + " " + cand.provider)
        val now = FinderHelpers.now()
        val rec = ServerRecord(
            id = FinderHelpers.sha1_16(cand.url),
            name = cand.provider,
            provider = cand.provider,
            host = cand.host,
            baseUrl = base,
            kind = probe.kind,
            sampleUrl = probe.url,
            referer = cand.referer,
            quality = if (label != "unknown") label else probe.quality,
            qualityTier = maxOf(tier, probe.qualityTier),
            catalogue = catalogueCount,
            ttfbMs = probe.ttfbMs,
            kbps = probe.kbps,
            stalls = probe.stalls,
            bufferScore = probe.bufferScore,
            signature = probe.signature,
            score = ServerFinder.scoreOf(catalogueCount, maxOf(tier, probe.qualityTier), probe.bufferScore, probe.ttfbMs),
            failures = 0,
            firstSeen = now,
            lastChecked = now
        )
        store.upsert(rec, "alive")
        written += 1
        FinderTrace.log("web", "ACCEPT ${rec.host} kind=${rec.kind} cat=$catalogueCount")
        FinderTrace.count("accepted")
    }

    /**
     * Refill the frontier from generated discovery, not from a fixed list.
     *
     * Each round pushes a different slice of [Discovery.webSurfaces]: search
     * queries built from viewer intents, certificate-transparency and urlscan
     * lookups for streaming keywords, and the Common Crawl index. The frontier
     * is persisted and de-duplicated against everything already crawled
     * ([ServerFinderStore.markTried]), so a refill can only ever add ground
     * this device has not covered.
     */
    fun refillSeeds(round: Int = 0) {
        this.round = round
        var added = 0
        for (u in Discovery.webSurfaces(round)) if (store.push(u)) added++
        // The corpus feeds the next round: every brand discovered so far is
        // asked for its other TLDs. Without this the frontier only ever holds
        // what the *first* query set produced and starves after one hop.
        added += sweepBrands(round)
        FinderTrace.log("web", "  discovery round $round -> $added surface url(s)")
        FinderTrace.count("discovery.surface", added)
    }

    suspend fun run() {
        var round = 0
        while (store.count("alive") < cap) {
            var batch = store.pop(64)
            if (batch.isEmpty()) {
                refillSeeds(round++)
                batch = store.pop(32)
                if (batch.isEmpty()) break
            }
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
            delay(200)
        }
    }

    /** Route one frontier entry: discovery surfaces first, then ordinary pages. */
    suspend fun process(url: String) {
        val host = FinderHelpers.hostOf(url)
        when {
            host == "crt.sh" || host.endsWith(".crt.sh") -> fromCt(url)
            host == "api.certspotter.com" -> fromCertSpotter(url)
            host == "web.archive.org" -> fromWayback(url)
            host == "rapiddns.io" || host.endsWith(".rapiddns.io") -> fromRapidDns(url)
            host == "otx.alienvault.com" -> fromOtx(url)
            host == "urlscan.io" || host.endsWith(".urlscan.io") -> fromUrlscan(url)
            host == "commoncrawl.org" || host.endsWith(".commoncrawl.org") -> fromCommonCrawl(url)
            Discovery.isSearchHost(host) -> fromSearch(url)
            else -> discover(url)
        }
    }

    // -- discovery surfaces -------------------------------------------------

    /**
     * Queue the origins named by a passive index.
     *
     * These engines (certificate transparency, Wayback, RapidDNS, OTX) return
     * hostnames with no evidence of being alive: crt.sh alone hands back
     * decade-old parking domains. They are queued anyway, because the cheap
     * cascade in [cheapAlive] kills a dead hostname for the price of one DNS
     * lookup -- far cheaper than the media probe that used to swallow them.
     * Filtering here on the host *name* was what made `alive=0`: a brand that
     * hides its domain from search engines still has a certificate.
     */
    private fun queueHosts(hosts: Collection<String>, tag: String): Int {
        var added = 0
        for (host in hosts) {
            if (!Discovery.usableHost(host)) continue
            if (store.push("https://$host/")) added++
            Discovery.brandOf(host)?.let { brands += it }
        }
        FinderTrace.log("web", "  $tag -> $added domain(s)")
        FinderTrace.count("discovery.$tag", added)
        return added
    }

    /** Read a search-results page and queue the streaming hosts it names. */
    private suspend fun fromSearch(url: String) {
        val r = http.get(url, read = 500_000) ?: return
        if (r.status >= 400) return
        var added = 0
        var seen = 0
        // Search results are already relevant to the query, so the host does
        // not have to spell "anime" in its name to be worth a look. Requiring
        // that (the old behaviour) discarded most real results even after the
        // redirect was unwrapped.
        for (link in Discovery.linksFromHtml(r.text, url)) {
            seen++
            if (added >= ServerFinder.MAX_GRAPH_HOSTS * 8) break
            val host = FinderHelpers.hostOf(link)
            if (!Discovery.usableHost(host)) continue
            val origin = FinderHelpers.originOf(link) + "/"
            if (store.push(origin)) {
                added++
                Discovery.brandOf(host)?.let { brands += it }
            }
        }
        FinderTrace.log("web", "  search -> $added host(s) of $seen link(s) $url")
        FinderTrace.count("discovery.host", added)
        FinderTrace.count("discovery.search.links", seen)
    }

    /** Certificate transparency: every domain issued a cert for a keyword. */
    private suspend fun fromCt(url: String) {
        val r = http.get(url, read = 2_000_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromCt(r.text), "ct")
    }

    /** Cert Spotter: the same idea as crt.sh, a different certificate log. */
    private suspend fun fromCertSpotter(url: String) {
        val r = http.get(url, read = 2_000_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromCertSpotter(r.text), "certspotter")
    }

    /** Wayback CDX: every URL ever archived under a keyword's domains. */
    private suspend fun fromWayback(url: String) {
        val r = http.get(url, read = 3_000_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromWayback(r.text), "wayback")
    }

    /** RapidDNS: passive DNS records, i.e. domains that actually resolved. */
    private suspend fun fromRapidDns(url: String) {
        val r = http.get(url, read = 1_500_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromRapidDns(r.text), "rapiddns")
    }

    /** AlienVault OTX passive DNS: the same, from a second vantage point. */
    private suspend fun fromOtx(url: String) {
        val r = http.get(url, read = 1_500_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromOtx(r.text), "otx")
    }

    private suspend fun fromUrlscan(url: String) {
        val r = http.get(url, read = 2_000_000) ?: return
        if (r.status >= 400) return
        queueHosts(Discovery.hostsFromUrlscan(r.text), "urlscan")
    }

    /**
     * Common Crawl. The index list is read once per round to learn the newest
     * collection, then each keyword is queried against it.
     */
    private suspend fun fromCommonCrawl(url: String) {
        val r = http.get(url, read = 1_500_000) ?: return
        if (r.status >= 400) return
        if ("collinfo" in url) {
            val index = Discovery.latestCommonCrawlIndex(r.text)
            if (index.isEmpty()) return
            var pushed = 0
            for (kw in Discovery.webKeywords(0)) {
                for (u in Discovery.commonCrawlUrls(index, kw)) if (store.push(u)) pushed++
            }
            FinderTrace.log("web", "  common crawl index $index -> $pushed query url(s)")
            return
        }
        var added = 0
        for (u in Discovery.urlsFromCommonCrawl(r.text)) {
            val host = FinderHelpers.hostOf(u)
            if (!Discovery.usableHost(host)) continue
            if (store.push(FinderHelpers.originOf(u) + "/")) added++
            Discovery.brandOf(host)?.let { brands += it }
        }
        FinderTrace.log("web", "  common crawl -> $added domain(s)")
        FinderTrace.count("discovery.cc", added)
    }

    /**
     * TLD sweep: the brand's domain on every cheap TLD.
     *
     * This is the step that keeps the graph growing instead of starving. A
     * brand found on `.to` almost always also owns `.rs`, `.is` and `.se`; the
     * sweep asks for all of them, so the frontier is fed by what discovery has
     * *already learned* rather than only by what an index happens to publish.
     */
    private fun sweepBrands(round: Int): Int {
        if (brands.isEmpty()) return 0
        var added = 0
        for (brand in brands.toList().take(64)) {
            for (host in Discovery.tldSweep(brand, round)) {
                if (store.push("https://$host/")) added++
            }
        }
        if (added > 0) {
            FinderTrace.log("web", "  tld sweep round $round brands=${brands.size} -> $added url(s)")
            FinderTrace.count("discovery.tldsweep", added)
        }
        return added
    }

    /**
     * A discovered domain becomes real watch pages through its own sitemap.
     *
     * A homepage almost never embeds a player; episodes live one level down.
     * The sitemap is the site's own index of those pages, so this is how a
     * domain found by a query turns into pages that actually hold embeds --
     * without guessing URL shapes.
     */
    private suspend fun expandSitemap(url: String) {
        val origin = FinderHelpers.originOf(url)
        if (origin.isEmpty()) return
        val r = http.get("$origin/sitemap.xml", read = 400_000) ?: return
        if (r.status >= 400) return
        var added = 0
        val locs = ServerFinder.LOC.findAll(r.text).map { it.groupValues[1] }.toList()
        for (loc in locs) {
            if (added >= ServerFinder.MAX_SITEMAP_URLS) break
            if (!Discovery.watchLike(loc)) continue
            if (store.push(loc)) added++
        }
        if ("<sitemapindex" in r.text.lowercase()) {
            for (child in locs.take(3)) {
                if (added >= ServerFinder.MAX_SITEMAP_URLS) break
                val c = http.get(child, read = 400_000) ?: continue
                if (c.status >= 400) continue
                for (loc in ServerFinder.LOC.findAll(c.text).map { it.groupValues[1] }) {
                    if (added >= ServerFinder.MAX_SITEMAP_URLS) break
                    if (!Discovery.watchLike(loc)) continue
                    if (store.push(loc)) added++
                }
            }
        }
        if (added > 0) FinderTrace.log("web", "  sitemap $origin -> $added watch url(s)")
        FinderTrace.count("discovery.sitemap", added)
    }

    /** Grow the frontier through the link graph: mirrors and sibling sites. */
    private fun expandHosts(html: String, url: String) {
        val self = FinderHelpers.hostOf(url)
        var added = 0
        for (link in Discovery.linksFromHtml(html, url)) {
            if (added >= ServerFinder.MAX_GRAPH_HOSTS) break
            val host = FinderHelpers.hostOf(link)
            if (host.isEmpty() || host == self) continue
            if (!Discovery.usableHost(host)) continue
            // No name filter here: a mirror of a working site does not have to
            // repeat the word "anime" in its hostname, and the cheap cascade
            // will discard it for free if it is dead. This is the link-graph
            // engine, and it is what makes the graph deeper than one hop.
            if (store.push(FinderHelpers.originOf(link) + "/")) {
                added++
                Discovery.brandOf(host)?.let { brands += it }
            }
        }
        if (added > 0) FinderTrace.count("discovery.graph", added)
    }

    /** Bounded refill used by the checker after a sweep. */
    suspend fun runUntilCap(rounds: Int = 4) {
        repeat(rounds) {
            if (store.count("alive") >= cap) return
            var batch = store.pop(48)
            if (batch.isEmpty()) {
                refillSeeds(rounds)
                batch = store.pop(24)
                if (batch.isEmpty()) return
            }
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
        }
    }

    /**
     * Plug point replacing `WebsiteSpider.harvest`.
     *
     * Python's `Harvester.run()` is a `while count('alive') < cap` loop, not a
     * single batch: it pops 64 frontier URLs, discovers them, and keeps going,
     * refilling the seeds when the frontier runs dry. The translation used to
     * do exactly one batch per call, so a single seed homepage -- which yields
     * same-host links but no players -- left the pool empty and the UI reported
     * "No new websites right now" forever.
     *
     * This restores the loop shape. It stays bounded (one call cannot hang the
     * caller) by [harvestBudgetMs], and it never reports `more = false` while
     * the frontier still holds unvisited pages.
     */
    suspend fun harvest(round: Int): ServerHarvest {
        val started = System.currentTimeMillis()
        // One drainer at a time. The UI and the checker both call this; two
        // workers on one frontier split every batch in half and log the same
        // `harvest#0 START` twice (the device trace showed exactly that).
        if (!harvesting.compareAndSet(false, true)) {
            // A harvest that never returns would hold this flag for the life of
            // the process, so every later caller would skip forever and the
            // finder would look permanently stuck. A drain is bounded by
            // [harvestBudgetMs], so anything older than the stall window is a
            // hung coroutine, not a working one: take the flag back and say so.
            val held = harvestStartedAt.get()
            val heldFor = if (held == 0L) 0L else System.currentTimeMillis() - held
            if (held != 0L && heldFor > HARVEST_STALL_MS) {
                FinderTrace.log(
                    "web",
                    "harvest#$round STALL held ${heldFor}ms > ${HARVEST_STALL_MS}ms, taking the drainer back"
                )
                FinderTrace.count("harvest.stall")
                harvesting.set(false)
                harvestStartedAt.set(0)
                if (harvesting.compareAndSet(false, true)) {
                    harvestStartedAt.set(System.currentTimeMillis())
                    try {
                        return harvestLocked(round, started)
                    } finally {
                        harvesting.set(false)
                        harvestStartedAt.set(0)
                    }
                }
            }
            FinderTrace.log("web", "harvest#$round SKIP (already running)")
            FinderTrace.count("harvest.skip")
            return snapshot(0, System.currentTimeMillis() - started).copy(busy = true)
        }
        harvestStartedAt.set(started)
        try {
            return harvestLocked(round, started)
        } finally {
            harvesting.set(false)
            harvestStartedAt.set(0)
        }
    }

    private fun snapshot(seen: Int, elapsed: Long): ServerHarvest =
        ServerHarvest(
            sites = store.alive().take(cap).map { it.toSavedWebsite() }.distinctBy { FinderHelpers.hostOf(it.url) },
            more = store.count("alive") < cap || store.frontierSize() > 0,
            sources = listOf(
                FinderSourceReport(
                    id = "server-finder",
                    status = FinderSourceReport.Status.COMPLETE,
                    seen = seen,
                    checked = seen,
                    candidates = 0,
                    elapsedMs = elapsed
                )
            )
        )

    private suspend fun harvestLocked(round: Int, started: Long): ServerHarvest {
        resetRejections()
        shellRenders.set(0)
        FinderTrace.log(
            "web",
            "harvest#$round START frontier=${store.frontierSize()} alive=${store.count("alive")} cap=$cap"
        )
        var seen = 0
        // `pop()` records every URL it hands out in the persisted tried-set, so
        // a refill can only add ground this device has never covered. That is
        // what makes an unbounded discovery surface terminate instead of
        // re-crawling the same pages until the budget expires.
        var barren = 0
        var refills = 0
        while (System.currentTimeMillis() - started < harvestBudgetMs) {
            // Stop exactly where Python stops: the cap is a hard ceiling.
            if (store.count("alive") >= cap) break
            var batch = store.pop(64)
            if (batch.isEmpty()) {
                // Rotate the query set by round so a barren surface is
                // followed by one that asks different questions.
                refillSeeds(round + refills)
                refills++
                batch = store.pop(32)
                FinderTrace.log("web", "  discovery refill -> ${batch.size} new url(s) tried=${store.triedSize()}")
                if (batch.isEmpty()) break
            }
            seen += batch.size
            val before = store.count("alive") + store.frontierSize()
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
            val after = store.count("alive") + store.frontierSize()
            barren = if (after > before) 0 else barren + 1
            FinderTrace.log(
                "web",
                "  batch=${batch.size} seen=$seen alive=${store.count("alive")} " +
                    "frontier=${store.frontierSize()} barren=$barren"
            )
            // Only give up when there is genuinely nothing left to try.
            // A barren round with a non-empty frontier means the next
            // batch still has ground to cover -- breaking here is what
            // left the frontier at 0 after 18 of 211 urls.
            if (barren >= BARREN_ROUNDS && store.frontierSize() == 0) break
            // Python sleeps 0.2 s between rounds; keep the same courtesy pause
            // so a hostile host is not hammered by a tight loop.
            delay(200)
            // A round that found nothing new and drained the frontier is done.
            if (store.frontierSize() == 0 && store.count("alive") == 0) {
                refillSeeds(round + refills)
                refills++
                if (store.frontierSize() == 0) break
            }
        }
        // One entry per *site*, not per endpoint: `WebsitePool` keys on host, and
        // a host with eight working embeds is still one website. Dedupe here so
        // the round's `new` count and the pool's capacity are meaningful.
        val found = store.alive()
            .take(cap)
            .map { it.toSavedWebsite() }
            .distinctBy { FinderHelpers.hostOf(it.url) }

        // Give each site its real search template so `WebsiteFinder` can query
        // it. The Python left this to its consumer; here the consumer is a
        // title search that needs a URL shape, so we profile while we are
        // already holding the socket. Bounded, and a site that will not talk
        // still keeps its standard search paths (`profile` never throws for
        // that reason), so profiling can never empty the pool.
        // Local, so the nullable property is smart-cast inside the lambda.
        val profile = profiler
        val sites = if (profile == null) found else coroutineScope {
            found.map { site ->
                async {
                    if (System.currentTimeMillis() - started >= harvestBudgetMs) site
                    else runCatching { profile(site.url) }.getOrDefault(site)
                }
            }.awaitAll()
        }
        val elapsed = System.currentTimeMillis() - started
        FinderTrace.log(
            "web",
            "harvest#$round END ${elapsed}ms seen=$seen alive=${store.count("alive")} " +
                "sites=${found.size} drops=${rejectionReport().summary.ifEmpty { "-" }}"
        )
        return ServerHarvest(
            sites = sites,
            // `more` means "there is still work to do", exactly like the Python
            // loop's condition. Unvisited pages count, not just a sub-cap pool.
            more = store.count("alive") < cap || store.frontierSize() > 0,
            sources = listOf(
                FinderSourceReport(
                    id = "server-finder",
                    status = FinderSourceReport.Status.COMPLETE,
                    seen = seen,
                    checked = seen,
                    candidates = sites.size,
                    elapsedMs = elapsed
                )
            )
        )
    }

    /** How long one [harvest] call may crawl before returning what it has. */
    private var harvestBudgetMs: Long = 45_000L

    private companion object {
        /** Consecutive rounds that add nothing before a harvest gives up. */
        const val BARREN_ROUNDS = 2

        /**
         * How long a drain may hold the flag before it is presumed hung.
         *
         * A normal drain is bounded by [harvestBudgetMs] (45 s), so a hold well
         * past that means the coroutine died without running its `finally`
         * (a cancelled scope, a native hang inside the WebView). Without this
         * the flag stayed `true` forever and every later call skipped.
         */
        const val HARVEST_STALL_MS = 180_000L
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
}

/**
 * Timed sweep: re-probe the weakest slice, quarantine, refill.
 * Python: `Checker` in servers.py.
 */
class ServerFinderChecker(
    private val store: ServerFinderStore,
    private val http: FinderHttp,
    private val prober: ServerProber,
    private val harvester: ServerFinderHarvester,
    private val interval: Int = ServerFinder.CHECK_INTERVAL,
    private val deadStore: PoolStore? = null,
    private val deadTxtStore: PoolStore? = null
) {
    private val dead = mutableListOf<ServerRecord>()

    suspend fun sweep() {
        val targets = store.weak(maxOf(4, store.count("alive") / 20))
        val sem = Semaphore(8)
        coroutineScope {
            targets.map { rec ->
                async {
                    sem.withPermit {
                        val probe = prober.probe(rec.sampleUrl, rec.referer.ifBlank { null })
                        if (probe.alive) {
                            val updated = rec.copy(
                                lastChecked = FinderHelpers.now(),
                                ttfbMs = probe.ttfbMs,
                                kbps = probe.kbps,
                                stalls = probe.stalls,
                                bufferScore = probe.bufferScore,
                                failures = 0,
                                score = ServerFinder.scoreOf(rec.catalogue, rec.qualityTier, probe.bufferScore, probe.ttfbMs)
                            )
                            store.upsert(updated, if (probe.bufferScore < 40) "slow" else "alive")
                        } else {
                            val failures = rec.failures + 1
                            if (failures >= ServerFinder.FAILS_TO_DEAD || probe.status in listOf(404, 410, 451)) {
                                val deadRec = rec.copy(
                                    failures = failures,
                                    reason = probe.err.ifBlank { "http-${probe.status}" },
                                    deadAt = FinderHelpers.now(),
                                    lastChecked = FinderHelpers.now()
                                )
                                store.markDead(rec.id)
                                synchronized(dead) { dead += deadRec }
                            } else {
                                store.upsert(rec.copy(failures = failures, lastChecked = FinderHelpers.now()), "degraded")
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (dead.isNotEmpty()) {
            synchronized(dead) {
                deadStore?.write("""{"generated_at":"${FinderHelpers.now()}","entries":${Json.encodeToString(dead.takeLast(2000))}}""")
                val prev = deadTxtStore?.read()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet().orEmpty()
                val hosts = dead.map { it.host }.sorted()
                deadTxtStore?.write((prev + hosts).sorted().joinToString("\n") + "\n")
                dead.clear()
            }
        }
        if (store.count("alive") < harvester.cap) harvester.runUntilCap()
    }

    suspend fun loop() {
        while (true) {
            delay((interval * Random.nextDouble(0.85, 1.15) * 1000).toLong())
            try {
                sweep()
            } catch (e: Exception) {
                // keep the loop alive no matter what
            }
        }
    }
}
