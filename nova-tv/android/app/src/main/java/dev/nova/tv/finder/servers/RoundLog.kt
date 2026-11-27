package dev.nova.tv.finder.servers

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Append-only ledger. A ticket that closes unresolved resolves as UNRECORDED,
 * so a cancel, a throw or a process kill still writes a line and audit()
 * always balances. Resume reads the host keys back out of earlier rounds.
 */
class RoundLog(
    private val file: File,
    private val roundId: String,
    private val flushEvery: Int = 20
) : Closeable {

    private val out = FileOutputStream(file, true)
    private val lock = Any()
    private val seq = AtomicInteger()
    private val entered = AtomicInteger()
    private val seen = ConcurrentHashMap<String, Byte>()

    init { loadSeen() }

    fun enter(host: String, source: Source): Ticket {
        entered.incrementAndGet()
        val fresh = seen.putIfAbsent(host, 1) == null
        return Ticket(host, source, fresh)
    }

    inner class Ticket internal constructor(
        val host: String,
        private val source: Source,
        private val fresh: Boolean
    ) : Closeable {
        private val done = AtomicBoolean()
        val isNewHost: Boolean get() = fresh
        val from: Source get() = source

        fun resolve(o: Outcome, detail: String = "") {
            if (!done.compareAndSet(false, true)) return
            write(o, host, detail)
        }

        override fun close() = resolve(Outcome.UNRECORDED)
    }

    fun hosts(): Set<String> = seen.keys

    fun snapshot(): Map<Outcome, Int> {
        val out = Outcome.entries.associateWith { 0 }.toMutableMap()
        if (!file.exists()) return out
        file.useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                if (p.size >= 3 && p[0] == roundId) {
                    val o = Outcome.entries.firstOrNull { it.name == p[2] } ?: return@forEach
                    out[o] = (out[o] ?: 0) + 1
                }
            }
        }
        return out
    }

    fun audit() {
        val sum = snapshot().values.sum()
        check(sum == entered.get()) { "round $roundId leaked: $sum for ${entered.get()}" }
    }

    override fun close() = synchronized(lock) { out.flush(); out.close() }

    private fun write(o: Outcome, host: String, detail: String) {
        val line = "${roundId}\t${seq.incrementAndGet()}\t${o.name}\t$host\t${detail.replace('\n', ' ').take(280)}\n"
        synchronized(lock) {
            out.write(line.toByteArray())
            if (seq.get() % flushEvery == 0) out.flush()
        }
    }

    private fun loadSeen() {
        if (!file.exists()) return
        file.useLines { lines ->
            lines.forEach { line ->
                line.split('\t').getOrNull(3)?.let { host -> seen.putIfAbsent(host, 1) }
            }
        }
    }
}
