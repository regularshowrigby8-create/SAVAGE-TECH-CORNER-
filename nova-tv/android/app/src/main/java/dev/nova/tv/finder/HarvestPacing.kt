package dev.nova.tv.finder

/**
 * What one pool-fill iteration should do next, and how long to wait.
 *
 * This exists because the pacing decision used to be written inline in
 * `fillWebsitePool` as two `if`s:
 *
 * ```kotlin
 * if (!harvest.more && sites.size == before) return
 * if (sites.size > before) delay(POOL_FILL_PAUSE_MS)
 * ```
 *
 * A *skip* -- another drainer held the finder, so nothing was crawled and the
 * result was just the current pool -- reported `more = true` (the pool is under
 * cap) and added nothing, so it matched neither branch. The loop therefore ran
 * with no delay at all, millions of times per second, each pass incrementing
 * the round counter. On device that is the `harvest#3945996 SKIP (already
 * running)` flood, where every line carried the same timestamp because the
 * whole flood happened inside one second.
 *
 * Keeping the decision pure makes the invariant testable: **every step that
 * does not finish the loop must pause.** `HarvestPacingTest` asserts exactly
 * that for all five cases.
 */
enum class HarvestStep {
    /** Another drainer holds the finder; nothing was crawled. */
    SKIP,

    /** The harvest threw or timed out; retry with exponential backoff. */
    RETRY,

    /** Nothing more to do: the pool did not grow and the finder has no more. */
    DONE,

    /** The pool grew; a short pause then continue. */
    PROGRESS,

    /** A real round that added nothing; back off so a dead corpus is not hammered. */
    BARREN
}

object HarvestPacing {

    fun step(busy: Boolean, harvestNull: Boolean, more: Boolean, before: Int, after: Int): HarvestStep = when {
        busy -> HarvestStep.SKIP
        harvestNull -> HarvestStep.RETRY
        !more && after == before -> HarvestStep.DONE
        after > before -> HarvestStep.PROGRESS
        else -> HarvestStep.BARREN
    }

    /**
     * Delay before the next iteration. [HarvestStep.DONE] returns 0 because the
     * loop exits; every other step must return a positive pause, which is the
     * invariant that stops the spin.
     */
    fun pauseMs(step: HarvestStep, failures: Int = 0): Long = when (step) {
        HarvestStep.DONE -> 0L
        HarvestStep.PROGRESS -> 400L
        HarvestStep.BARREN -> 3_000L
        HarvestStep.SKIP -> 3_000L
        HarvestStep.RETRY -> 3_000L shl (failures - 1).coerceIn(0, 4)
    }
}
