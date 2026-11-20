package dev.nova.tv.finder.pool

import dev.nova.tv.finder.FinderCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One server option. Stats are global (across titles), so good servers stay. */
@Serializable
data class PoolEntry(
    val manifestUrl: String,
    val baseUrl: String,
    val name: String = "",
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val stars: Int = 0,
    val from: String = "",
    val hits: Int = 0,
    val misses: Int = 0
) {
    fun toCandidate() = FinderCandidate(
        manifestUrl = manifestUrl, baseUrl = baseUrl, name = name, description = "", version = "",
        types = types, idPrefixes = idPrefixes, searchable = false, stars = stars, from = from
    )
}

@Serializable
private data class Snapshot(val generation: Int, val entries: List<PoolEntry>)

@Serializable
private data class Persisted(
    val generation: Int = 0,
    val entries: List<PoolEntry> = emptyList(),
    val history: List<Snapshot> = emptyList(),
    val retired: List<String> = emptyList(),
    val cursor: Int = 0
)

data class PoolState(
    val size: Int = 0,
    /** Servers that have answered for at least one title; the only ones offered. */
    val working: Int = 0,
    val capacity: Int = ServerPool.CAPACITY,
    val generation: Int = 0,
    val canRevert: Boolean = false,
    val retired: Int = 0,
    /** How many options the last scramble brought in that were never in the pool. */
    val lastAdded: Int = 0
)

/** Where the pool is saved; a file on device, a string in tests. */
interface PoolStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * Up to [CAPACITY] server options at once.
 *
 *  - [add] merges what the finders discover. The same server is never added
 *    twice: entries are keyed by [key] (scheme-less host + path, lower case,
 *    no manifest.json), and one host may hold at most [PER_HOST] entries, so a
 *    hundred configurations of one addon cannot crowd out everything else.
 *  - [scramble] runs when a title is not found: the current pool is pushed to
 *    history, servers that failed twice and never answered for anything are
 *    retired (never re-added), and the freed slots are filled with options that
 *    have never been in the pool, in shuffled order.
 *  - [revert] restores the previous pool exactly and un-retires its servers.
 */
class ServerPool(private val store: PoolStore, private val random: Random = Random.Default) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var data: Persisted = runCatching { store.read()?.let { json.decodeFromString<Persisted>(it) } }.getOrNull() ?: Persisted()
    private var lastAdded = 0

    private val _state = MutableStateFlow(stateOf())
    val state: StateFlow<PoolState> = _state.asStateFlow()

    /** Pages already harvested; the harvester resumes from here. */
    var cursor: Int
        get() = synchronized(lock) { data.cursor }
        set(value) = synchronized(lock) { data = data.copy(cursor = value); save() }

    fun entries(): List<PoolEntry> = synchronized(lock) { data.entries }

    /**
     * Only servers that have actually answered for a title. The pool may hold
     * 3000 discoveries, but an unproven one is not a source the UI should show:
     * the Sources screen lists exactly what has worked.
     */
    fun working(): List<PoolEntry> = synchronized(lock) {
        data.entries.filter { it.hits > 0 }
            .sortedWith(compareByDescending<PoolEntry> { it.hits }.thenBy { it.misses }.thenByDescending { it.stars })
    }

    /** Merge discovered servers; returns how many were new. */
    fun add(candidates: List<FinderCandidate>): Int = synchronized(lock) {
        val (merged, added) = merge(data.entries, candidates.map { it.toEntry() }, data.retired.toHashSet())
        data = data.copy(entries = merged)
        save()
        added
    }

    /**
     * The next [size] servers to ask for one title: proven ones first, then
     * untried, then the rest; [skip] removes ones this title already asked.
     */
    fun batch(size: Int, skip: (String) -> Boolean): List<FinderCandidate> = synchronized(lock) {
        data.entries
            .filterNot { skip(it.baseUrl) }
            .sortedWith(compareByDescending<PoolEntry> { it.hits }.thenBy { it.misses }.thenByDescending { it.stars })
            .take(size)
            .map { it.toCandidate() }
    }

    fun noteResult(baseUrl: String, hit: Boolean) = synchronized(lock) {
        val k = key(baseUrl)
        val updated = data.entries.map {
            if (key(it.baseUrl) != k) it else if (hit) it.copy(hits = it.hits + 1) else it.copy(misses = it.misses + 1)
        }
        // A server that has failed [RETIRE_AFTER_MISSES] times and never once
        // answered is dead: drop it now, and remember it so it is never
        // re-added. Waiting for the next scramble kept dead hosts in the pool
        // and wasted a request on every title.
        val dead = updated.filter { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES }
        val retired = if (dead.isEmpty()) data.retired
        else (data.retired + dead.map { key(it.baseUrl) }).distinct().takeLast(MAX_RETIRED)
        data = data.copy(
            entries = if (dead.isEmpty()) updated else updated.filterNot { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES },
            retired = retired
        )
        // Saved on the next add / scramble; stats are cheap to lose, writes are not.
        publish()
    }

    /**
     * Drop every server that has proven itself dead (never answered, failed
     * [RETIRE_AFTER_MISSES] times) and return how many slots that freed.
     *
     * Called by the fill loop, so the pool is continuously self-cleaning while
     * it grows toward [CAPACITY]: a host that goes down mid-run is evicted and
     * its slot goes to the next discovery instead of being retried forever.
     */
    fun pruneDead(): Int = synchronized(lock) {
        val dead = data.entries.filter { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES }
        if (dead.isEmpty()) return@synchronized 0
        data = data.copy(
            entries = data.entries - dead.toSet(),
            retired = (data.retired + dead.map { key(it.baseUrl) }).distinct().takeLast(MAX_RETIRED)
        )
        save()
        dead.size
    }

    /** Forget the retirement list, so a host that came back can be found again. */
    fun clearRetired(): Int = synchronized(lock) {
        val n = data.retired.size
        data = data.copy(retired = emptyList())
        save()
        n
    }

    /** Write hit / miss counts; the engine calls this when a title finishes. */
    fun flush() = synchronized(lock) { save() }

    /** A title was not found: rotate in new servers. Returns how many are new. */
    fun scramble(fresh: List<FinderCandidate>): Int = synchronized(lock) {
        val previous = data.entries
        // Nothing to go back to: just fill, no history entry.
        if (previous.isEmpty()) {
            val (merged, added) = merge(emptyList(), fresh.map { it.toEntry() }.shuffled(random), data.retired.toHashSet())
            lastAdded = added
            data = data.copy(entries = merged)
            save()
            return@synchronized added
        }
        val dead = previous.filter { it.hits == 0 && it.misses >= RETIRE_AFTER_MISSES }.map { key(it.baseUrl) }
        val retired = (data.retired + dead).distinct().takeLast(MAX_RETIRED)
        val keep = previous.filter { it.hits > 0 }
        val untried = previous.filter { it.hits == 0 && it.misses == 0 }
        val known = previous.map { key(it.baseUrl) }.toHashSet()
        val newOnes = fresh.map { it.toEntry() }.filter { key(it.baseUrl) !in known }.shuffled(random)
        // Proven servers stay; never-seen options come next; untried old ones fill the rest.
        val (merged, _) = merge(keep, newOnes + untried.shuffled(random), retired.toHashSet())
        lastAdded = merged.count { key(it.baseUrl) !in known }
        data = data.copy(
            generation = data.generation + 1,
            entries = merged,
            history = (data.history + Snapshot(data.generation, previous)).takeLast(MAX_HISTORY),
            retired = retired
        )
        save()
        lastAdded
    }

    /** Go back to the previous pool. False when there is nothing to go back to. */
    fun revert(): Boolean = synchronized(lock) {
        val last = data.history.lastOrNull() ?: return false
        val restored = last.entries.map { key(it.baseUrl) }.toHashSet()
        data = data.copy(
            generation = last.generation,
            entries = last.entries,
            history = data.history.dropLast(1),
            retired = data.retired.filterNot { it in restored }
        )
        lastAdded = 0
        save()
        true
    }

    private fun merge(base: List<PoolEntry>, incoming: List<PoolEntry>, retired: Set<String>): Pair<List<PoolEntry>, Int> {
        val out = LinkedHashMap<String, PoolEntry>()
        val perHost = HashMap<String, Int>()
        fun offer(e: PoolEntry): Boolean {
            val k = key(e.baseUrl)
            if (k.isEmpty() || k in out || k in retired) return false
            val host = k.substringBefore('/')
            if ((perHost[host] ?: 0) >= PER_HOST) return false
            if (out.size >= CAPACITY) return false
            perHost[host] = (perHost[host] ?: 0) + 1
            out[k] = e
            return true
        }
        base.forEach { offer(it) }
        var added = 0
        incoming.forEach { if (offer(it)) added++ }
        return out.values.toList() to added
    }

    private fun stateOf() = PoolState(
        size = data.entries.size,
        working = data.entries.count { it.hits > 0 },
        generation = data.generation,
        canRevert = data.history.isNotEmpty(),
        retired = data.retired.size,
        lastAdded = lastAdded
    )

    private fun publish() { _state.value = stateOf() }

    private fun save() {
        runCatching { store.write(json.encodeToString(Persisted.serializer(), data)) }
        publish()
    }

    private fun FinderCandidate.toEntry() = PoolEntry(
        manifestUrl = manifestUrl, baseUrl = baseUrl, name = name, types = types,
        idPrefixes = idPrefixes, stars = stars, from = from
    )

    companion object {
        const val CAPACITY = 3000
        const val PER_HOST = 4
        const val RETIRE_AFTER_MISSES = 2
        private const val MAX_HISTORY = 3
        private const val MAX_RETIRED = 20_000

        /** "https://Torrentio.strem.fun/abc/manifest.json" → "torrentio.strem.fun/abc". */
        fun key(url: String): String = url.trim().lowercase()
            .substringAfter("://")
            .replace(Regex("""/manifest\.json.*$"""), "")
            .substringBefore('?').substringBefore('#')
            .trimEnd('/')
    }
}
