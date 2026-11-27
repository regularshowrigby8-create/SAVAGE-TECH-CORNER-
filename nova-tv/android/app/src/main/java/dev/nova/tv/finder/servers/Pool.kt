package dev.nova.tv.finder.servers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Capped, ranked, persisted. Cap 6000 across both natures; the lowest score
 * is evicted, never a random one. Recheck is two-strike.
 */
class Pool(private val file: File, private val cap: Int = 6000) {

    private class Entry(val host: String, val nature: Nature, val score: Double, val record: Any)

    private val byHost = ConcurrentHashMap<String, Entry>()
    private val order = PriorityBlockingQueue(16, compareBy<Entry> { it.score })

    init { load() }

    val size: Int get() = byHost.size
    fun full(): Boolean = byHost.size >= cap
    fun known(host: String) = byHost.containsKey(host)
    fun all(): List<Any> = byHost.values.map { it.record }

    fun putSite(r: SiteRecord): Boolean = insert(Entry(r.host, Nature.SITE, r.score, r))
    fun putAddon(r: DiscoveredAddon): Boolean = insert(Entry(r.host, Nature.ADDON, r.score, r))

    private fun insert(e: Entry): Boolean {
        if (byHost.containsKey(e.host)) return false
        if (full()) {
            val worst = order.poll() ?: return false
            if (worst.score >= e.score) { order.offer(worst); return false }
            byHost.remove(worst.host)
        }
        byHost[e.host] = e
        order.offer(e)
        return true
    }

    /** Returns the hosts that survived; evicts the ones that did not. */
    suspend fun recheck(candidates: Int = 40): Pair<List<String>, List<String>> {
        val lowest = byHost.values.sortedBy { it.score }.take(candidates)
        val dead = mutableListOf<String>()
        val live = mutableListOf<String>()
        lowest.forEach { e ->
            if (violate(e.host)) {
                dead += e.host
                byHost.remove(e.host)?.let { order.remove(it) }
            } else {
                order.remove(e)
                live += e.host
            }
        }
        return live to dead
    }

    private suspend fun violate(host: String): Boolean =
        !tcpAlive(host) || (Net.get("https://$host/", 40_000)?.status?.let { it >= 400 } ?: true)

    fun save() {
        val arr = JSONArray()
        byHost.values.forEach { e ->
            val o = JSONObject()
                .put("host", e.host)
                .put("n", e.nature.name)
                .put("s", e.score)
            when (val r = e.record) {
                is SiteRecord -> o.put("cat", r.catalogue)
                    .put("emb", r.embeds)
                    .put("sea", r.searchable)
                    .put("src", r.source.name)
                    .put("at", r.at)
                is DiscoveredAddon -> o.put("man", r.manifestUrl)
                    .put("cat", r.catalogue)
                    .put("str", r.streams)
                    .put("src", r.source.name)
                    .put("at", r.at)
            }
            arr.put(o)
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val host = o.getString("host")
                val nature = Nature.valueOf(o.getString("n"))
                val score = o.getDouble("s")
                val source = Source.entries.firstOrNull { it.name == o.optString("src") } ?: Source.LISTICLE
                val at = o.optLong("at", System.currentTimeMillis())
                val record: Any = if (nature == Nature.SITE) {
                    SiteRecord(host, score, o.optInt("cat"), o.optInt("emb"), o.optBoolean("sea"), source, at)
                } else {
                    DiscoveredAddon(host, o.optString("man"), score, o.optInt("cat"), o.optInt("str"), source, at)
                }
                val e = Entry(host, nature, score, record)
                byHost[host] = e
                order.offer(e)
            }
        } catch (e: Exception) { }
    }
}
