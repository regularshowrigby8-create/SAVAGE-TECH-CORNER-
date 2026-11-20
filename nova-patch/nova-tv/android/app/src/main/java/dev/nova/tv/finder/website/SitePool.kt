package dev.nova.tv.finder.website

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an imported site is handed on to the viewer's saved site list.
 *
 * A one-method interface rather than the whole `LibraryStore`: the pool only ever
 * needs to *add* a site, and depending on `SharedPreferences` would make this
 * class impossible to unit-test.
 */
fun interface SiteSink {
    fun add(site: SavedWebsite)
}

/** Where the site pool is saved; a file on device, a string in tests. */
interface SitePoolStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * One discovered streaming site.
 *
 * This is **not** a [dev.nova.tv.finder.FinderCandidate]. A candidate is an addon
 * manifest the app asks for streams; a site is a page [WebsiteFinder] resolves
 * itself, by following the page and up to two player frames to a direct
 * `.m3u8`/`.mp4`/`.mpd`. They are separate pools because they are separate
 * things, and mixing them would let an embed host be shown as a source that can
 * never play.
 *
 * [templates] are absolute search URLs with `{q}`/`{slug}` placeholders, the same
 * shape [SavedWebsite.templates] already uses, so an imported site needs no
 * second profiling pass.
 */
@Serializable
data class SiteRecord(
    val url: String,
    val name: String = "",
    val templates: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val score: Int = 0,
    val hits: Int = 0,
    val misses: Int = 0,
    /** Streams this site has actually produced for a title. Zero means unproven. */
    val resolved: Int = 0,
    val from: String = ""
) {
    fun toSite(): SavedWebsite = SavedWebsite(url = url, name = name, templates = templates)

    val host: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

@Serializable
private data class SiteDocument(
    val version: Int = 1,
    val target: Int = SitePool.CAPACITY,
    val generated: String = "",
    val entries: List<SiteRecord> = emptyList(),
    val rejected: Map<String, Int> = emptyMap()
)

data class SitePoolState(
    val size: Int = 0,
    /** Sites that have produced at least one playable stream. */
    val working: Int = 0,
    val capacity: Int = SitePool.CAPACITY,
    /** How many were dropped for failing verification, by reason. */
    val rejected: Map<String, Int> = emptyMap(),
    val lastImported: Int = 0,
    /** Non-empty when the pool could not be read; the Sources screen shows it. */
    val lastError: String = ""
) {
    val full: Boolean get() = size >= capacity
    val progress: Float get() = if (capacity <= 0) 0f else (size.toFloat() / capacity).coerceIn(0f, 1f)
    val rejectedTotal: Int get() = rejected.values.sum()
}

/**
 * Up to [CAPACITY] streaming sites, verified before they are kept.
 *
 * The crawler does the verification (a search on the site must return title
 * links), and this class trusts that but never *assumes* it: [import] only takes
 * entries whose templates actually contain a query placeholder, because a site
 * with no working search URL is a site the resolver can never use.
 *
 * [import] is the auto-import path the viewer asked for: whatever the crawler
 * verified goes straight into the Websites list, deduped by host, with the
 * existing user-added sites kept as they are.
 */
class SitePool(
    private val store: SitePoolStore,
    private val sites: SiteSink,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    private val lock = Any()
    private var document: SiteDocument = runCatching {
        store.read()?.let { json.decodeFromString<SiteDocument>(it) }
    }.getOrNull() ?: SiteDocument()

    /**
     * The last failure worth showing. Held here, not read back out of [_state]:
     * `MutableStateFlow(stateOf())` used to call a function that read `_state`,
     * which is the initialisation cycle the compiler reported as "type checking
     * has run into a recursive problem".
     */
    private var lastError: String = ""

    private val _state = MutableStateFlow(
        SitePoolState(
            size = document.entries.size,
            working = document.entries.count { it.resolved > 0 },
            rejected = document.rejected
        )
    )
    val state: StateFlow<SitePoolState> = _state.asStateFlow()

    fun sites(): List<SiteRecord> = synchronized(lock) { document.entries }

    /** Only sites that have produced a stream, best first. What the UI offers. */
    fun working(): List<SiteRecord> = synchronized(lock) {
        document.entries.filter { it.resolved > 0 }
            .sortedWith(compareByDescending<SiteRecord> { it.resolved }.thenByDescending { it.score })
    }

    /** Best candidates for a title: proven first, then the highest-scoring untried. */
    fun batch(size: Int): List<SavedWebsite> = synchronized(lock) {
        document.entries
            .sortedWith(compareByDescending<SiteRecord> { it.resolved }
                .thenBy { it.misses }.thenByDescending { it.score })
            .take(size)
            .map { it.toSite() }
    }

    fun noteResult(host: String, resolved: Int, miss: Boolean) = synchronized(lock) {
        document = document.copy(entries = document.entries.map {
            if (it.host.lowercase() != host.lowercase()) it
            else it.copy(
                resolved = it.resolved + resolved,
                hits = if (resolved > 0) it.hits + 1 else it.hits,
                misses = if (miss && resolved == 0) it.misses + 1 else it.misses
            )
        })
        persist()
    }

    /**
     * Merge a crawler document into the pool and into the app's site list.
     *
     * Returns how many were new. A record without a `{q}`/`{slug}` template is
     * skipped and counted: it would be a site the resolver cannot search, and
     * adding it would produce exactly the dead entry this whole change exists to
     * avoid.
     */
    fun import(text: String, addToLibrary: Boolean = true): Int = synchronized(lock) {
        // A malformed document is reported, not treated as "nothing to import":
        // a silent 0 here is indistinguishable from an empty crawl, and the
        // viewer would be left wondering why the pool never filled.
        val incoming = runCatching { json.decodeFromString<SiteDocument>(text) }.getOrElse { error ->
            lastError = "site pool could not be read: ${error.message}"
            lastImported = 0
            publish()
            return 0
        }
        val byHost = document.entries.associateBy { it.host.lowercase() }.toMutableMap()
        var added = 0
        for (record in incoming.entries) {
            val host = record.host.lowercase()
            if (host.isEmpty() || host in byHost || host !in byHost && byHost.size >= CAPACITY) continue
            if (record.templates.none { it.contains("{q}") || it.contains("{slug}") }) continue
            byHost[host] = record
            added += 1
            if (addToLibrary) sites.add(record.toSite())
        }
        document = document.copy(
            entries = byHost.values.take(CAPACITY),
            rejected = incoming.rejected,
            generated = incoming.generated
        )
        lastImported = added
        lastError = ""
        persist()
        added
    }

    /**
     * Record that the bundled pool could not be read. Surfaced through
     * [state] so Sources can say why it is empty instead of showing a zero that
     * looks identical to a crawl that found nothing.
     */
    fun noteBundledPoolMissing(reason: String) = synchronized(lock) {
        lastError = "bundled site pool missing: $reason"
        publish()
    }

    /** Whether the crawler's pool is on this device at all. */
    val hasPool: Boolean get() = synchronized(lock) { document.entries.isNotEmpty() }

    private var lastImported: Int = 0

    private fun publish() {
        _state.value = SitePoolState(
            size = document.entries.size,
            working = document.entries.count { it.resolved > 0 },
            rejected = document.rejected,
            lastImported = lastImported,
            lastError = lastError
        )
    }

    private fun persist() {
        runCatching { store.write(json.encodeToString(SiteDocument.serializer(), document)) }
        publish()
    }

    companion object {
        /**
         * The site pool is a separate 3000 from the manifest pool's 3000. Two
         * pools, two ceilings, two numbers on the Sources screen.
         */
        const val CAPACITY = 3000
    }
}
