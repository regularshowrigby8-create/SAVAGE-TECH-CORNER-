// Fixture for `semgrep --test` (Kotlin false-green rules). Not compiled.
class Fixture {
    // ruleid: test-without-assertion
    @Test
    fun ranksSources() {
        val ranked = StreamRanker.rank(listOf(a, b))
        println(ranked)
    }

    // ok: test-without-assertion
    @Test
    fun ranksSourcesChecked() {
        assertEquals(listOf(b, a), StreamRanker.rank(listOf(a, b)))
    }

    // ok: test-without-assertion
    @Test(expected = IllegalStateException::class)
    fun throwsOnEmpty() {
        StreamRanker.rank(emptyList())
    }

    // ruleid: test-is-ignored
    @Ignore("flaky")
    fun later() {
        assertTrue(false)
    }
}

class NestedFixture {
    // ok: test-without-assertion
    @Test
    fun everyRowChecked() {
        for (row in rows) {
            assertTrue(row.ok)
        }
    }
}
