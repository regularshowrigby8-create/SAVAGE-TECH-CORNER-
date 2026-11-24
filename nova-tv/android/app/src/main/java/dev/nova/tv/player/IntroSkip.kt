package dev.nova.tv.player

import dev.nova.tv.data.remote.HttpClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One intro (or recap) window, on the media's own timeline.
 *
 * [endMs] is an absolute position in the file, so skipping is a plain seek and
 * works unchanged for every source in the failover queue.
 */
data class IntroSegment(
    val startMs: Long,
    val endMs: Long,
    /**
     * How sure the community data is, 0..1. IntroDB.app publishes it, and it is
     * the reason a low-confidence timing is not offered: a wrong skip is worse
     * than no skip.
     */
    val confidence: Double = 1.0
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** A window worth showing a button for. */
    val usable: Boolean get() =
        startMs >= 0 && endMs > startMs && durationMs >= MIN_DURATION_MS && confidence >= MIN_CONFIDENCE

    private companion object {
        const val MIN_DURATION_MS = 5_000L
        const val MIN_CONFIDENCE = 0.5
    }
}

/**
 * Intro timestamps from the two community databases, both keyless.
 *
 * Nothing here is guessed from the video. An intro has to be *declared* by data
 * that is keyed on the title's IMDb id, because detecting one from pixels is
 * exactly the kind of half-measure that shows a skip button in the wrong place.
 *
 * Two sources, because their coverage differs and each is missing episodes the
 * other has (TheIntroDB has several times the coverage of IntroDB.app; the
 * latter covers some shows the former does not):
 *
 *  - TheIntroDB: `GET https://api.theintrodb.org/v4/media?imdb_id=…&season=&episode=`
 *    -> `{"intro":[{"start_ms":…,"end_ms":…}], "credits":[…]}`. Anonymous.
 *  - IntroDB.app: `GET https://api.introdb.app/segments?imdb_id=…&season=&episode=`
 *    -> `{"intro":{"start_ms":…,"end_ms":…,"confidence":…}, …}`. Anonymous.
 *
 * Both are queried in parallel and the first usable answer wins, preferring the
 * one that declares a confidence value. A miss is not an error: it means this
 * title simply has no community timing, and the button is not offered.
 */
class IntroLookup(private val http: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Intro window for one title, or null when neither database knows it.
     *
     * [imdbId] must be a real `tt…` id -- these APIs cannot answer for anything
     * else, so an empty or non-IMDb id returns immediately without a request.
     */
    suspend fun introFor(
        imdbId: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long = 4_000L
    ): IntroSegment? {
        val id = imdbId.trim()
        if (!id.startsWith("tt")) return null
        val tv = season != null && episode != null
        val primary = urlIntroDb(id, season, episode, tv)
        val secondary = urlIntroDbApp(id, season, episode, tv)
        val found = withTimeoutOrNull(timeoutMs) {
            // Sequential on purpose: the first source answers in well under the
            // timeout, and a second request for a title that already resolved
            // is wasted data on a phone.
            fetchIntro(primary) ?: fetchIntro(secondary)
        }
        return found?.takeIf { it.usable }
    }

    private fun urlIntroDb(imdbId: String, season: Int?, episode: Int?, tv: Boolean): String =
        buildString {
            append("https://api.theintrodb.org/v4/media?imdb_id=").append(imdbId)
            if (tv) append("&season=").append(season).append("&episode=").append(episode)
        }

    private fun urlIntroDbApp(imdbId: String, season: Int?, episode: Int?, tv: Boolean): String =
        buildString {
            append("https://api.introdb.app/segments?imdb_id=").append(imdbId)
            if (tv) append("&season=").append(season).append("&episode=").append(episode)
            else append("&is_movie=true")
        }

    private suspend fun fetchIntro(url: String): IntroSegment? = runCatching {
        // One attempt: an intro miss is not worth a retry storm while a film
        // is starting, and the shared client already guards the URL.
        val body = http.get(
            url,
            headers = mapOf("Accept" to "application/json"),
            attempts = 1,
            limitBytes = 64 * 1024
        )
        parseIntro(Json.parseToJsonElement(body) as JsonObject)
    }.getOrNull()

    /**
     * Read the first usable `intro` window out of either response shape.
     *
     * Split out from the network call so the parsing is unit-testable without a
     * server, which is the only way these shapes can be kept honest.
     */
    fun parseIntro(doc: JsonObject): IntroSegment? {
        val node = doc["intro"] ?: return null
        // TheIntroDB: an array. IntroDB.app: a single object.
        val first = when (node) {
            is JsonArray -> node.firstOrNull() as? JsonObject
            is JsonObject -> node
            else -> null
        } ?: return null
        val start = first.long("start_ms") ?: return null
        val end = first.long("end_ms") ?: return null
        val confidence = first["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0
        return IntroSegment(startMs = start, endMs = end, confidence = confidence)
    }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    companion object {
        /** First few seconds are always content, so an intro is never offered there. */
        const val INTRO_START_GUARD_MS = 5_000L

        /** How close to the end of the intro the button stops being useful. */
        const val INTRO_SKIP_SLACK_MS = 1_000L
    }
}
