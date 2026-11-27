package dev.nova.tv.finder.servers

import dev.nova.tv.finder.FinderSourceReport
import dev.nova.tv.finder.pool.PoolStore
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
object ServerSeeds {
    const val CAP = 6000
    const val CHECK_INTERVAL = 300
    const val DEFAULT_CONCURRENCY = 48
    const val RENDER_SLOTS = 4
    const val PROBE_BYTES = 262_144
    const val CHUNK = 32_768
    const val MAX_CATALOGUE = 250_000
    const val FAILS_TO_DEAD = 2

    /** Entry points. Only the engine and the cap are hardcoded. */
    val SEEDS = listOf(
        "https://hianime.to",
        "https://hianime.bz",
        "https://aniwatchtv.to",
        "https://9anime.org.lv",
        "https://9animetv.to",
        "https://aniwatch.to",
        "https://zoroxtv.to",
        "https://kaido.to",
        "https://anikai.to",
        "https://gogoanime.tel",
        "https://animekai.to",
        "https://9anime.pl"
    )

    val IFRAME = Regex("<(?:iframe|embed|source)[^>]+(?:src|data-src|data-file|file)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    val JS_SRC = Regex("(?:file|source|src|url|link)\\s*:\\s*[\"']((?:https?:)?//[^\"'\\\\ ]+)[\"']", RegexOption.IGNORE_CASE)
    val HLS = Regex("[\"']([^\"']+?\\.(?:m3u8|mpd)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
    val MAGIC = Regex("[\"']([A-Za-z0-9+/=]{40,})[\"']")
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
    private val seen = HashSet<String>()

    private fun add(rawIn: String, base: String, out: MutableMap<String, ServerCandidate>) {
        val raw = unescape(rawIn).trim().replace("\\/", "/")
        if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("javascript:")) return
        val url = FinderHelpers.norm(FinderHelpers.urljoin(base, raw))
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || (host in seen && url in out)) return
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
        for (rx in listOf(ServerSeeds.IFRAME, ServerSeeds.JS_SRC, ServerSeeds.HLS)) {
            for (m in rx.findAll(html)) add(m.groupValues[1], base, out)
        }
        for (blob in ServerSeeds.MAGIC.findAll(html).map { it.groupValues[1] }) {
            val decoded = runCatching {
                val padded = blob + "=".repeat((4 - blob.length % 4) % 4)
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if ("http" in decoded) {
                for (m in Regex("https?://[^\\s\"'<>\\\\]+").findAll(decoded)) add(m.value, base, out)
            }
        }
        if ("\"sources\"" in html) {
            for (m in Regex("\"(?:file|url)\"\\s*:\\s*\"([^\"]+)\"").findAll(html)) add(m.groupValues[1], base, out)
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
            while (got < ServerSeeds.PROBE_BYTES && System.nanoTime() < deadline) {
                val c0 = System.nanoTime()
                val buf = Buffer()
                val n = source.read(buf, ServerSeeds.CHUNK.toLong())
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
            if (ServerSeeds.SIG.containsMatchIn(url)) p.signature = "signed"
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
        val maps = if ("<sitemapindex" in root.text.lowercase()) ServerSeeds.LOC.findAll(root.text).map { it.groupValues[1] }.toList()
        else listOf("$base/sitemap.xml")
        for (sm in maps.take(25)) {
            if (!sm.endsWith(".xml") && !sm.endsWith(".xml.gz")) continue
            val r = http.get(sm, read = 400_000)
            if (r != null && r.status < 400) total += ServerSeeds.LOC.findAll(r.text).count()
            if (total >= ServerSeeds.MAX_CATALOGUE) break
        }
        return minOf(total, ServerSeeds.MAX_CATALOGUE)
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
        return minOf(total, ServerSeeds.MAX_CATALOGUE)
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
        return minOf(total, ServerSeeds.MAX_CATALOGUE)
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
    val frontier: List<String> = emptyList()
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

    init {
        runCatching {
            store.read()?.let { json.decodeFromString<ServerStoreFile>(it) }?.let { file ->
                file.servers.forEach { servers[it.id] = it }
                frontier += file.frontier
            }
        }
    }

    private fun save() {
        runCatching {
            store.write(json.encodeToString(ServerStoreFile.serializer(), ServerStoreFile(servers.values.toList(), frontier.toList())))
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
        if (url in frontier) return false
        frontier += url
        save()
        true
    }

    fun pop(n: Int): List<String> = synchronized(lock) {
        val out = frontier.take(n)
        frontier = frontier.drop(out.size).toMutableList()
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
    val sources: List<FinderSourceReport>
)

/**
 * Crawl -> extract -> probe -> count -> rank. Stops hard at the cap.
 * Python: `Harvester` in servers.py.
 */
class ServerFinderHarvester(
    private val store: ServerFinderStore,
    private val http: FinderHttp,
    private val browser: FinderBrowser? = null,
    val cap: Int = ServerSeeds.CAP,
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
    var written: Int = 0
        private set

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

    suspend fun discover(url: String) {
        http.prime(url)
        var html = ""
        var net: List<Pair<String, String>> = emptyList()
        if (browser != null) {
            val (h, n) = browser.render(url)
            html = h
            net = n
        }
        if (html.isEmpty()) {
            val r = http.get(url, read = 400_000)
            html = r?.text.orEmpty()
        }
        // A page that never came back cannot yield anything, and that is the
        // single most useful thing to know when the pool is empty.
        if (html.isEmpty()) {
            note("unreachable")
            return
        }
        for ((kind, link) in net) {
            if (kind == "xhr" && ("/api/" in link || link.endsWith(".json"))) {
                val r = http.get(link, referer = url, read = 300_000)
                if (r != null && r.status < 400) html += "\n" + r.text
            }
        }
        for (link in Regex("href\\s*=\\s*[\"']([^\"'#]+)[\"']").findAll(html).map { it.groupValues[1] }) {
            val absUrl = FinderHelpers.norm(FinderHelpers.urljoin(url, unescape(link)))
            if (FinderHelpers.hostOf(absUrl) == FinderHelpers.hostOf(url) &&
                Regex("/(watch|anime|series|tv|movie|episode|browse|page)/|page=\\d+").containsMatchIn(absUrl)
            ) {
                store.push(absUrl)
            }
        }
        val extracted = extractor.extract(html, url)
        if (extracted.isEmpty()) note("no-embeds")
        for (cand in extracted.values) {
            if (cand.host in blocked) { note("blocked"); continue }
            if (store.knownHost(cand.host)) continue
            if (store.count("alive") >= cap) return
            validate(cand)
        }
    }

    suspend fun validate(cand: ServerCandidate) {
        val probe = prober.probe(cand.url, cand.referer)
        if (!probe.alive) {
            // Distinguishes "the host is down" from "the host answered but
            // served a page/JS shell with no playable media".
            note(if (probe.status in listOf(200, 206)) "page-not-media" else "probe-failed")
            return
        }
        val base = FinderHelpers.originOf(cand.url)
        val catalogueCount = catalogue.count(base)
        if (catalogueCount < 40) {
            note("catalogue<40")
            return
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
            score = ServerSeeds.scoreOf(catalogueCount, maxOf(tier, probe.qualityTier), probe.bufferScore, probe.ttfbMs),
            failures = 0,
            firstSeen = now,
            lastChecked = now
        )
        store.upsert(rec, "alive")
        written += 1
    }

    fun refillSeeds() {
        // Python: push every SEED and its mirrors back onto the frontier.
        for (s in ServerSeeds.SEEDS) {
            store.push(s)
            for (m in FinderHelpers.mirrors(s)) store.push(m)
        }
    }

    suspend fun run() {
        for (s in ServerSeeds.SEEDS) store.push(s)
        while (store.count("alive") < cap) {
            var batch = store.pop(64)
            if (batch.isEmpty()) {
                refillSeeds()
                batch = store.pop(32)
                if (batch.isEmpty()) break
            }
            coroutineScope { batch.map { u -> async { discover(u) } }.awaitAll() }
            delay(200)
        }
    }

    /** Bounded refill used by the checker after a sweep. */
    suspend fun runUntilCap(rounds: Int = 4) {
        repeat(rounds) {
            if (store.count("alive") >= cap) return
            var batch = store.pop(48)
            if (batch.isEmpty()) {
                refillSeeds()
                batch = store.pop(24)
                if (batch.isEmpty()) return
            }
            coroutineScope { batch.map { u -> async { discover(u) } }.awaitAll() }
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
        resetRejections()
        var seen = 0
        // URLs already fetched by this call. `push()` only rejects a URL that is
        // currently *in* the frontier and `pop()` removes it, so `refillSeeds()`
        // re-queues a seed it has just consumed. When discovery yields nothing
        // -- the normal case for a JS shell fetched without a browser -- the
        // loop re-fetched the same seeds until its budget ran out instead of
        // concluding the surface was barren.
        val visited = HashSet<String>()
        var barren = 0
        while (System.currentTimeMillis() - started < harvestBudgetMs) {
            // Stop exactly where Python stops: the cap is a hard ceiling.
            if (store.count("alive") >= cap) break
            var batch = store.pop(64).filter { visited.add(it) }
            if (batch.isEmpty()) {
                refillSeeds()
                batch = store.pop(32).filter { visited.add(it) }
                // Every seed was fetched by this call and led nowhere: stop.
                // Resetting `visited` here would reintroduce the spin.
                if (batch.isEmpty()) break
            }
            seen += batch.size
            val before = store.count("alive") + store.frontierSize()
            coroutineScope { batch.map { u -> async { discover(u) } }.awaitAll() }
            val after = store.count("alive") + store.frontierSize()
            barren = if (after > before) 0 else barren + 1
            if (barren >= BARREN_ROUNDS) break
            // Python sleeps 0.2 s between rounds; keep the same courtesy pause
            // so a hostile host is not hammered by a tight loop.
            delay(200)
            // A round that found nothing new and drained the frontier is done.
            if (store.frontierSize() == 0 && store.count("alive") == 0) {
                refillSeeds()
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
    private val interval: Int = ServerSeeds.CHECK_INTERVAL,
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
                                score = ServerSeeds.scoreOf(rec.catalogue, rec.qualityTier, probe.bufferScore, probe.ttfbMs)
                            )
                            store.upsert(updated, if (probe.bufferScore < 40) "slow" else "alive")
                        } else {
                            val failures = rec.failures + 1
                            if (failures >= ServerSeeds.FAILS_TO_DEAD || probe.status in listOf(404, 410, 451)) {
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
