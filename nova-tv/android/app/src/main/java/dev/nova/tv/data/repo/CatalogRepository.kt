package dev.nova.tv.data.repo

import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.remote.AddonClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the addon list and merges catalogues across all of them.
 *
 * Two deliberate choices:
 *  - one addon failing must not empty the shelf, so errors are collected per
 *    addon rather than thrown;
 *  - duplicates across addons are merged by id, keeping the better rating.
 */
class CatalogRepository(private val client: AddonClient) {

    private val _addons = MutableStateFlow<List<NovaAddon>>(emptyList())
    val addons: StateFlow<List<NovaAddon>> = _addons.asStateFlow()

    private val _lastErrors = MutableStateFlow<List<String>>(emptyList())
    val lastErrors: StateFlow<List<String>> = _lastErrors.asStateFlow()

    suspend fun installDefaults() {
        if (_addons.value.isNotEmpty()) return
        val defaults = listOf(NovaAddon.CINEMETA_URL)
        val installed = defaults.mapNotNull { url ->
            runCatching { client.loadAddon(url).copy(isBuiltIn = true) }.getOrNull()
        }
        _addons.value = installed
    }

    suspend fun add(baseUrl: String): Result<NovaAddon> = runCatching {
        val addon = client.loadAddon(baseUrl)
        _addons.value = (_addons.value + addon).distinctBy { it.baseUrl }
        addon
    }

    fun remove(baseUrl: String) {
        _addons.value = _addons.value.filterNot { it.baseUrl == baseUrl && !it.isBuiltIn }
    }

    suspend fun loadRow(type: String): List<MetaPreview> = coroutineScope {
        val enabled = _addons.value.filter { it.enabled && it.supportsCatalog(type) }
        val errors = mutableListOf<String>()
        val results = enabled.map { addon ->
            async {
                val catalog = addon.catalogDefs.firstOrNull { it.type == type } ?: return@async emptyList<MetaPreview>()
                runCatching { client.fetchCatalog(addon, catalog) }
                    .onFailure { errors += "${addon.name}: ${it.message}" }
                    .getOrElse { it }
            }
        }.map { it.await() }
        _lastErrors.value = errors
        merge(results.flatten())
    }

    suspend fun loadStreams(type: String, id: String): List<Stream> = coroutineScope {
        val enabled = _addons.value.filter { it.enabled && it.supportsStreams() }
        val all = enabled.map { addon ->
            async { client.fetchStreams(addon, type, id) }
        }.flatMap { it.await() }
        AddonClient.rank(all)
    }

    /** De-duplicate by id, preferring the higher-rated copy. */
    internal fun merge(items: List<MetaPreview>): List<MetaPreview> =
        items.groupBy { it.id }
            .mapNotNull { (_, copies) -> copies.maxByOrNull { it.rating } }
            .sortedByDescending { it.rating }
}
