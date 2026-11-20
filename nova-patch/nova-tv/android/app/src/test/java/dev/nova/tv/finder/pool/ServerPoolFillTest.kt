package dev.nova.tv.finder.pool

import dev.nova.tv.finder.FinderCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The behaviour the fill loop depends on. These are JVM tests: no Android, no
 * network, no clock. The harvester is driven through an injected fake source, so
 * what is verified is the loop's logic -- stop at capacity, stop when exhausted,
 * count what it added -- not whether the internet has servers today.
 */
class ServerPoolFillTest {

    private class MemoryStore(var text: String? = null) : PoolStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private fun candidate(host: String, path: String = "") = FinderCandidate(
        manifestUrl = "https://$host$path/manifest.json",
        baseUrl = "https://$host$path",
        name = host,
        description = "",
        version = "1",
        types = listOf("movie"),
        idPrefixes = listOf("tt"),
        searchable = false,
        stars = 0,
        from = "test"
    )

    private fun many(count: Int, prefix: String = "s") =
        (0 until count).map { candidate("$prefix$it.example") }

    /** A source that hands out [batches] then declares itself exhausted. */
    private class FakeSource(
        override val id: String,
        override val label: String = "fake",
        private val batches: List<List<FinderCandidate>>
    ) : ServerHarvester.Source {
        private var index = 0
        override suspend fun sweep(ctx: ServerHarvester.SweepContext): List<FinderCandidate> {
            if (index >= batches.size) return emptyList()
            return batches[index++]
        }
        override fun isDone(): Boolean = index >= batches.size
        override fun backoffUntil(): Long = 0L
        override fun coolingDown(now: Long): Boolean = false
    }

    // -- the pool -----------------------------------------------------------

    @Test
    fun `addAndCount reports the size after merging, not just the delta`() {
        val pool = ServerPool(MemoryStore())
        val (added, size) = pool.addAndCount(many(5))
        assertEquals(5, added)
        assertEquals(5, size)
        val (again, sameSize) = pool.addAndCount(many(5))
        assertEquals(0, again)
        assertEquals(5, sameSize)
    }

    @Test
    fun `free slots shrink as the pool fills and reach zero at capacity`() {
        val pool = ServerPool(MemoryStore(), Random(1))
        assertEquals(ServerPool.CAPACITY, pool.free)
        pool.addAndCount(many(ServerPool.CAPACITY - 1))
        assertEquals(1, pool.free)
        assertEquals(false, pool.state.value.full)
        pool.addAndCount(listOf(candidate("last.example")))
        assertEquals(0, pool.free)
        assertTrue(pool.state.value.full)
        assertFalse(pool.state.value.filling)
    }

    @Test
    fun `a full pool ignores further candidates instead of overfilling`() {
        val pool = ServerPool(MemoryStore())
        pool.addAndCount((0 until ServerPool.CAPACITY + 50).map { candidate("h$it.example") })
        assertEquals(ServerPool.CAPACITY, pool.state.value.size)
        assertEquals(0, pool.addAndCount(listOf(candidate("overflow.example"))).first)
    }

    @Test
    fun `state is published, so a collector sees the pool grow`() = runTest {
        val pool = ServerPool(MemoryStore())
        pool.addAndCount(many(3))
        val state = withTimeout(1_000) { pool.state.first { it.size == 3 } }
        assertEquals(3, state.size)
        assertEquals(ServerPool.CAPACITY - 3, state.free)
        assertTrue(state.filling)
    }

    // -- the harvester loop -------------------------------------------------

    @Test
    fun `continuous fill stops as soon as the pool reports full`() = runTest {
        val pool = ServerPool(MemoryStore())
        pool.addAndCount(many(ServerPool.CAPACITY - 2))
        val source = FakeSource("fake", batches = listOf(many(10, prefix = "new")))
        val harvester = ServerHarvester(fakeHttp(), listOf(source))

        harvester.continuousFill(
            poolSize = { pool.state.value.size },
            target = ServerPool.CAPACITY,
            onBatch = { pool.addAndCount(it) }
        )

        assertEquals(ServerPool.CAPACITY, pool.state.value.size)
        assertEquals("pool full", harvester.state.value.stopped)
    }

    @Test
    fun `continuous fill stops when every source is exhausted, and says so`() = runTest {
        val pool = ServerPool(MemoryStore())
        val source = FakeSource("fake", batches = listOf(many(4, prefix = "a"), many(3, prefix = "b")))
        val harvester = ServerHarvester(fakeHttp(), listOf(source))

        harvester.continuousFill(
            poolSize = { pool.state.value.size },
            target = ServerPool.CAPACITY,
            onBatch = { pool.addAndCount(it) }
        )

        assertEquals(7, pool.state.value.size)
        assertTrue(harvester.state.value.exhausted)
        assertEquals("every source exhausted", harvester.state.value.stopped)
        assertFalse(harvester.state.value.running)
    }

    @Test
    fun `a pool that is already full does not start a crawl`() = runTest {
        val pool = ServerPool(MemoryStore())
        pool.addAndCount((0 until ServerPool.CAPACITY).map { candidate("full$it.example") })
        var sweeps = 0
        val source = FakeSource("fake", batches = listOf(many(1, prefix = "x")))
        val harvester = ServerHarvester(fakeHttp(), listOf(source))

        harvester.continuousFill(
            poolSize = { pool.state.value.size },
            target = ServerPool.CAPACITY,
            onBatch = { sweeps++; pool.addAndCount(it) }
        )

        assertEquals(0, sweeps)
        assertEquals("pool full", harvester.state.value.stopped)
    }

    @Test
    fun `the same server found twice is added once`() = runTest {
        val pool = ServerPool(MemoryStore())
        // Two sources, one overlapping host: the dedupe set is shared.
        val overlap = many(3, prefix = "shared")
        val harvester = ServerHarvester(
            fakeHttp(),
            listOf(
                FakeSource("one", batches = listOf(overlap)),
                FakeSource("two", batches = listOf(overlap))
            )
        )
        harvester.continuousFill(
            poolSize = { pool.state.value.size },
            target = ServerPool.CAPACITY,
            onBatch = { pool.addAndCount(it) }
        )
        assertEquals(3, pool.state.value.size)
    }

    @Test
    fun `harvest state counts requests and rounds rather than asserting success`() = runTest {
        val pool = ServerPool(MemoryStore())
        val harvester = ServerHarvester(
            fakeHttp(),
            listOf(FakeSource("one", batches = listOf(many(2, prefix = "q"))))
        )
        harvester.continuousFill(
            poolSize = { pool.state.value.size },
            target = ServerPool.CAPACITY,
            onBatch = { pool.addAndCount(it) }
        )
        val state = harvester.state.value
        assertEquals(1, state.sourceCount)
        assertEquals(1, state.sourcesDone)
        assertEquals(2, state.found)
        assertTrue(state.rounds >= 1)
        assertEquals(2, state.poolSize)
    }

    /**
     * The harvester's real default sources are replaced by fakes in every test
     * here, so the HttpClient is only a constructor argument for the real
     * network path. OkHttp builds a client lazily and makes no request until one
     * is asked for, which is why constructing it in a unit test is safe.
     */
    private fun fakeHttp(): dev.nova.tv.data.remote.HttpClient =
        dev.nova.tv.data.remote.HttpClient()
}
