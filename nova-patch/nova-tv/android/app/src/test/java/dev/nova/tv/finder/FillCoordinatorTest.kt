package dev.nova.tv.finder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FillCoordinatorTest {
    @Test
    fun `replacement waits for a cancelled fill to unwind`() = runTest {
        val coordinator = FillCoordinator(backgroundScope)
        val releaseCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coordinator.start {
            events += "old start"
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    events += "old cleanup"
                    releaseCleanup.await()
                    events += "old end"
                }
            }
        }
        runCurrent()
        coordinator.start { events += "new start"; awaitCancellation() }
        runCurrent()
        assertEquals(listOf("old start", "old cleanup"), events)
        releaseCleanup.complete(Unit)
        runCurrent()
        assertEquals(listOf("old start", "old cleanup", "old end", "new start"), events)
        coordinator.stop()
    }

    @Test
    fun `stop during a one shot prevents the old fill from restarting`() = runTest {
        val coordinator = FillCoordinator(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var starts = 0
        var oneShotStarted = false
        coordinator.start { starts += 1; awaitCancellation() }
        runCurrent()
        backgroundScope.launch {
            coordinator.pauseAndRun { oneShotStarted = true; release.await() }
        }
        runCurrent()
        assertTrue(oneShotStarted)
        assertTrue(coordinator.filling)
        coordinator.stop()
        release.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
        assertFalse(coordinator.filling)
    }

    @Test
    fun `overlapping one shots finish before the fill restarts`() = runTest {
        val coordinator = FillCoordinator(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coordinator.start { events += "fill"; awaitCancellation() }
        runCurrent()
        backgroundScope.launch { coordinator.pauseAndRun { events += "first"; release.await() } }
        backgroundScope.launch { coordinator.pauseAndRun { events += "second" } }
        runCurrent()
        assertEquals(listOf("fill", "first"), events)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("fill", "first", "second", "fill"), events)
        coordinator.stop()
    }

    @Test
    fun `a newer start while paused wins over the old fill`() = runTest {
        val coordinator = FillCoordinator(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var oldStarts = 0
        var newStarts = 0
        coordinator.start { oldStarts += 1; awaitCancellation() }
        runCurrent()
        backgroundScope.launch { coordinator.pauseAndRun { release.await() } }
        runCurrent()
        coordinator.start { newStarts += 1; awaitCancellation() }
        release.complete(Unit)
        runCurrent()
        assertEquals(1, oldStarts)
        assertEquals(1, newStarts)
        coordinator.stop()
    }

    @Test
    fun `a naturally completed fill is not restarted by a one shot`() = runTest {
        val coordinator = FillCoordinator(backgroundScope)
        var starts = 0
        coordinator.start { starts += 1 }
        runCurrent()
        assertFalse(coordinator.filling)
        coordinator.pauseAndRun { }
        runCurrent()
        assertEquals(1, starts)
    }
}
