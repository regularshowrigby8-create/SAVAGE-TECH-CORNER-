package dev.nova.tv.data.repo

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.Manifest
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.ResourceDef
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.finder.FinderRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
            async { install(url, builtIn) }
        }.awaitAll().filterNotNull()
        _addons.value = loaded.distinctBy { it.baseUrl.lowercase() }
    }

    /**
     * Install one addon, with a retry and a guaranteed fallback for the
     * built-ins.
     *
     * A single failed manifest used to drop the addon outright, and because
     * Cinemeta is the only catalogue source, one slow or refused manifest
     * meant *zero* catalogues: the home screen rendered its shimmer and then
     * nothing at all. A TV box with a cold DNS cache hits that on the first
     * launch. So: two attempts, then the catalogue set the host is known to
     * serve, so the shelves always have somewhere to load from.
     */
    private suspend fun install(url: String, builtIn: Boolean): NovaAddon? {
        repeat(INSTALL_ATTEMPTS) { attempt ->
            val addon = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                runCatching { client.loadAddon(url).copy(isBuiltIn = builtIn) }.getOrNull()
            }
            if (addon != null) return addon
            if (attempt < INSTALL_ATTEMPTS - 1) delay(INSTALL_RETRY_MS)
        }
        return if (builtIn) fallbackAddon(url) else null
    }

    /** The catalogues Cinemeta is known to serve, when its manifest will not load. */
    private fun fallbackAddon(url: String): NovaAddon? {
        val base = url.trim().trimEnd('/')
        val host = runCatching { java.net.URI(base).host }.getOrNull()?.lowercase() ?: return null
        if (host != CINEMETA_HOST) return null
        return NovaAddon(
            baseUrl = base,
            isBuiltIn = true,
            manifest = Manifest(
                id = "com.linvo.cinemeta",
                name = "Cinemeta",
                version = "3",
                resources = listOf(
                    ResourceDef(name = "catalog", types = listOf("movie", "series")),
                    ResourceDef(name = "meta", types = listOf("movie", "series"))
                ),
                types = listOf("movie", "series"),
                idPrefixes = listOf("tt"),
                catalogs = listOf(
                    CatalogDef(type = "movie", id = "top", name = "Popular"),
                    CatalogDef(type = "series", id = "top", name = "Popular"),
                    CatalogDef(type = "movie", id = "imdbRating", name = "Featured"),
                    CatalogDef(type = "series", id = "imdbRating", name = "Featured"),
                    CatalogDef(type = "movie", id = "year", name = "New"),
                    CatalogDef(type = "series", id = "year", name = "New")
                )
            )
        )
    }

    /**
     * Install an addon the viewer typed in.
     *
     * The address is accepted the way people write it ("example.com"), and the
     * manifest gets the same two attempts the built-ins get, so a single slow
     * response is not reported as a broken server.
     */
    suspend fun add(rawUrl: String): Result<NovaAddon> = runCatching {
        val typed = rawUrl.trim()
        val url = if (typed.startsWith("http://", true) || typed.startsWith("https://", true)) {
            typed
        } else {
            "https://$typed"
        }
        var last: Throwable? = null
        repeat(INSTALL_ATTEMPTS) { attempt ->
            val addon = runCatching { client.loadAddon(url) }.getOrElse { error ->
                last = error
                null
            }
            if (addon != null) {
                require(addon.supportsStreams() || addon.catalogDefs.isNotEmpty() || addon.supportsSubtitles()) {
                    "${addon.name} offers no catalogue, streams or subtitles."
                }
                _addons.value = (_addons.value.filterNot { same(it.baseUrl, addon.baseUrl) } + addon)
                persist()
                return@runCatching addon
            }
            if (attempt < INSTALL_ATTEMPTS - 1) delay(INSTALL_RETRY_MS)
        }
        throw last ?: IllegalStateException("Couldn't reach that server")
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

    /**
     * One page of one catalogue, for endless rows: Popular / Featured / New,
     * optionally narrowed to a genre, from the first addon that declares it
     * (Cinemeta for the built-in rows). `skip` is the number already shown.
     */
    suspend fun loadPage(type: String, catalogId: String, genre: String? = null, skip: Int = 0): List<MetaPreview> {
        val pair = _addons.value.filter { it.enabled }.firstNotNullOfOrNull { addon ->
            addon.catalogDefs.firstOrNull { it.type == type && it.id == catalogId }?.let { addon to it }
        } ?: return emptyList()
        val (addon, def) = pair
        if (genre == null && def.extra.any { it.isRequired && it.name == "genre" }) return emptyList()
        val extra = buildMap {
            if (genre != null) put("genre", genre)
            if (skip > 0) put("skip", skip.toString())
        }
        return withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            runCatching { client.fetchCatalog(addon, def, extra) }.getOrDefault(emptyList())
        }.orEmpty().filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    /** Genres the first matching catalogue offers ("Action", "Comedy", ...). */
    fun genres(type: String, catalogId: String = "top"): List<String> =
        _addons.value.firstNotNullOfOrNull { addon ->
            addon.catalogDefs.firstOrNull { it.type == type && it.id == catalogId }
                ?.extra?.firstOrNull { it.name == "genre" }?.options
        }.orEmpty()

    /** Full details with episodes, from the first addon that serves `meta` for this id. */
    suspend fun loadMeta(type: String, id: String): MetaPreview? {
        val addons = _addons.value.filter { it.enabled && it.supportsMeta() && it.acceptsId(id) }
        for (addon in addons) {
            val meta = withTimeoutOrNull(PAGE_TIMEOUT_MS) { client.fetchMeta(addon, type, id) }
            if (meta != null) return meta
        }
        return null
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
        const val PAGE_TIMEOUT_MS = 10_000L
        const val INSTALL_TIMEOUT_MS = 12_000L
        /** Two tries at a manifest before falling back. */
        const val INSTALL_ATTEMPTS = 2
        const val INSTALL_RETRY_MS = 700L
        const val CINEMETA_HOST = "v3-cinemeta.strem.io"
    }
}
