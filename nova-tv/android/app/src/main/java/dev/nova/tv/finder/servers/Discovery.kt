package dev.nova.tv.finder.servers

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Frontier plus a per-source precision ledger. The ledger is what keeps
 * "no seeding" from turning into "no filtering": a source that stops paying
 * gets parked instead of eaten.
 */
class Discovery(private val stateFile: File) {

    private val front = PriorityBlockingQueue(64, compareByDescending<Candidate> { it.priority })
    private val tried = ConcurrentHashMap<String, Boolean>()

    private class Stat(var attempts: Int = 0, var accepted: Int = 0) {
        fun ratio() = if (attempts == 0) 0.0 else accepted.toDouble() / attempts
    }
    private val stats = ConcurrentHashMap<Source, Stat>()

    init { load() }

    fun offer(raw: String, source: Source, parent: String? = null, agreement: Int = 1): Boolean {
        val key = Admission.key(raw) ?: return false
        if (tried.putIfAbsent(key, true) != null) return false
        if (!enabled(source)) return false
        return front.offer(Candidate(raw, source, parent, agreement))
    }

    fun poll(): Candidate? = front.poll()
    fun depth(): Int = front.size

    fun record(source: Source, ok: Boolean) {
        val s = stats.getOrPut(source) { Stat() }
        synchronized(s) { s.attempts++; if (ok) s.accepted++ }
        save()
    }

    /** Under 5% after 40 attempts is parked; 25%+ reopens. */
    fun enabled(source: Source): Boolean {
        val s = stats[source] ?: return true
        return synchronized(s) {
            when {
                s.attempts >= 40 && s.ratio() < 0.05 -> false
                s.attempts >= 10 && s.ratio() >= 0.25 -> true
                else -> s.attempts < 40 || s.ratio() >= 0.05
            }
        }
    }

    fun report(): Map<String, String> = Source.entries.associate { s ->
        val st = stats[s]
        s.name to "n=${st?.attempts ?: 0} acc=${st?.accepted ?: 0} r=${"%.2f".format(st?.ratio() ?: 0.0)}${if (enabled(s)) "" else " PARKED"}"
    }

    private fun save() {
        val o = JSONObject()
        stats.forEach { (s, st) -> o.put(s.name, JSONObject().put("a", st.attempts).put("c", st.accepted)) }
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(o.toString())
    }

    private fun load() {
        if (!stateFile.exists()) return
        try {
            val o = JSONObject(stateFile.readText())
            o.keys().forEach { k ->
                val s = Source.entries.firstOrNull { it.name == k } ?: return@forEach
                val b = o.getJSONObject(k)
                stats[s] = Stat(b.getInt("a"), b.getInt("c"))
            }
        } catch (e: Exception) { }
    }
}
