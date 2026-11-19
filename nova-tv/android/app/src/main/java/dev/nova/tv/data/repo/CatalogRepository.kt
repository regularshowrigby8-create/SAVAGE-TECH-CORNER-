package dev.nova.tv.data.repo

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the addon list and merges catalogues across all of them.
 *
 * Three deliberate choices:
 *  - one addon failing must not empty the shelf, so errors are collected per
 *    addon rather than thrown;
 *  - duplicates across addons are merged by id, keeping the better rating;
 *  - the user's addons are persisted. Previously [SettingsStore.saveAddons]
 *    was never called and nothing read `addonUrls`, so every addon the user
 *    added was gone on the next launch.
 */
class CatalogRepository(
    private val client: AddonClient,
    private val store: AddonUrlStore? = null
) {
    /** The persistence half of [SettingsStore], so tests can pass a fake. */
    interface AddonUrlStore {
        fun savedAddonUrls(): List<String>
        fun saveAddonUrls(urls: List<String>)
    }

    private val _addons = MutableStateFlow<List<NovaAddon>>(emptyList())
    val addons: StateFlow<List<NovaAddon>> = _addons.asStateFlow()

    private val _lastErrors = MutableStateFlow<List<String>>(emptyList())
    val lastErrors: StateFlow<List<String>> = _lastErrors.asStateFlow()

    suspend fun installDefaults() = coroutineScope {
        if (_addons.value.isNotEmpty()) return@coroutineScope
        val builtIns = FinderRules.DEFAULT_SOURCES
        val saved = store?.savedAddonUrls().orEmpty().filter { url -> builtIns.none { same(it, url) } }
        val loaded = (builtIns.map { it to true } + saved.map { it to false }).map { (url, builtIn) ->
            async {
                runCatching { client.loadAddon(url).copy(isBuiltIn = builtIn) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        _addons.value = loaded.distinctBy { it.baseUrl.lowercase() }
    }

    suspend fun add(baseUrl: String): Result<NovaAddon> = runCatching {
        val addon = client.loadAddon(baseUrl)
        require(addon.supportsStreams() || addon.catalogDefs.isNotEmpty() || addon.supportsSubtitles()) {
            "${addon.name} offers no catalogue, streams or subtitles."
        }
        _addons.value = (_addons.value.filterNot { same(it.baseUrl, addon.baseUrl) } + addon)
        persist()
        addon
    }

    fun remove(baseUrl: String) {
        _addons.value = _addons.value.filterNot { same(it.baseUrl, baseUrl) && !it.isBuiltIn }
        persist()
    }

    fun isInstalled(baseUrl: String): Boolean = _addons.value.any { same(it.baseUrl, baseUrl) }

    private fun persist() {
        store?.saveAddonUrls(_addons.value.filterNot { it.isBuiltIn }.map { it.baseUrl })
    }

    suspend fun loadRow(type: String): List<MetaPreview> = coroutineScope {
        val enabled = _addons.value.filter { it.enabled && it.supportsCatalog(type) }
        val errors = mutableListOf<String>()
        val results = enabled.map { addon ->
            async {
                val catalog = addon.catalogDefs.firstOrNull { it.type == type && it.extra.none { e -> e.isRequired } }
                    ?: return@async emptyList<MetaPreview>()
                runCatching<List<MetaPreview>> { client.fetchCatalog(addon, catalog) }
                    .onFailure { synchronized(errors) { errors += "${addon.name}: ${it.message}" } }
                    .getOrElse { emptyList() }
            }
        }.awaitAll()
        _lastErrors.value = errors
        merge(results.flatten())
    }

    /** Ask every addon that declares a search catalogue. */
    suspend fun search(query: String): List<MetaPreview> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val calls = _addons.value.filter { it.enabled }.flatMap { addon ->
            addon.catalogDefs.filter { def -> def.extra.any { it.name == "search" } }
                .distinctBy { it.type }
                .map { addon to it }
        }
        calls.map { (addon, catalog) ->
            async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    runCatching { client.searchCatalog(addon, catalog, query) }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    suspend fun loadStreams(type: String, id: String): List<Stream> = coroutineScope {
        val enabled = _addons.value.filter { it.enabled && it.supportsStreams() && it.acceptsId(id) }
        val all = enabled.map { addon ->
            async { client.fetchStreams(addon, type, id) }
        }.awaitAll().flatten()
        AddonClient.rank(all)
    }

    /** External subtitles from addons that declare a `subtitles` resource (OpenSubtitles). */
    suspend fun loadSubtitles(type: String, id: String): List<SubtitleRef> = coroutineScope {
        _addons.value.filter { it.enabled && it.supportsSubtitles() }.map { addon ->
            async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    runCatching { client.fetchSubtitles(addon, type, id) }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    /** De-duplicate by id, preferring the higher-rated copy. */
    internal fun merge(items: List<MetaPreview>): List<MetaPreview> =
        items.groupBy { it.id }
            .mapNotNull { (_, copies) -> copies.maxByOrNull { it.rating } }
            .sortedByDescending { it.rating }

    private fun same(a: String, b: String): Boolean =
        a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    private companion object {
        const val SEARCH_TIMEOUT_MS = 8_000L
    }
}
