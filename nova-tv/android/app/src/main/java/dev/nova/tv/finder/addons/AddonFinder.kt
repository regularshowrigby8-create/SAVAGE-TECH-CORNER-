package dev.nova.tv.finder.addons

import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.pool.PoolStore
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

    /** Discovery surfaces. Only the engine and the cap are hardcoded. */
    val SEEDS = listOf(
        "https://api.github.com/search/repositories?q=stremio+addon+in:name,description,readme&sort=updated&per_page=100",
        "https://api.npms.io/v2/search?q=stremio%20addon&size=250",
        "https://registry.npmjs.org/-/v1/search?text=stremio%20addon&size=250",
        "https://www.reddit.com/r/StremioAddons/new.json?limit=100",
        "https://www.reddit.com/r/StremioAddons/search.json?q=manifest.json&restrict_sr=1&sort=new&limit=100",
        "https://stremio-addons.net",
        "https://stremio-addons.com"
    )

    /** Manifest paths tried against every discovered origin, cheapest first. */
    val MANIFEST_PATHS = listOf(
        "/manifest.json",
        "/manifest",
        "/api/manifest.json",
        "/addon/manifest.json",
        "/stremio/manifest.json",
        "/manifest.json?t=1"
    )

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
    val frontier: List<String> = emptyList()
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

    init {
        runCatching {
            store.read()?.let { json.decodeFromString<AddonStoreFile>(it) }?.let { file ->
                file.addons.forEach { addons[it.id] = it }
                frontier += file.frontier
            }
        }
    }

    private fun save() {
        runCatching {
            store.write(json.encodeToString(AddonStoreFile.serializer(), AddonStoreFile(addons.values.toList(), frontier.toList())))
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
        frontier += n
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
        addons.values.any { it.host == host }
    }

    fun weak(n: Int): List<AddonRecord> = synchronized(lock) {
        addons.values.filter { it.status != "dead" }.sortedBy { it.score }.take(n)
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
        for (path in AddonFinder.MANIFEST_PATHS) {
            val r = http.get(base.trimEnd('/') + path)
            val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
            if (doc != null && AddonFinder.MANIFEST_KEYS.all { it in doc }) return doc
        }
        return null
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
        if (seen.isEmpty()) { v.reason = "no-streams"; return v }
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

    fun loadBlocklist(hosts: Set<String>) {
        blocked = hosts
    }

    /** Queue an origin unless its host is quarantined or already known. */
    fun queue(url: String): Boolean {
        val host = FinderHelpers.hostOf(url)
        if (host.isEmpty() || host in blocked || store.knownHost(host)) return false
        return store.push(FinderHelpers.originOf(url))
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
                for (path in listOf("manifest.json", "public/manifest.json", "addon/manifest.json")) {
                    val raw = "https://raw.githubusercontent.com/$fullName/$branch/$path"
                    if (store.push(raw)) validateRaw(raw)
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
            for (key in listOf("homepage", "repository", "demo")) {
                val link = (links?.get(key) ?: pkg[key]).str()
                if (link != null && link.startsWith("http")) {
                    if ("github.com" in link) continue
                    if (queue(link) && !full()) validateHost(FinderHelpers.originOf(link))
                }
            }
        }
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
                val host = FinderHelpers.hostOf(unescape(link))
                if (host.isEmpty() || host in listOf("github.com", "raw.githubusercontent.com", "reddit.com")) continue
                if ("reddit.com" in host || "imgur" in host || "youtube" in host) continue
                if (queue(link) && !full()) validateHost(FinderHelpers.originOf(link))
            }
        }
    }

    suspend fun fromWww(url: String) {
        val r = http.get(url)
        var html = r?.text.orEmpty()
        if (browser != null && html.length < 4000) {
            html = browser.renderText(url).ifBlank { html }
        }
        for (linkIn in AddonFinder.LINK.findAll(html).map { it.value }) {
            if (full()) return
            val link = unescape(linkIn)
            val host = FinderHelpers.hostOf(link)
            if (host.isEmpty() || host in blocked) continue
            if (host.endsWith("github.com") || host.endsWith("npmjs.com") || host.endsWith("reddit.com") || host.endsWith("githubusercontent.com")) continue
            if (queue(link)) validateHost(FinderHelpers.originOf(link))
        }
    }

    // -- validation ---------------------------------------------------------

    /** Check a manifest.json found at a raw URL without a base guess. */
    suspend fun validateRaw(raw: String) {
        val r = http.get(raw)
        val doc = if (r != null && r.status < 400) r.json() as? JsonObject else null
        if (doc == null || !AddonFinder.MANIFEST_KEYS.all { it in doc }) return
        commit(FinderHelpers.originOf(raw), validator.verify(FinderHelpers.originOf(raw)))
    }

    suspend fun validateHost(base: String) {
        commit(base, validator.verify(base))
    }

    /** Persist a candidate that passed every gate, ranked. */
    suspend fun commit(base: String, v: AddonVerdict) {
        if (!v.alive || !v.nonTorrent || v.streams == 0) {
            noteRejection(
                when {
                    !v.alive -> v.reason.ifBlank { "unreachable" }
                    !v.nonTorrent -> "torrent"
                    else -> "no-streams"
                }
            )
            return
        }
        if (store.knownHost(FinderHelpers.hostOf(base))) {
            noteRejection("already-known")
            return
        }
        val doc = validator.manifest(base) ?: JsonObject(emptyMap())
        val catalogueCount = validator.catalogue(base, doc)
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

    /** Route one frontier entry to the right surface handler. */
    suspend fun process(url: String) {
        val host = FinderHelpers.hostOf(url)
        when {
            "api.github.com" in host -> fromGithub(url)
            "npms.io" in host || "npmjs.org" in host -> fromNpm(url)
            "reddit.com" in host -> fromReddit(url)
            else -> fromWww(url)
        }
    }

    fun refillSeeds() {
        for (s in AddonFinder.SEEDS) store.push(s)
    }

    suspend fun run() {
        for (s in AddonFinder.SEEDS) store.push(s)
        while (!full()) {
            var batch = store.pop(64)
            if (batch.isEmpty()) {
                refillSeeds()
                batch = store.pop(32)
                if (batch.isEmpty()) break
            }
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
            delay(200)
        }
    }

    /** Bounded refill used by the checker after a sweep. */
    suspend fun runUntilCap(rounds: Int = 3) {
        repeat(rounds) {
            if (full()) return
            var batch = store.pop(48)
            if (batch.isEmpty()) {
                refillSeeds()
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
     * stays responsive, and it refills the seeds when the frontier drains,
     * exactly as the Python does.
     */
    suspend fun harvest(round: Int): List<FinderCandidate> {
        val started = System.currentTimeMillis()
        // `push()` rejects a URL only while it is in the frontier and `pop()`
        // removes it, so `refillSeeds()` re-queues seeds it has just consumed.
        // With nothing new to follow, the loop re-fetched the same seven URLs
        // for its whole budget -- which is why the pool sat at a fraction of
        // capacity (198/6000 on device) rather than reporting the surface dry.
        val visited = HashSet<String>()
        var barren = 0
        while (System.currentTimeMillis() - started < harvestBudgetMs) {
            if (full()) break
            var batch = store.pop(64).filter { visited.add(it) }
            if (batch.isEmpty()) {
                refillSeeds()
                batch = store.pop(32).filter { visited.add(it) }
                if (batch.isEmpty()) break
            }
            val before = store.count("alive") + store.frontierSize()
            coroutineScope { batch.map { u -> async { process(u) } }.awaitAll() }
            val after = store.count("alive") + store.frontierSize()
            barren = if (after > before) 0 else barren + 1
            if (barren >= BARREN_ROUNDS) break
            delay(200)
        }
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
