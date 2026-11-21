package dev.nova.tv.finder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes the continuous harvester and one-shot operations over its mutable
 * source cursors. Cancellation is not necessarily instantaneous: a blocking
 * network request may still be unwinding when a replacement is requested.
 *
 * [pauseAndRun] waits for that unwind and restarts filling only if it is still
 * wanted. A Stop or a newer Start while paused wins over the old request.
 */
internal class FillCoordinator(private val scope: CoroutineScope) {
    private val control = Any()
    private val serial = Mutex()
    private var desired: (suspend () -> Unit)? = null
    private var job: Job? = null
    private var pauses = 0

    val filling: Boolean get() = synchronized(control) { desired != null }

    fun start(work: suspend () -> Unit) = synchronized(control) {
        desired = work
        cancelCurrentLocked()
        if (pauses == 0) launchLocked()
    }

    fun stop() = synchronized(control) {
        desired = null
        cancelCurrentLocked()
    }

    suspend fun <T> pauseAndRun(work: suspend () -> T): T {
        synchronized(control) {
            // Completion callbacks can be waiting for this lock; do not revive
            // a fill that has already finished while entering the pause.
            if (pauses == 0 && job?.isCompleted == true) desired = null
            pauses += 1
            if (pauses == 1) cancelCurrentLocked()
        }
        try {
            return serial.withLock { work() }
        } finally {
            synchronized(control) {
                pauses -= 1
                if (pauses == 0 && desired != null) launchLocked()
            }
        }
    }

    private fun cancelCurrentLocked() {
        val old = job
        job = null // A completing old job must not clear a newer request.
        old?.cancel()
    }

    private fun launchLocked() {
        val work = desired ?: return
        val launched = scope.launch { serial.withLock { work() } }
        job = launched
        launched.invokeOnCompletion {
            synchronized(control) {
                if (job === launched) {
                    job = null
                    desired = null
                }
            }
        }
    }
}
