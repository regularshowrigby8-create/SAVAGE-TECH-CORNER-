package dev.nova.tv.finder.pool

import dev.nova.tv.finder.website.SavedWebsite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One streaming website the finder discovered, saved in the same shape as a
 * website the viewer added by hand: an address plus the search templates that
 * worked on it.
 */
@Serializable
data class SiteEntry(
    val url: String,
    val name: String = "",
    val templates: List<String> = emptyList(),
    val hits: Int = 0,
    val misses: Int = 0,
    val from: String = ""
) {
    fun toSite() = SavedWebsite(url = url, name = name, templates = templates)
}

data class WebsitePoolState(
    val size: Int = 0,
    /** Sites that have actually returned a playable stream for a title. */
    val working: Int = 0,
    val capacity: Int = WebsitePool.CAPACITY
)

@Serializable
private data class WebsiteSnapshot(
    val entries: List<SiteEntry> = emptyList(),
    val retired: List<String> = emptyList()
)

/**
 * The website-server half of Nova's sources.
 *
 * This is deliberately a **different pool** from [ServerPool]: that one holds
 * addon manifests and asks them for `/stream/...`, this one holds ordinary
 * websites -- the same kind of source the viewer adds with "Add website" --
 * and searches them by title. One pool can be switched off while the other
 * keeps feeding playback.
 *
 * Sites are keyed by host, because a site is the unit here: two paths on the
 * same host are the same website and would only double the requests.
 */
class WebsitePool(private val store: PoolStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var data: WebsiteSnapshot =
        runCatching { store.read()?.let { json.decodeFromString<WebsiteSnapshot>(it) } }.getOrNull() ?: WebsiteSnapshot()

    private val _state = MutableStateFlow(stateOf())
    val state: StateFlow<WebsitePoolState> = _state.asStateFlow()

    fun entries(): List<SiteEntry> = synchronized(lock) { data.entries }

    /** Sites that answered for at least one title; the only ones worth asking first. */
    fun working(): List<SiteEntry> = synchronized(lock) {
        data.entries.filter { it.hits > 0 }
            .sortedWith(compareByDescending<SiteEntry> { it.hits }.thenBy { it.misses })
    }

    fun sites(): List<SavedWebsite> = working().map { it.toSite() }

    /** Merge discovered sites; returns how many were new. */
    fun add(sites: List<SavedWebsite>, from: String = ""): Int = synchronized(lock) {
        val known = data.entries.map { key(it.url) }.toHashSet()
        val retired = data.retired.toHashSet()
        val merged = data.entries.toMutableList()
        var added = 0
        for (site in sites) {
            val k = key(site.url)
            if (k.isEmpty() || k in known || k in retired) continue
            if (merged.size >= CAPACITY) break
            merged += SiteEntry(
                url = site.url,
                name = site.name,
                templates = site.templates,
                from = from
            )
            known += k
            added++
        }
        if (added > 0) {
            data = data.copy(entries = merged)
            save()
        }
        added
    }

    /**
     * The next [size] sites to search for one title: proven ones first, then
     * the rest in discovery order. [skip] removes hosts already handled.
     */
    fun batch(size: Int, skip: (String) -> Boolean): List<SavedWebsite> = synchronized(lock) {
        data.entries
            .filterNot { skip(it.url) }
            .sortedWith(compareByDescending<SiteEntry> { it.hits }.thenBy { it.misses })
            .take(size)
            .map { it.toSite() }
    }

    fun noteResult(url: String, hit: Boolean) = synchronized(lock) {
        val k = key(url)
        var dead = false
        val updated = data.entries.map { entry ->
            if (key(entry.url) != k) entry
            else if (hit) entry.copy(hits = entry.hits + 1)
            else entry.copy(misses = entry.misses + 1).also { if (it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES) dead = true }
        }
        if (dead) {
            val gone = updated.filter { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES }
            data = data.copy(
                entries = updated - gone.toSet(),
                retired = (data.retired + gone.map { key(it.url) }).distinct().takeLast(MAX_RETIRED)
            )
            save()
        } else {
            data = data.copy(entries = updated)
            publish()
        }
    }

    /** Drop sites that failed twice and never answered. Returns how many. */
    fun pruneDead(): Int = synchronized(lock) {
        val dead = data.entries.filter { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES }
        if (dead.isEmpty()) return@synchronized 0
        data = data.copy(
            entries = data.entries - dead.toSet(),
            retired = (data.retired + dead.map { key(it.url) }).distinct().takeLast(MAX_RETIRED)
        )
        save()
        dead.size
    }

    /** Forget the retirement list, so a site that came back is reachable again. */
    fun clearRetired(): Int = synchronized(lock) {
        val n = data.retired.size
        data = data.copy(retired = emptyList())
        save()
        n
    }

    /** Remove one site by address (the Sources screen shows the proven ones). */
    fun remove(url: String): Boolean = synchronized(lock) {
        val k = key(url)
        val next = data.entries.filterNot { key(it.url) == k }
        if (next.size == data.entries.size) return@synchronized false
        data = data.copy(entries = next, retired = (data.retired + k).distinct().takeLast(MAX_RETIRED))
        save()
        true
    }

    fun flush() = synchronized(lock) { save() }

    private fun stateOf() = WebsitePoolState(
        size = data.entries.size,
        working = data.entries.count { it.hits > 0 }
    )

    private fun publish() { _state.value = stateOf() }

    private fun save() {
        runCatching { store.write(json.encodeToString(WebsiteSnapshot.serializer(), data)) }
        publish()
    }

    companion object {
        /** Same 10,000-slot ceiling as the addon pool, so neither is the bottleneck. */
        const val CAPACITY = 10_000
        const val RETIRE_AFTER_MISSES = 2
        private const val MAX_RETIRED = 20_000

        /** "https://www.example.com/watch?a=1" -> "example.com". */
        fun key(url: String): String = runCatching {
            java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.").orEmpty()
        }.getOrDefault("")
    }
}
