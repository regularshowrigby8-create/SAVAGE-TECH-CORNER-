package dev.nova.tv.finder.diag

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * A flight recorder for the finders.
 *
 * Three attempts at "Website finder 0 sites" were made from code reading
 * alone, because the app threw away everything that would have explained the
 * result: which seed was fetched, what came back, whether the browser rendered,
 * what the probe saw, and why each candidate was dropped. The reject tallies
 * added last round helped but still had to be inferred.
 *
 * This records the whole sequence, on the device, and lets it be read (and
 * copied) from the Sources screen. It is deliberately:
 *
 *  - **in memory**, capped by [MAX], so a long crawl cannot grow without bound;
 *  - **append-only and cheap**, because it is called from hot loops;
 *  - **thread-safe**, because harvests run on many coroutines at once;
 *  - **format-free**, one line per event, so it can be pasted into a message
 *    or a screenshot and still be useful.
 *
 * Nothing here decides anything. It only writes down what happened.
 */
object FinderTrace {

    /** Newest last. Enough to cover several harvest rounds without bloat. */
    private const val MAX = 400

    private val lines = Collections.synchronizedList(ArrayList<String>())

    /** Per-run counters so a summary line is available without scanning. */
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var enabled: Boolean = true

    fun enabled(on: Boolean) {
        enabled = on
        if (!on) clear()
    }

    /** Record one event. [scope] groups it (websites, addons, player). */
    fun log(scope: String, message: String) {
        if (!enabled) return
        val line = "${stamp.format(Date())} [$scope] $message"
        synchronized(lines) {
            lines += line
            while (lines.size > MAX) lines.removeAt(0)
        }
    }

    /** Count something worth summarising (`seed.ok`, `probe.page`, ...). */
    fun count(key: String, n: Int = 1) {
        if (!enabled) return
        counts.merge(key, n, Int::plus)
    }

    fun tally(): String = counts.entries.sortedByDescending { it.value }
        .joinToString(" ") { "${it.key}=${it.value}" }

    fun clear() {
        synchronized(lines) { lines.clear() }
        counts.clear()
    }

    /** Everything recorded, oldest first. Safe to copy out and paste. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** The whole trace as text, for the Copy button. */
    fun dump(): String = buildString {
        appendLine("Nova finder trace")
        appendLine("totals: ${tally().ifEmpty { "(none)" }}")
        appendLine("---")
        snapshot().forEach { appendLine(it) }
    }
}
