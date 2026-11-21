package dev.nova.tv.data.repo

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonApi
import dev.nova.tv.data.remote.CatalogBatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    private data class Call(val base: String, val catalog: String, val extra: Map<String, String>)
    private class FakeApi : AddonApi {
        val calls = mutableListOf<Call>()
        var handler: suspend (Call) -> CatalogBatch = { throw IllegalStateException("offline") }
        override suspend fun loadAddon(baseUrl: String): NovaAddon = throw IllegalStateException("offline")
        override suspend fun fetchCatalogPage(addon: NovaAddon, catalog: CatalogDef, extra: Map<String, String>): CatalogBatch {
            val call = Call(addon.baseUrl, catalog.id, extra)
            calls += call
            return handler(call)
        }
        override suspend fun fetchMeta(addon: NovaAddon, type: String, id: String): MetaPreview? = null
        override suspend fun fetchStreams(addon: NovaAddon, type: String, id: String): List<Stream> = emptyList()
        override suspend fun fetchSubtitles(addon: NovaAddon, type: String, id: String): List<SubtitleRef> = emptyList()
    }
    private fun title(id: String) = MetaPreview(id = id, name = "Title $id")

    @Test fun `seeds appear without waiting for manifest network`() = runTest {
        val api = FakeApi()
        val repo = CatalogRepository(api, background = backgroundScope)
        repo.installDefaults()
        assertTrue(repo.addons.value.any { it.baseUrl == CatalogDefaults.CINEMETA })
        assertTrue(repo.homeCatalogs().size >= 10)
        assertTrue(repo.homeCatalogs().all { row -> row.catalog.extra.none { it.isRequired } })
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `fallback is raced then pagination remains pinned and advances raw cursor`() = runTest {
        val api = FakeApi()
        api.handler = { call ->
            when {
                call.base == CatalogDefaults.CINEMETA -> { delay(5_000); CatalogBatch(listOf(title("late")), 1) }
                call.base == CatalogDefaults.IMDB_POPULAR && "skip" !in call.extra -> {
                    delay(10); CatalogBatch(listOf(title("tt1"), title("tt2")), 3)
                }
                call.base == CatalogDefaults.IMDB_POPULAR && call.extra["skip"] == "3" -> CatalogBatch(listOf(title("tt4")), 2)
                call.base == CatalogDefaults.IMDB_POPULAR -> CatalogBatch(emptyList(), 0)
                else -> throw IllegalStateException("unavailable")
            }
        }
        val repo = CatalogRepository(api, background = backgroundScope)
        repo.installDefaults()
        val first = repo.loadPage("movie", "top")
        assertEquals(CatalogDefaults.IMDB_POPULAR, first.origin?.addon?.baseUrl)
        assertEquals(3, first.nextSkip)
        assertFalse(first.exhausted)
        val start = api.calls.size
        val second = repo.loadPage("movie", "top", skip = first.nextSkip, origin = first.origin)
        assertEquals(listOf("tt4"), second.items.map { it.id })
        assertEquals(5, second.nextSkip)
        assertTrue(api.calls.drop(start).all { it.base == CatalogDefaults.IMDB_POPULAR && it.extra["skip"] == "3" })
        val end = repo.loadPage("movie", "top", skip = second.nextSkip, origin = second.origin)
        assertTrue(end.exhausted)
        assertNull(end.error)
        assertEquals(5, end.nextSkip)
    }

    @Test fun `a provider without skip is not paged and cannot change origins`() = runTest {
        val api = FakeApi()
        api.handler = { CatalogBatch(listOf(title("tt1")), 1) }
        val repo = CatalogRepository(api, background = backgroundScope)
        repo.installDefaults()
        val origin = repo.homeCatalogs().first { it.addon.baseUrl == CatalogDefaults.FESTIVALS }
        val first = repo.loadPage("movie", origin.catalog.id, origin = origin)
        assertTrue(first.exhausted)
        val calls = api.calls.size
        val invalid = repo.loadPage("movie", origin.catalog.id, skip = 1, origin = origin)
        assertNotNull(invalid.error)
        assertEquals(calls, api.calls.size)
        assertNotNull(repo.loadPage("movie", "top", skip = 1).error)
    }

    @Test fun `all failed providers report a retryable error rather than an empty success`() = runTest {
        val repo = CatalogRepository(FakeApi(), background = backgroundScope)
        repo.installDefaults()
        val page = repo.loadPage("movie", "top")
        assertTrue(page.items.isEmpty())
        assertNotNull(page.error)
        assertTrue(repo.lastErrors.value.isNotEmpty())
    }
}
