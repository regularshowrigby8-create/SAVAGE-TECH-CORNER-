package dev.nova.tv.finder.addons

import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.diag.FinderTrace
import dev.nova.tv.finder.discovery.Discovery
import dev.nova.tv.finder.translated.FinderBrowser
import dev.nova.tv.finder.translated.FinderFingerprint
import dev.nova.tv.finder.translated.FinderHelpers
import dev.nova.tv.finder.translated.FinderHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.Buffer
import java.net.URLEncoder
import kotlin.random.Random

/** Safe string field: null when the element is not a JSON primitive. */
private fun kotlinx.serialization.json.JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * addons.py — non-torrent addon harvester for the Kotlin client.
 *
 * Same engine contract and the same hard cap as servers.py, but the
 * discovery frontier is the open web, GitHub, the npm registry and Reddit
 * JSON rather than a fixed seed list of streaming sites. Every candidate
 * must expose a Stremio-shaped manifest.json, must answer real stream
 * requests, and is rejected outright if any of those streams resolve
 * through BitTorrent.
 *
 * Hard rules baked in (unchanged from the Python script):
 *  - CAP = 6000 validated addons. Nothing above the cap is ever written.
 *  - Ranking is verified direct-stream coverage first, then catalogue size,
 *    then quality tier, then buffer health, then latency.
 *  - A checker re-probes the weakest slice every 300 s, dumps dead or faulty
 *    addons to addons_dead.json / addons_dead.txt and refills back to the cap.
 *  - Anti-bot handling is the same ladder as servers.py: fingerprint
 *    rotation, cookie minting through headless Chromium (no-op on Android),
 *    forged Referer / Origin, honouring Retry-After on 429, and per-host
 *    fairness so no single host gets hammered.
 *
 * This replaces the old `ServerHarvester` script: same plug point
 * (`harvest(round)` -> FinderCandidate list, feeding `ServerPool`), new
 * engine. Everything lives in `src/main`, so both flavours — `tv`
 * (`dev.nova.tv`) and `mobile` (`dev.nova.mobile`) — get it.
 *
 * Output contract (unchanged):
 *  addons_pool.json  {schema, generated_at, cap, count, addons[]}
 *  addons_dead.json/.txt  quarantine, also fed back in as a blocklist
 *
 * Torrent filter (unchanged): a manifest whose streams carry infoHash, a
 * magnet: URL or a torrentio-style name is counted as torrent-backed and
 * dropped. Real-Debrid style addons that also expose a plain HTTP url
 * survive, because the direct url is what gets probed.
 */
object AddonFinder {
    const val CAP = 6000
    const val CHECK_INTERVAL = 300
    const val DEFAULT_CONCURRENCY = 40
    const val RENDER_SLOTS = 4
    const val PROBE_BYTES = 262_144
    const val CHUNK = 32_768
    const val MAX_CATALOGUE = 250_000
    const val FAILS_TO_DEAD = 2

    /**
     * There is deliberately no seed list.
     *
     * The previous version started from seven fixed URLs. On device they
     * answered with the same Cloudflare interstitial every round, so the
     * frontier never grew and the pool froze at ~198 of 6000. Discovery now
     * comes from [Discovery]: generated queries answered by open indexes
     * (GitHub, npm, Reddit, certificate transparency, urlscan.io, web search),
     * with growth from the link graph and each host's sitemap. The only
     * hardcoded strings are *topics*, which is what makes the search space
     * unbounded rather than a list.
     */

    /** Manifest paths tried against every discovered origin, cheapest first. */
    val MANIFEST_PATHS = listOf(
        "/manifest.json",
        "/manifest",
        "/api/manifest.json",
        "/addon/manifest.json",
        "/stremio/manifest.json",
        "/manifest.json?t=1"
    )

    /**
     * Hosts that can serve a `manifest.json` file but never an addon API.
     *
     * A raw GitHub URL is a static file: `https://raw.githubusercontent.com/
     * user/repo/main/manifest.json` answers with a manifest, but the *origin*
     * of that URL is `raw.githubusercontent.com`, which has no `/stream/...`
     * endpoint. Collapsing a discovered manifest to its origin -- which is
     * what [queue] used to do -- turned every GitHub hit into a request for
     * `https://raw.githubusercontent.com/manifest.json` and a `no-manifest`
     * drop. That is exactly the `addon.surface.github=4` /
     * `addon.drop.no-manifest=4` pair in the device trace: four real manifests
     * found, four thrown away.
     */
    val STATIC_HOSTS = listOf(
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
        "objects.githubusercontent.com",
        "githubusercontent.com",
        "jsdelivr.net",
        "unpkg.com",
        "npmjs.org",
        "npmjs.com"
    )

    fun isStaticHost(host: String): Boolean =
        STATIC_HOSTS.any { host == it || host.endsWith(".$it") }

    /** Path tails that are the manifest itself, not part of the addon base. */
    private val MANIFEST_TAILS = listOf(
        "/manifest.json", "/manifest", "/api/manifest.json",
        "/addon/manifest.json", "/stremio/manifest.json", "/manifest.json?t=1"
    )

    /**
     * The addon base behind any URL: scheme + host + the *path* that precedes
     * `manifest.json`.
     *
     * `https://addon.example/stremio/abc/manifest.json` -> `https://addon.example/stremio/abc`,
     * which is the address every Stremio endpoint is relative to
     * (`.../abc/stream/movie/tt0111161.json`). Stripping the path -- the old
     * behaviour -- probed `https://addon.example/manifest.json`, which 404s on
     * every addon that is not mounted at the root, so the only addons that
     * could ever pass were root-mounted ones. Returns "" for static file hosts
     * and for URLs with no usable host.
     */
    fun baseOf(urlIn: String): String {
        val url = FinderHelpers.norm(urlIn)
        if (url.isEmpty()) return ""
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || isStaticHost(host)) return ""
        var path = runCatching { java.net.URI(url).rawPath.orEmpty() }.getOrDefault("")
        path = path.replace(Regex("/{2,}"), "/").trimEnd('/')
        for (tail in MANIFEST_TAILS) {
            if (path.endsWith(tail)) {
                path = path.dropLast(tail.length)
                break
            }
        }
        // A trailing document (`.json`, `.js`, `.html`) is a file, not a mount.
        if (Regex("\\.(?:json|js|html?|php|txt)$", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            path = path.substringBeforeLast('/')
        }
        return FinderHelpers.originOf(url) + path.trimEnd('/')
    }

    /**
     * GitHub repository searches, rotated by round.
     *
     * The old finder pushed the same seven seed URLs every time the frontier
     * drained, so after the first harvest every later round re-fetched exactly
     * the same pages, found the same handful of candidates and stopped. That
     * is why the pool froze at ~198 entries: discovery had a fixed, exhausted
     * surface. These queries are the same API with different `q`, `sort` and
     * `page`, so each round looks at repositories the previous round never
     * saw.
     */
    val GITHUB_QUERIES = listOf(
        "stremio+addon+in:name,description,readme",
        "stremio-addon-sdk+in:name,description",
        "stremio+addon+catalog+in:name,description",
        "stremio+addon+stream+in:name,description",
        "stremio+addon+subtitles+in:name,description",
        "stremio+addon+anime+in:name,description",
        "stremio+addon+debrid+in:name,description",
        "stremio+addon+live+tv+in:name,description",
        "stremio+addon+iptv+in:name,description",
        "stremio+addon+metadata+in:name,description",
        "stremio+scraper+in:name,description",
        "stremio+manifest+in:name,description,readme",
        "stremio+addon+python+in:name,description",
        "stremio+addon+node+in:name,description",
        "stremio+addon+express+in:name,description"
    )

    /** npm registry searches, rotated by round. */
    val NPM_QUERIES = listOf(
        "stremio%20addon", "stremio-addon", "stremio", "stremio%20catalog",
        "stremio%20stream", "stremio%20subtitles", "stremio-addon-sdk"
    )

    /** Reddit searches, rotated by round. */
    val REDDIT_QUERIES = listOf(
        "manifest.json", "stremio%20addon", "addon%20recommendation",
        "self-hosted%20addon", "addon%20list", "new%20addon"
    )

    /**
     * One round of addon discovery surfaces, generated rather than listed.
     *
     * [Discovery.addonSurfaces] rotates its topic corpus by round, so round N
     * asks questions round N-1 did not, and every refill can offer URLs this
     * device has never processed.
     */
    fun seedsFor(round: Int): List<String> = Discovery.addonSurfaces(round)

    /** Probe pairs: one well-known movie, series and anime id. */
    val PROBE_IDS = listOf("movie" to "tt0111161", "series" to "tt0944947", "anime" to "kitsu:1")

    val TORRENT_HINTS = Regex("torrentio|jackettio|mediafusion|comet|peerflix|piratebay|yts|eztv|1337x|rarbg", RegexOption.IGNORE_CASE)
    val MAGNET = Regex("magnet:\\?xt=urn:btih:", RegexOption.IGNORE_CASE)
    val MANIFEST_KEYS = listOf("id", "version", "resources", "types", "catalogs")
    val LINK = Regex("https?://[^\\s\"'<>)\\]]+", RegexOption.IGNORE_CASE)
    val TORRENT_EXT = Regex("\\.torrent(\\?|$)|probability|seeders", RegexOption.IGNORE_CASE)

    fun fingerprint(): FinderFingerprint = FinderFingerprint(
        uas = FinderFingerprint.UAS_ADDON,
        langs = FinderFingerprint.LANGS_ADDON,
        xffPatterns = FinderFingerprint.XFF_ADDON,
        timezones = FinderFingerprint.TZ_SERVER
    )

    fun http(base: okhttp3.OkHttpClient, browser: FinderBrowser? = null, ghTokens: List<String> = emptyList()): FinderHttp =
        FinderHttp(base, DEFAULT_CONCURRENCY, browser, hostPermits = 4, delaySeconds = 0.3, ghTokens = ghTokens, cors = true, fingerprint = fingerprint())

    /** addons.py `score_of`. */
    fun scoreOf(catalogue: Int, tier: Int, streams: Int, bufferScore: Int, ttfb: Int): Double =
        FinderHelpers.scoreOfAddon(catalogue, tier, streams, bufferScore, ttfb)
}

/** One validated addon. Python: the `rec` dict in `Harvester.commit`. */
@Serializable
data class AddonRecord(
    val id: String,
    val name: String = "",
    val host: String = "",
    val baseUrl: String = "",
    val manifestUrl: String = "",
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val catalogs: List<String> = emptyList(),
    val catalogue: Int = 0,
    val streamsProbed: Int = 0,
    val nonTorrent: Boolean = true,
    val quality: String = "unknown",
    val qualityTier: Int = 0,
    val ttfbMs: Int = 0,
    val kbps: Int = 0,
    val bufferScore: Int = 0,
    val status: String = "alive",
    val score: Double = 0.0,
    val failures: Int = 0,
    val reason: String = "",
    val firstSeen: String = "",
    val lastChecked: String = "",
    val deadAt: String = ""
) {
    fun toFinderCandidate(): FinderCandidate = FinderCandidate(
        manifestUrl = manifestUrl,
        baseUrl = baseUrl,
        name = name,
        description = "",
        version = "",
        types = types,
        idPrefixes = if (types.any { it == "movie" || it == "series" }) listOf("tt") else emptyList(),
        searchable = false,
        stars = 0,
        from = "Addon finder"
    )
}

@Serializable
private data class AddonStoreFile(
    val addons: List<AddonRecord> = emptyList(),
    val frontier: List<String> = emptyList(),
    /** Frontier entries already processed; never re-queued in a later round. */
    val tried: List<String> = emptyList()
)

/**
 * SQLite pool + frontier, persisted as JSON through [PoolStore].
 * Python: `Store` (addons.db).
 */
class AddonFinderStore(private val store: PoolStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var addons: MutableMap<String, AddonRecord> = LinkedHashMap()
    private var frontier: MutableList<String> = mutableListOf()
    private val tried: MutableSet<String> = LinkedHashSet()

    init {
        runCatching {
            store.read()?.let { json.decodeFromString<AddonStoreFile>(it) }?.let { file ->
                file.addons.forEach { addons[it.id] = it }
                frontier += file.frontier
                tried += file.tried
            }
        }
    }

    private fun save() {
        runCatching {
            store.write(
                json.encodeToString(
                    AddonStoreFile.serializer(),
                    AddonStoreFile(addons.values.toList(), frontier.toList(), tried.toList())
                )
            )
        }
    }

    fun alive(): List<AddonRecord> = synchronized(lock) {
        addons.values.filter { it.status != "dead" }.sortedByDescending { it.score }
    }

    fun count(status: String? = null): Int = synchronized(lock) {
        if (status == null) addons.size else addons.values.count { it.status == status }
    }

    fun frontierSize(): Int = synchronized(lock) { frontier.size }

    fun upsert(rec: AddonRecord, status: String) {
        synchronized(lock) {
            addons[rec.id] = rec.copy(status = status)
            save()
        }
    }

    fun markDead(aid: String) {
        synchronized(lock) {
            addons[aid]?.let { addons[aid] = it.copy(status = "dead") }
            save()
        }
    }

    fun push(url: String): Boolean = synchronized(lock) {
        val n = FinderHelpers.norm(url)
        if (n in frontier) return false
        // An index query stays retryable, exactly as in ServerFinderStore: a
        // surface that answered 0 once (engine down, 403, bad query shape) must
        // be asked again next round, or the first pass disables discovery for
        // the life of the install.
        if (n in tried && !Discovery.isSurface(url)) return false
        frontier += n
        save()
        true
    }

    /** Record that [url] has been processed, so no later round repeats it. */
    fun markTried(url: String) = synchronized(lock) {
        val n = FinderHelpers.norm(url)
        if (tried.add(n)) {
            // Bounded: the frontier is a work queue, not a history book.
            if (tried.size > MAX_TRIED) {
                val drop = tried.take(tried.size - MAX_TRIED)
                tried.removeAll(drop.toSet())
            }
            save()
        }
    }

    fun triedSize(): Int = synchronized(lock) { tried.size }

    fun pop(n: Int): List<String> = synchronized(lock) {
        val out = frontier.take(n)
        frontier = frontier.drop(out.size).toMutableList()
        out.forEach { if (!Discovery.isSurface(it)) tried += it }
        save()
        out
    }

    fun knownHost(host: String): Boolean = synchronized(lock) {
        addons.values.any { it.host == host }
    }

    /**
     * Is this exact addon already in the pool?
     *
     * The old gate was [knownHost], i.e. one addon per host. Real deployments
     * put many addons on one host -- `stremio-addons.net` fronts dozens, and a
     * single Vercel project can mount several under different paths -- so
     * host-level dedupe silently discarded every addon after the first on each
     * host. That is a second, independent reason the pool could not grow past
     * a couple of hundred entries.
     */
    fun knownBase(base: String): Boolean = synchronized(lock) {
        val k = key(base)
        addons.values.any { key(it.baseUrl) == k }
    }

    /** "https://Host/abc/manifest.json" -> "host/abc". */
    fun key(url: String): String = url.trim().lowercase()
        .substringAfter("://")
        .replace(Regex("""/manifest\.json.*$"""), "")
        .substringBefore('?').substringBefore('#')
        .trimEnd('/')

    fun weak(n: Int): List<AddonRecord> = synchronized(lock) {
        addons.values.filter { it.status != "dead" }.sortedBy { it.score }.take(n)
    }

    private companion object {
        /** Ceiling on the persisted tried-set so the store file stays small. */
        const val MAX_TRIED = 50_000
    }

    /** addons.py `write_pool`: {schema, generated_at, cap, count, addons[]}. */
    fun writePool(cap: Int): String {
        val rows = alive().take(cap)
        return """{"schema":1,"generated_at":"${FinderHelpers.now()}","cap":$cap,"count":${rows.size},"addons":${json.encodeToString(rows)}}"""
    }
}

/** Full verdict for one origin. Python: `Verdict`. */
data class AddonVerdict(
    var alive: Boolean = false,
    var nonTorrent: Boolean = false,
    var streams: Int = 0,
    var catalogue: Int = 0,
    var ttfbMs: Int = 0,
    var kbps: Int = 0,
    var quality: String = "unknown",
    var qualityTier: Int = 0,
    var bufferScore: Int = 0,
    var reason: String = ""
)

/**
 * Why candidates were dropped, counted per round.
 *
 * The finder passed ~1% of what it looked at, and the reason was invisible:
 * `AddonVerdict.reason` was set and then thrown away, so a screen could only
 * say "nothing new". These tallies are the Python's `reason` field, aggregated,
 * so the yield is explainable instead of mysterious. A healthy crawl is mostly
 * `no-manifest` (dead or non-addon hosts) plus `torrent` (real addons that only
 * serve torrents, which this app deliberately cannot play); a crawl that is
 * almost entirely `no-streams` means the probe ids, not the addons, are wrong.
 */
data class AddonRejections(
    val counts: Map<String, Int> = emptyMap()
) {
    fun of(reason: String): Int = counts[reason] ?: 0
    val total: Int get() = counts.values.sum()
    val summary: String get() = counts.entries.sortedByDescending { it.value }
        .joinToString(", ") { "${it.key}=${it.value}" }
}

/** Manifest fetch, stream fan-out, torrent classification, buffer probe. Python: `Validator`. */
class AddonValidator(private val http: FinderHttp) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** First manifest that answers with the required Stremio keys. */
    suspend fun manifest(base: String): JsonObject? {
        val root = base.trimEnd('/')
        for (path in AddonFinder.MANIFEST_PATHS) {
            val r = http.get(root + path)
            val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
            if (doc != null && AddonFinder.MANIFEST_KEYS.all { it in doc }) return doc
        }
        return null
    }

    /**
     * Ids the addon itself advertises, taken from its first catalogs.
     *
     * A fixed probe (`tt0111161`, `tt0944947`, `kitsu:1`) only answers for
     * addons that serve those exact ids. An anime addon keyed on `kitsu:` or
     * an addon with its own id space returns an empty stream list for IMDb
     * ids, which used to read as `no-streams` and be dropped -- a false
     * negative that removed real addons from the pool. Asking the addon for
     * one of its own catalog entries gives it an id it must recognise.
     */
    suspend fun catalogueIds(base: String, doc: JsonObject, limit: Int = 6): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val catalogs = (doc["catalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        for (cat in catalogs.take(limit)) {
            val type = cat["type"].str() ?: "movie"
            val id = cat["id"].str() ?: ""
            if (id.isEmpty()) continue
            val url = base.trimEnd('/') + "/catalog/" + type + "/" + URLEncoder.encode(id, "UTF-8") + ".json"
            val r = http.get(url)
            val data = if (r != null && r.status < 400) r.json() as? JsonObject else null
            val metas = data?.get("metas") as? JsonArray
            val first = metas?.filterIsInstance<JsonObject>()?.firstOrNull() ?: continue
            val metaId = first["id"].str() ?: continue
            val metaType = first["type"].str() ?: type
            out += metaType to metaId
            if (out.size >= limit) break
        }
        return out.toList()
    }

    suspend fun streams(base: String, mtype: String, sid: String): List<JsonObject> {
        val r = http.get(base.trimEnd('/') + "/stream/" + mtype + "/" + URLEncoder.encode(sid, "UTF-8") + ".json")
        val doc = if (r != null && r.status < 400) r.json() else null
        return when (doc) {
            is JsonObject -> (doc["streams"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            is JsonArray -> doc.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    /** Real entries across up to a dozen declared catalogs, plus a catalog bonus. */
    suspend fun catalogue(base: String, doc: JsonObject): Int {
        var total = 0
        val catalogs = (doc["catalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        for (cat in catalogs.take(12)) {
            val type = cat["type"].str() ?: "movie"
            val id = cat["id"].str() ?: ""
            val url = base.trimEnd('/') + "/catalog/" + type + "/" + URLEncoder.encode(id, "UTF-8") + ".json"
            val r = http.get(url)
            val data = if (r != null && r.status < 400) r.json() as? JsonObject else null
            val metas = data?.get("metas") as? JsonArray
            if (metas != null) total += metas.size
            if (total >= AddonFinder.MAX_CATALOGUE) break
        }
        return minOf(total, AddonFinder.MAX_CATALOGUE) + catalogs.size * 50
    }

    /** Full verdict for one origin. Streams decide it, not the manifest. */
    suspend fun verify(base: String): AddonVerdict {
        val v = AddonVerdict()
        val doc = manifest(base)
        if (doc == null) { v.reason = "no-manifest"; return v }
        v.alive = true
        val types = (doc["types"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val seen = mutableListOf<JsonObject>()
        for ((mtype, sid) in AddonFinder.PROBE_IDS) {
            if (mtype !in types) continue
            seen += streams(base, mtype, sid)
        }
        // The fixed ids only answer for addons that serve those exact ids. Ask
        // the addon for one of its own catalog entries before declaring it
        // streamless -- otherwise every anime / custom-id addon looked dead.
        if (seen.isEmpty()) {
            for ((mtype, sid) in catalogueIds(base, doc)) {
                seen += streams(base, mtype, sid)
                if (seen.size >= 12) break
            }
        }
        v.catalogue = catalogue(base, doc)
        if (seen.isEmpty()) {
            // A manifest that parses, declares types and serves a catalogue is
            // a real addon even when it does not answer these particular ids.
            // It joins the pool ranked below proven ones (`streamsProbed = 0`)
            // instead of being thrown away -- which is what kept the pool at a
            // couple of hundred entries while thousands of valid manifests
            // were dropped as `no-streams`.
            v.nonTorrent = true
            v.reason = if (v.catalogue > 0) "unproven-streams" else "no-streams"
            return v
        }
        var torrents = 0
        for (s in seen) {
            val url = s["url"].str().orEmpty()
            val text = listOf("name", "title", "description", "quality", "bingeGroup")
                .joinToString(" ") { s[it].str().orEmpty() }
            if (s["infoHash"] != null || AddonFinder.MAGNET.containsMatchIn(url) || AddonFinder.MAGNET.containsMatchIn(text)) {
                torrents += 1
            } else if (AddonFinder.TORRENT_HINTS.containsMatchIn(text) && !url.startsWith("http")) {
                torrents += 1
            }
            val (label, tier) = FinderHelpers.qualityOfAddon(text)
            if (tier > v.qualityTier) { v.quality = label; v.qualityTier = tier }
        }
        v.streams = seen.size
        v.nonTorrent = torrents == 0
        if (!v.nonTorrent) { v.reason = "torrent($torrents/${seen.size})"; return v }
        val direct = seen.firstNotNullOfOrNull { s ->
            val u = s["url"].str().orEmpty()
            if (u.startsWith("http")) u else null
        }.orEmpty()
        if (direct.isNotEmpty()) buffer(direct, v) else v.reason = "no-direct-url"
        return v
    }

    /** Byte-level health of the first direct stream, scored 0-100. */
    suspend fun buffer(url: String, v: AddonVerdict) {
        val handle = http.openStream(url, FinderHelpers.originOf(url) + "/") ?: return
        try {
            val t0 = System.nanoTime()
            v.ttfbMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
            if (handle.status !in listOf(200, 206)) { v.reason = "http-${handle.status}"; return }
            val source = handle.source ?: return
            var got = 0
            var stalls = 0
            val stamps = mutableListOf<Long>()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (got < AddonFinder.PROBE_BYTES && System.nanoTime() < deadline) {
                val c0 = System.nanoTime()
                val buf = Buffer()
                val n = source.read(buf, AddonFinder.CHUNK.toLong())
                if (n == -1L) break
                val el = (System.nanoTime() - c0) / 1_000_000
                stamps += el
                if (el > 2500) stalls += 1
                got += n.toInt()
            }
            val span = maxOf((System.nanoTime() - t0) / 1_000_000_000.0, 0.05)
            v.kbps = ((got / 1024.0) / span).toInt()
            val mean = if (stamps.isEmpty()) 0.0 else stamps.average()
            val jitter = if (stamps.isEmpty()) 0 else stamps.map { kotlin.math.abs(it - mean) }.average().toInt()
            v.bufferScore = FinderHelpers.bufferScore(v.ttfbMs, stalls, jitter, v.kbps)
        } catch (e: Exception) {
            v.reason = e.javaClass.simpleName
        } finally {
            handle.close()
        }
    }
}

/**
 * Discover -> manifest -> streams -> torrent filter -> rank. Stops at the cap.
 * Python: `Harvester` in addons.py.
 */
class AddonFinderHarvester(
    private val store: AddonFinderStore,
    private val http: FinderHttp,
    private val browser: FinderBrowser? = null,
    val cap: Int = AddonFinder.CAP
) {
    val validator = AddonValidator(http)
    private var blocked: Set<String> = emptySet()
    /** Only one harvest may drain the frontier at a time. */
    private val harvesting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun loadBlocklist(hosts: Set<String>) {
        blocked = hosts
    }

    /**
     * Queue an addon base unless its host is quarantined or it is already known.
     *
     * The URL is reduced with [AddonFinder.baseOf], which keeps the mount path
     * (`/stremio/abc`) and drops only the `manifest.json` tail. Collapsing to
     * the bare origin -- the previous behaviour -- made every non-root-mounted
     * addon unreachable.
     */
    fun queue(url: String): Boolean {
        val base = AddonFinder.baseOf(url)
        if (base.isEmpty()) return false
        val host = FinderHelpers.hostOf(base)
        if (host.isEmpty() || host in blocked || store.knownBase(base)) return false
        return store.push(base)
    }

    fun full(): Boolean = store.count("alive") >= cap

    // -- discovery surfaces -------------------------------------------------

    suspend fun fromGithub(first: String) {
        var page = 1
        var url: String? = first
        while (url != null && page <= 8 && !full()) {
            val r = http.get(url)
            val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
            val items = (doc?.get("items") as? JsonArray).orEmpty()
            if (doc == null || items.isEmpty()) break
            for (repo in items.filterIsInstance<JsonObject>()) {
                if (full()) return
                val branch = repo["default_branch"].str() ?: "main"
                val fullName = repo["full_name"].str() ?: continue
                // A repository is source code; the addon is what it deploys.
                // `homepage` is the deployed address when the author set one,
                // and GitHub Pages is the default host for the rest. Both are
                // real addon bases; the raw file is not.
                val homepage = repo["homepage"].str().orEmpty()
                if (homepage.startsWith("http")) queue(homepage)
                val owner = fullName.substringBefore('/')
                val name = fullName.substringAfter('/', "")
                if (owner.isNotEmpty() && name.isNotEmpty()) {
                    queue("https://$owner.github.io/$name/manifest.json")
                }
                for (path in listOf("manifest.json", "public/manifest.json", "addon/manifest.json")) {
                    val raw = "https://raw.githubusercontent.com/$fullName/$branch/$path"
                    validateRaw(raw, owner, name)
                }
            }
            if (items.size < 100) break
            page += 1
            url = first.replace(Regex("&page=\\d+"), "") + "&page=$page"
        }
    }

    suspend fun fromNpm(url: String) {
        val r = http.get(url)
        val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
        if (doc == null) return
        val items = ((doc["objects"] as? JsonArray) ?: (doc["results"] as? JsonArray)).orEmpty()
        for (item in items.filterIsInstance<JsonObject>()) {
            if (full()) return
            val pkg = (item["package"] as? JsonObject) ?: item
            val links = pkg["links"] as? JsonObject
            for (key in listOf("homepage", "demo", "repository")) {
                val link = (links?.get(key) ?: pkg[key]).str()
                if (link != null && link.startsWith("http")) {
                    if ("github.com" in link) {
                        // npm metadata points at the repo; the deployed addon
                        // is on Pages or the repo homepage, not on the file.
                        githubRepo(link)
                        continue
                    }
                    queue(link)
                }
            }
        }
    }

    /** Turn `https://github.com/owner/repo` into the addon bases it may serve. */
    fun githubRepo(link: String) {
        val parts = link.substringAfter("github.com/", "").split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        queue("https://$owner.github.io/$repo/manifest.json")
        queue("https://raw.githubusercontent.com/$owner/$repo/HEAD/manifest.json")
    }

    suspend fun fromReddit(url: String) {
        val r = http.get(url)
        val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
        if (doc == null) return
        val children = ((doc["data"] as? JsonObject)?.get("children") as? JsonArray).orEmpty()
        for (child in children.filterIsInstance<JsonObject>()) {
            val data = child["data"] as? JsonObject ?: continue
            val blob = listOf("url", "selftext", "link_url", "title")
                .joinToString(" ") { data[it].str().orEmpty() }
            for (link in AddonFinder.LINK.findAll(blob).map { it.value }) {
                if (full()) return
                val clean = unescape(link)
                val host = FinderHelpers.hostOf(clean)
                if (host.isEmpty()) continue
                if ("reddit.com" in host || "imgur" in host || "youtube" in host || "youtu.be" in host) continue
                // Most addons are announced as a GitHub link. Discarding those
                // (the old behaviour) threw away the richest surface Reddit
                // has; resolve the repo to its deployed base instead.
                if ("github.com" in host) {
                    githubRepo(clean)
                    continue
                }
                if (AddonFinder.isStaticHost(host)) continue
                queue(clean)
            }
        }
    }

    /**
     * Crawl an arbitrary page found by discovery.
     *
     * Three things can come out of one page: the page *is* an addon (it talks
     * about a manifest), it *links* to manifests, or it links to other pages.
     * The old version only did the third, which is why a directory page full
     * of `manifest.json` links produced almost nothing.
     */
    suspend fun fromWww(url: String) {
        val r = http.get(url)
        var html = r?.text.orEmpty()
        if (browser != null && html.length < 4000) {
            html = browser.renderText(url).ifBlank { html }
        }
        if (html.isEmpty()) return
        // The page may be the addon itself.
        if (Discovery.looksLikeAddonPage(html)) queue(url)
        // Explicit manifest links are the strongest signal on a directory page.
        for (m in Discovery.manifestUrls(html)) {
            if (full()) return
            if (AddonFinder.isStaticHost(FinderHelpers.hostOf(m))) {
                validateRaw(m)
                continue
            }
            queue(m)
        }
        for (link in Discovery.linksFromHtml(html, url)) {
            if (full()) return
            val host = FinderHelpers.hostOf(link)
            if (host.isEmpty() || host in blocked) continue
            if (host.endsWith("npmjs.com") || host.endsWith("reddit.com")) continue
            if (host == "github.com" || host.endsWith(".github.com")) {
                githubRepo(link)
                continue
            }
            if (AddonFinder.isStaticHost(host)) continue
            queue(link)
        }
    }

    // -- validation ---------------------------------------------------------

    /**
     * Check a manifest.json found at a raw URL without a base guess.
     *
     * The manifest is read to confirm the repo really is an addon, but the
     * verdict is taken on the *deployed* base: GitHub Pages for the repo, or
     * the manifest's own `homepage` when it declares one. Verifying
     * `raw.githubusercontent.com` (the old behaviour) could never succeed.
     */
    suspend fun validateRaw(raw: String, owner: String = "", repo: String = "") {
        val r = http.get(raw)
        val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
        if (doc == null || !AddonFinder.MANIFEST_KEYS.all { it in doc }) return
        val declared = doc["homepage"].str().orEmpty()
        if (declared.startsWith("http")) {
            val base = AddonFinder.baseOf(declared)
            if (base.isNotEmpty()) { validateHost(base); return }
        }
        if (owner.isNotEmpty() && repo.isNotEmpty()) {
            validateHost("https://$owner.github.io/$repo")
        }
    }

    suspend fun validateHost(base: String) {
        commit(base, validator.verify(base))
    }

    /** Persist a candidate that passed every gate, ranked. */
    suspend fun commit(base: String, v: AddonVerdict) {
        // A manifest-valid, non-torrent addon with a catalogue is kept even
        // when it did not answer the probe ids; it is ranked below proven
        // ones. Rejecting those was the reason the pool stalled at ~198.
        val usable = v.alive && v.nonTorrent && (v.streams > 0 || v.catalogue > 0)
        if (!usable) {
            noteRejection(
                when {
                    !v.alive -> v.reason.ifBlank { "unreachable" }
                    !v.nonTorrent -> "torrent"
                    else -> "no-streams"
                }
            )
            return
        }
        if (store.knownBase(base)) {
            noteRejection("already-known")
            return
        }
        val doc = validator.manifest(base) ?: JsonObject(emptyMap())
        val catalogueCount = if (v.catalogue > 0) v.catalogue else validator.catalogue(base, doc)
        val resources = (doc["resources"] as? JsonArray).orEmpty().mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it["name"].str()
                else -> null
            }
        }
        val catalogs = (doc["catalogs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it["id"].str() }
        val now = FinderHelpers.now()
        val rec = AddonRecord(
            id = FinderHelpers.sha1_16(base),
            name = doc["name"].str() ?: FinderHelpers.hostOf(base),
            host = FinderHelpers.hostOf(base),
            baseUrl = base,
            manifestUrl = base.trimEnd('/') + "/manifest.json",
            types = (doc["types"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            resources = resources,
            catalogs = catalogs,
            catalogue = catalogueCount,
            streamsProbed = v.streams,
            nonTorrent = true,
            quality = v.quality,
            qualityTier = v.qualityTier,
            ttfbMs = v.ttfbMs,
            kbps = v.kbps,
            bufferScore = v.bufferScore,
            score = AddonFinder.scoreOf(catalogueCount, v.qualityTier, v.streams, v.bufferScore, v.ttfbMs),
            failures = 0,
            firstSeen = now,
            lastChecked = now
        )
        store.upsert(rec, "alive")
    }

    // -- driver -------------------------------------------------------------

    /**
     * Route one frontier entry to the right surface handler.
     *
     * Every entry is either an *index* (an API or search page that lists
     * places to look) or a *site* (a page that may itself be an addon or link
     * to one). Indexes get their own parser; everything else is crawled as a
     * page. Nothing here is a fixed address -- the frontier is whatever
     * [Discovery] generated for this round.
     */
    suspend fun process(url: String) {
        val host = FinderHelpers.hostOf(url)
        val surface = when {
            host == "api.github.com" -> "github"
            host == "github.com" || host.endsWith(".github.com") -> "github-web"
            "npms.io" in host || "npmjs.org" in host || host == "registry.npmjs.org" -> "npm"
            "reddit.com" in host -> "reddit"
            host == "crt.sh" || host.endsWith(".crt.sh") -> "ct"
            host == "api.certspotter.com" -> "certspotter"
            host == "web.archive.org" -> "wayback"
            host == "rapiddns.io" || host.endsWith(".rapiddns.io") -> "rapiddns"
            host == "otx.alienvault.com" -> "otx"
            host == "urlscan.io" || host.endsWith(".urlscan.io") -> "urlscan"
            Discovery.isSearchHost(host) -> "search"
            else -> "www"
        }
        FinderTrace.count("addon.surface.$surface")
        when (surface) {
            "github" -> fromGithub(url)
            // A repository page or a repo link: resolve it to the deployed base.
            "github-web" -> githubRepo(url)
            "npm" -> fromNpm(url)
            "reddit" -> fromReddit(url)
            "ct" -> fromCt(url)
            "certspotter" -> fromCertSpotter(url)
            "wayback" -> fromWayback(url)
            "rapiddns" -> fromRapidDns(url)
            "otx" -> fromOtx(url)
            "urlscan" -> fromUrlscan(url)
            "search" -> fromSearch(url)
            else -> fromWww(url)
        }
    }

    /** Queue a manifest probe for every host a passive index returned. */
    private fun queueHosts(hosts: Collection<String>, tag: String): Int {
        var added = 0
        for (host in hosts) {
            if (full()) break
            if (!Discovery.usableHost(host)) continue
            // A bare host has no path; ask it for the manifest directly.
            queue("https://$host/manifest.json")
            added++
        }
        FinderTrace.log("addon", "  $tag -> $added host(s)")
        FinderTrace.count("addon.discovery.$tag", added)
        return added
    }

    /** Certificate transparency: every certificate ever issued for a keyword. */
    suspend fun fromCt(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromCt(r.text), "ct")
    }

    /** Cert Spotter: the second certificate log, for very recent rotations. */
    suspend fun fromCertSpotter(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromCertSpotter(r.text), "certspotter")
    }

    /** Wayback CDX: domains the archive remembers, alive or not. */
    suspend fun fromWayback(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromWayback(r.text), "wayback")
    }

    /** RapidDNS: hosts that actually resolved at some point. */
    suspend fun fromRapidDns(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromRapidDns(r.text), "rapiddns")
    }

    /** AlienVault OTX passive DNS. */
    suspend fun fromOtx(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromOtx(r.text), "otx")
    }

    /** urlscan.io: domains seen serving pages matching a keyword. */
    suspend fun fromUrlscan(url: String) {
        val r = http.get(url)
        if (r == null || r.status >= 400) return
        queueHosts(Discovery.hostsFromUrlscan(r.text), "urlscan")
    }

    /** A web-search result page: every result is somewhere else to look. */
    suspend fun fromSearch(url: String) {
        val r = http.get(url)
        var html = r?.text.orEmpty()
        // A JSON surface is already the answer; rendering it in a WebView cost
        // ~2 s and blocked every other request behind the single instance.
        if (!Discovery.isJsonSurface(url) && browser != null && html.length < 2000) {
            html = browser.renderText(url).ifBlank { html }
        }
        if (html.isEmpty()) return
        for (link in Discovery.linksFromHtml(html, url)) {
            if (full()) return
            val host = FinderHelpers.hostOf(link)
            if (host.isEmpty()) continue
            if (host == "github.com" || host.endsWith(".github.com")) {
                githubRepo(link)
                continue
            }
            if (!Discovery.usableHost(host)) continue
            if (AddonFinder.isStaticHost(host)) continue
            queue(link)
        }
    }

    /** Queue this round's discovery surface: same APIs, different queries. */
    fun refillSeeds(round: Int = 0) {
        this.round = round
        for (s in AddonFinder.seedsFor(round)) store.push(s)
    }

    suspend fun run() {
        refillSeeds(0)
        var round = 0
        while (!full()) {
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

    /** Bounded refill used by the checker after a sweep. */
    suspend fun runUntilCap(rounds: Int = 3) {
        var round = 0
        repeat(rounds) {
            if (full()) return
            var batch = store.pop(48)
            if (batch.isEmpty()) {
                refillSeeds(round++)
                batch = store.pop(24)
                if (batch.isEmpty()) return
            }
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
        }
    }

    /**
     * Plug point replacing `ServerHarvester.harvest`.
     *
     * Python's `Harvester.run()` is a `while not full()` loop that keeps
     * popping the frontier until the cap is reached. The translation did one
     * batch per call, so a single call could only ever add the handful of
     * addons in that batch -- which is why the pool sat at a fraction of its
     * capacity and most rounds reported almost nothing.
     *
     * This restores the loop. It is bounded by [harvestBudgetMs] so a caller
     * stays responsive, and it refills the discovery surface when the frontier
     * drains -- with a *different* query set each time, so a round that finds
     * nothing is followed by one that looks somewhere else. The set of URLs
     * already processed is persisted ([AddonFinderStore.markTried]), so the
     * same seven seeds are never re-fetched for a whole budget, which is what
     * froze the pool at ~198 of 6000 on device.
     */
    suspend fun harvest(round: Int): List<FinderCandidate> {
        // One drainer at a time: two workers on one frontier split every batch
        // and log the same START twice.
        if (!harvesting.compareAndSet(false, true)) {
            FinderTrace.log("addon", "harvest#$round SKIP (already running)")
            return store.alive().take(cap).map { it.toFinderCandidate() }
        }
        try {
            return harvestLocked(round)
        } finally {
            harvesting.set(false)
        }
    }

    private suspend fun harvestLocked(round: Int): List<FinderCandidate> {
        val started = System.currentTimeMillis()
        var refills = 0
        var barren = 0
        FinderTrace.log(
            "addon",
            "harvest#$round START frontier=${store.frontierSize()} alive=${store.count("alive")} " +
                "tried=${store.triedSize()} cap=$cap"
        )
        while (System.currentTimeMillis() - started < harvestBudgetMs) {
            if (full()) break
            var batch = store.pop(64)
            if (batch.isEmpty()) {
                // `pop()` records what it hands out, so a refill only yields
                // URLs this device has never processed. Rotating the query set
                // by round keeps that true for as long as there are queries.
                refillSeeds(round + refills)
                refills++
                batch = store.pop(32)
                FinderTrace.log("addon", "  refilled surface -> ${batch.size} new url(s)")
                if (batch.isEmpty()) break
            }
            val before = store.count("alive") + store.frontierSize()
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
            val after = store.count("alive") + store.frontierSize()
            barren = if (after > before) 0 else barren + 1
            FinderTrace.log(
                "addon",
                "  batch=${batch.size} alive=${store.count("alive")} " +
                    "frontier=${store.frontierSize()} barren=$barren"
            )
            // Only give up when there is genuinely nothing left to try.
            // A barren round with a non-empty frontier means the next
            // batch still has ground to cover -- breaking here is what
            // left the frontier at 0 after 18 of 211 urls.
            if (barren >= BARREN_ROUNDS && store.frontierSize() == 0) break
            delay(200)
        }
        FinderTrace.log(
            "addon",
            "harvest#$round END ${System.currentTimeMillis() - started}ms " +
                "alive=${store.count("alive")} drops=${rejectionReport().summary.ifEmpty { "-" }}"
        )
        return store.alive().take(cap).map { it.toFinderCandidate() }
    }

    /** Rejections observed since [resetRejections]; what this round threw away and why. */
    private val rejections = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun resetRejections() = rejections.clear()

    fun rejectionReport(): AddonRejections = AddonRejections(rejections.toMap())

    /** Record one dropped candidate. Called from [commit]'s early returns. */
    internal fun noteRejection(reason: String) {
        val key = reason.substringBefore('(').ifBlank { "unreachable" }
        rejections.merge(key, 1) { a, b -> a + b }
        // Every dropped candidate passes through here, so one hook covers the
        // whole addon gate chain without touching each early return.
        FinderTrace.count("addon.drop.$key")
    }

    /** How long one [harvest] call may crawl before returning what it has. */
    private var harvestBudgetMs: Long = 45_000L

    private companion object {
        /** Consecutive rounds that add nothing before a harvest gives up. */
        const val BARREN_ROUNDS = 2
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}

/**
 * Timed sweep: re-probe the weakest slice, quarantine, refill.
 * Python: `Checker` in addons.py.
 */
class AddonFinderChecker(
    private val store: AddonFinderStore,
    private val harvester: AddonFinderHarvester,
    private val interval: Int = AddonFinder.CHECK_INTERVAL,
    private val deadStore: PoolStore? = null,
    private val deadTxtStore: PoolStore? = null
) {
    private val dead = mutableListOf<AddonRecord>()

    suspend fun sweep() {
        val targets = store.weak(maxOf(4, store.count("alive") / 20))
        val sem = Semaphore(8)
        coroutineScope {
            targets.map { rec ->
                async {
                    sem.withPermit {
                        val v = harvester.validator.verify(rec.baseUrl)
                        val good = v.alive && v.nonTorrent && v.streams > 0
                        if (good) {
                            val updated = rec.copy(
                                lastChecked = FinderHelpers.now(),
                                ttfbMs = v.ttfbMs,
                                kbps = v.kbps,
                                bufferScore = v.bufferScore,
                                streamsProbed = v.streams,
                                failures = 0,
                                score = AddonFinder.scoreOf(rec.catalogue, rec.qualityTier, v.streams, v.bufferScore, v.ttfbMs)
                            )
                            store.upsert(updated, if (v.bufferScore < 40) "slow" else "alive")
                        } else {
                            val failures = rec.failures + 1
                            if (failures >= AddonFinder.FAILS_TO_DEAD) {
                                val deadRec = rec.copy(
                                    failures = failures,
                                    reason = v.reason.ifBlank { "unreachable" },
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
        if (!harvester.full()) harvester.runUntilCap()
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
