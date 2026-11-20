package dev.nova.tv.finder.pool

import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/** A candidate that passed [ServerTester]; [sampleStreams] is what it answered with. */
data class TestedServer(
    val candidate: FinderCandidate,
    /** Playable streams returned for the probe title; 0 means "alive but empty". */
    val sampleStreams: Int,
    val elapsedMs: Long
)

/** What one testing round did, for the Sources screen. */
data class TestReport(
    val tested: Int = 0,
    val passed: Int = 0,
    val rejected: Int = 0,
    val reasons: Map<String, Int> = emptyMap()
)

/**
 * The gate between discovery and storage.
 *
 * The old pool stored whatever a directory listed, so a manifest that had been
 * dead for a year, a torrent index, or a VOD front-end that demands an account
 * all occupied one of the 3000 slots and cost a request on every title. This
 * tester proves a server works *before* [ServerPool] is allowed to keep it:
 *
 *  1. the manifest is fetched fresh and must still declare a `stream` resource
 *     for film or series (a stale directory entry fails here);
 *  2. the server is asked for a real title (`tt0111161`) and must answer with a
 *     JSON object containing a `streams` array -- a 404, a login page or an
 *     HTML error is not a stream server;
 *  3. the rules gate rejects torrents, Internet Archive, duplicate VOD
 *     front-ends and account walls by name and host.
 *
 * A server that answers with an empty `streams` array is kept: it is alive and
 * simply does not carry that one title. A server that does not answer at all is
 * dropped, and because it never enters the pool it is never retried.
 *
 * Testing is [CONCURRENCY]-way parallel and every step is time-boxed, so a
 * thousand candidates cost a few seconds rather than a few minutes.
 */
class ServerTester(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Results are cached for the process lifetime; a dead host stays dead. */
    private val cache = ConcurrentHashMap<String, TestedServer?>()

    /** Test [candidates] and return only the ones that answered. */
    suspend fun test(candidates: List<FinderCandidate>): List<TestedServer> =
        testWithReport(candidates).first

    /** Test [candidates] and also report why the rest were dropped. */
    suspend fun testWithReport(candidates: List<FinderCandidate>): Pair<List<TestedServer>, TestReport> {
        val unique = candidates
            .filter { FinderRules.isStorable(it.name, it.baseUrl) }
            .distinctBy { ServerPool.key(it.baseUrl) }
        val gate = Semaphore(CONCURRENCY)
        val reasons = ConcurrentHashMap<String, Int>()
        val results = coroutineScope {
            unique.map { candidate ->
                async {
                    gate.withPermit {
                        val key = ServerPool.key(candidate.baseUrl)
                        val cached = cache[key]
                        if (cache.containsKey(key)) {
                            if (cached != null) cached else null
                        } else {
                            val (result, reason) = testOne(candidate)
                            cache[key] = result
                            if (reason != null) reasons.merge(reason, 1, Int::plus)
                            result
                        }
                    }
                }
            }.awaitAll()
        }
        val passed = results.filterNotNull()
        return passed to TestReport(
            tested = unique.size,
            passed = passed.size,
            rejected = unique.size - passed.size,
            reasons = reasons.toMap()
        )
    }

    /** One server, end to end. Returns the result (or null) and a rejection reason. */
    private suspend fun testOne(candidate: FinderCandidate): Pair<TestedServer?, String?> {
        val started = System.currentTimeMillis()
        val base = candidate.baseUrl.trimEnd('/')
        if (base.isEmpty()) return null to "no-base"

        // 1. The manifest must still be a live stream addon.
        val manifestUrl = candidate.manifestUrl.ifBlank { "$base/manifest.json" }
        val manifestText = withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
            runCatching {
                http.get(
                    manifestUrl,
                    headers = ACCEPT_JSON,
                    attempts = 1,
                    limitBytes = FinderRules.MAX_MANIFEST_BYTES.toLong()
                )
            }.getOrNull()
        } ?: return null to "manifest-unreachable"

        val manifest = runCatching { json.parseToJsonElement(manifestText) }.getOrNull()
            ?: return null to "manifest-not-json"
        val info = FinderRules.parseManifest(manifest) ?: return null to "not-a-stream-addon"
        if (!FinderRules.isStorable(info.name, base)) return null to "redundant-or-signup"

        // 2. It must answer a real request with a streams array.
        val probeId = if (info.types.contains("movie")) PROBE_MOVIE else PROBE_SERIES
        val probeType = if (info.types.contains("movie")) "movie" else "series"
        val streamUrl = "$base/stream/$probeType/${FinderRules.streamSegment(probeId)}.json"
        val streamText = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            runCatching {
                http.get(
                    streamUrl,
                    headers = ACCEPT_JSON,
                    attempts = 1,
                    limitBytes = FinderRules.MAX_JSON_BYTES.toLong()
                )
            }.getOrNull()
        } ?: return null to "stream-endpoint-unreachable"

        val obj = runCatching { json.parseToJsonElement(streamText) as? JsonObject }.getOrNull()
            ?: return null to "stream-not-json"
        val array = obj["streams"] as? JsonArray ?: return null to "no-streams-key"

        // 3. Count what it actually answered with. A playable entry is proof
        //    the server is useful; an empty array is proof it is alive.
        val playable = array.filterIsInstance<JsonObject>().count { entry ->
            val url = (entry["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val external = (entry["externalUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val infoHash = (entry["infoHash"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val nzb = (entry["nzbUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val yt = (entry["ytId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (infoHash.isNotBlank() || nzb.isNotBlank() || yt.isNotBlank()) return@count false
            val target = url.ifBlank { external }
            if (target.isBlank()) return@count false
            target.startsWith("http://", true) || target.startsWith("https://", true)
        }

        return TestedServer(
            candidate = candidate.copy(
                name = info.name.ifBlank { candidate.name },
                types = info.types.ifEmpty { candidate.types },
                idPrefixes = info.idPrefixes.ifEmpty { candidate.idPrefixes }
            ),
            sampleStreams = playable,
            elapsedMs = System.currentTimeMillis() - started
        ) to null
    }

    companion object {
        /** Candidates tested at once. Discovery is already parallel; this is the gate. */
        const val CONCURRENCY = 32
        private const val MANIFEST_TIMEOUT_MS = 7_000L
        private const val STREAM_TIMEOUT_MS = 8_000L

        /** A film almost every catalogue carries; used only to prove liveness. */
        const val PROBE_MOVIE = "tt0111161"
        const val PROBE_SERIES = "tt0903747:1:1"

        private val ACCEPT_JSON = mapOf(
            "Accept" to "application/json,text/plain;q=0.9,*/*;q=0.8",
            "User-Agent" to "NovaTV-ServerTester/1.0"
        )
    }
}
