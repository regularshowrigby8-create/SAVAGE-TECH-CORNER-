package dev.nova.tv.data.repo

import dev.nova.tv.data.model.CatalogDef
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonApi
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.CatalogBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** A shelf keeps this origin so page two cannot silently switch providers. */
data class CatalogOrigin(
    val addon: NovaAddon,
    val catalog: CatalogDef,
    val extra: Map<String, String> = emptyMap()
) {
    val canPage: Boolean get() = catalog.extra.any { it.name == "skip" }
}

data class CatalogPage(
    val items: List<MetaPreview> = emptyList(),
    val origin: CatalogOrigin? = null,
    val nextSkip: Int = 0,
    val exhausted: Boolean = true,
    val error: String? = null
)

class CatalogRepository(
    private val client: AddonApi,
    private val store: AddonUrlStore? = null,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    interface AddonUrlStore {
        fun savedAddonUrls(): List<String>
        fun saveAddonUrls(urls: List<String>)
    }

    private val _addons = MutableStateFlow<List<NovaAddon>>(emptyList())
    val addons: StateFlow<List<NovaAddon>> = _addons.asStateFlow()
    private val _lastErrors = MutableStateFlow<List<String>>(emptyList())
    val lastErrors: StateFlow<List<String>> = _lastErrors.asStateFlow()
    private val initLock = Any()
    private var initialized = false
    private val catalogGate = Semaphore(CATALOG_CONCURRENCY)
    private val hostGates = ConcurrentHashMap<String, Semaphore>()

    /**
     * Publish last-known definitions immediately; do not wait for a dozen
     * manifests before drawing home. Refresh manifests and saved user addons in
     * the background, with bounded parallel requests. A failed manifest retains
     * the seed; it does not mean that the service or its catalogue is working.
     */
    suspend fun installDefaults() {
        val start = synchronized(initLock) {
            if (initialized) false else {
                initialized = true
                _addons.value = CatalogDefaults.addons
                true
            }
        }
        if (!start) return
        background.launch(Dispatchers.IO) {
            val saved = runCatching { store?.savedAddonUrls().orEmpty() }.getOrDefault(emptyList())
                .filter { url -> CatalogDefaults.addons.none { same(it.baseUrl, url) } }
                .distinctBy { it.trimEnd('/').lowercase() }
            val gate = Semaphore(MANIFEST_CONCURRENCY)
            coroutineScope {
                val defaults = CatalogDefaults.addons.map { seed ->
                    async {
                        gate.withPermit {
                            val live = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                                try { client.loadAddon(seed.baseUrl) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            } ?: return@withPermit
                            // No auto-enabling of stream addons merely because
                            // they are being used as a metadata catalogue.
                            val allowed = seed.manifest.resources.map { it.name.lowercase() }.toSet()
                            val resources = live.manifest.resources.filter { it.name.lowercase() in allowed }
                            val manifest = live.manifest.copy(resources = resources.ifEmpty { seed.manifest.resources })
                            if (seed.catalogDefs.isNotEmpty() && manifest.catalogs.isEmpty()) return@withPermit
                            val fresh = live.copy(isBuiltIn = true, manifest = manifest)
                            _addons.update { current -> current.map { if (same(it.baseUrl, seed.baseUrl)) fresh else it } }
                        }
                    }
                }
                val restored = saved.map { url ->
                    async {
                        gate.withPermit {
                            val live = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
                                try { client.loadAddon(url) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            } ?: return@withPermit
                            _addons.update { current -> if (current.any { same(it.baseUrl, live.baseUrl) }) current else current + live }
                        }
                    }
                }
                (defaults + restored).awaitAll()
            }
        }
    }

    suspend fun add(rawUrl: String): Result<NovaAddon> {
        val typed = rawUrl.trim()
        val url = if (typed.startsWith("http://", true) || typed.startsWith("https://", true)) typed else "https://$typed"
        var last: Throwable? = null
        repeat(INSTALL_ATTEMPTS) { attempt ->
            val addon = try { client.loadAddon(url) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                last = error
                null
            }
            if (addon != null) {
                if (!(addon.supportsStreams() || addon.catalogDefs.isNotEmpty() || addon.supportsSubtitles())) {
                    return Result.failure(IllegalArgumentException("${addon.name} offers no catalogue, streams or subtitles."))
                }
                _addons.update { current ->
                    // A typed copy of a built-in must not make it removable.
                    val builtIn = current.any { same(it.baseUrl, addon.baseUrl) && it.isBuiltIn }
                    current.filterNot { same(it.baseUrl, addon.baseUrl) } + addon.copy(isBuiltIn = builtIn)
                }
                persist()
                return Result.success(addon)
            }
            if (attempt < INSTALL_ATTEMPTS - 1) delay(INSTALL_RETRY_MS)
        }
        return Result.failure(last ?: IllegalStateException("Couldn't reach that server"))
    }

    fun remove(baseUrl: String) {
        _addons.update { current -> current.filterNot { same(it.baseUrl, baseUrl) && !it.isBuiltIn } }
        persist()
    }

    fun isInstalled(baseUrl: String): Boolean = _addons.value.any { same(it.baseUrl, baseUrl) }
    private fun persist() = store?.saveAddonUrls(_addons.value.filterNot { it.isBuiltIn }.map { it.baseUrl })

    /** One visible, directly-addressed row per enabled catalogue service. */
    fun homeCatalogs(): List<CatalogOrigin> = _addons.value.filter { it.enabled }.mapNotNull { addon ->
        if (same(addon.baseUrl, CatalogDefaults.CINEMETA)) return@mapNotNull null // already has the main rows
        addon.catalogDefs.firstOrNull { def ->
            def.type in setOf("movie", "series", "anime") && def.id.isNotBlank() && def.extra.none { it.isRequired }
        }?.let { CatalogOrigin(addon, it) }
    }

    /**
     * General rows race a small set of equivalent providers, with a short
     * stagger favoring the primary. Individual provider rows and all later
     * pages stay pinned to their original provider. Required extras and `skip`
     * are sent only when advertised; unknown catalogue IDs are never invented.
     */
    suspend fun loadPage(
        type: String,
        catalogId: String,
        genre: String? = null,
        skip: Int = 0,
        origin: CatalogOrigin? = null
    ): CatalogPage {
        require(skip >= 0) { "Invalid catalogue offset" }
        if (skip > 0 && origin == null) return CatalogPage(error = "The catalogue source changed. Refresh to start again.")
        val choices = if (origin != null) {
            if (_addons.value.none { it.enabled && same(it.baseUrl, origin.addon.baseUrl) } || (skip > 0 && !origin.canPage)) emptyList()
            else listOf(origin)
        } else {
            choices(type, catalogId, genre).take(MAX_FALLBACKS)
        }
        if (choices.isEmpty()) return CatalogPage(error = "No compatible catalogue is available. Check Sources and try again.")
        return racePages(choices, skip)
    }

    private fun choices(type: String, role: String, genre: String?): List<CatalogOrigin> {
        val enabled = _addons.value.filter { it.enabled }
        val found = mutableListOf<CatalogOrigin>()
        fun add(addon: NovaAddon, def: CatalogDef, isAlias: Boolean) {
            if (def.type != type) return
            val effectiveGenre = if (isAlias && role == "year" && addon.baseUrl == CatalogDefaults.WATCH_ATLAS) null else genre
            val extra = mutableMapOf<String, String>()
            if (effectiveGenre != null) {
                val supported = def.extra.firstOrNull { it.name == "genre" } ?: return
                if (supported.options.isNotEmpty() && supported.options.none { it.equals(effectiveGenre, ignoreCase = true) }) return
                extra["genre"] = effectiveGenre
            }
            if (def.extra.any { it.isRequired && it.name !in extra }) return
            found += CatalogOrigin(addon, def, extra)
        }
        // Exact catalogue IDs first, then documented aliases. Do not use a
        // generic unrelated row as a "top rated" or filtered-genre fallback.
        enabled.forEach { addon -> addon.catalogDefs.filter { it.type == type && it.id == role }.forEach { add(addon, it, false) } }
        enabled.forEach { addon ->
            val alias = CatalogDefaults.alias(addon.baseUrl, type, role) ?: return@forEach
            addon.catalogDefs.filter { it.type == type && it.id == alias }.forEach { add(addon, it, true) }
        }
        return found.distinctBy { Triple(it.addon.baseUrl.lowercase(), it.catalog.type, it.catalog.id) }
    }

    private data class Attempt(val origin: CatalogOrigin, val batch: CatalogBatch? = null, val failure: String? = null)

    private suspend fun racePages(choices: List<CatalogOrigin>, skip: Int): CatalogPage = coroutineScope {
        val answers = Channel<Attempt>(choices.size)
        val jobs = choices.mapIndexed { index, choice ->
            launch {
                if (index > 0) delay(index * FALLBACK_STAGGER_MS)
                val result = try {
                    val batch = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                        val host = runCatching { URI(choice.addon.baseUrl).host?.lowercase() }.getOrNull().orEmpty()
                        val perHost = hostGates.computeIfAbsent(host) { Semaphore(PER_HOST_CONCURRENCY) }
                        perHost.withPermit {
                            catalogGate.withPermit {
                                val extra = if (skip > 0) choice.extra + ("skip" to skip.toString()) else choice.extra
                                client.fetchCatalogPage(choice.addon, choice.catalog, extra)
                            }
                        }
                    }
                    if (batch == null) Attempt(choice, failure = "timed out")
                    else if (batch.items.isEmpty()) Attempt(choice, batch, if (batch.consumed > 0) "invalid titles" else "no titles")
                    else Attempt(choice, batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Attempt(choice, failure = "unavailable")
                }
                answers.send(result)
            }
        }
        val failed = mutableListOf<String>()
        try {
            repeat(choices.size) {
                val result = answers.receive()
                val batch = result.batch
                if (batch != null && batch.items.isNotEmpty()) {
                    _lastErrors.value = emptyList()
                    return@coroutineScope CatalogPage(
                        items = batch.items, origin = result.origin,
                        nextSkip = skip + batch.consumed,
                        exhausted = !result.origin.canPage || batch.consumed == 0
                    )
                }
                // An empty later page is normal exhaustion, not a network
                // failure. Invalid rows, timeouts and HTTP errors are not.
                if (skip > 0 && batch != null && batch.consumed == 0) {
                    _lastErrors.value = emptyList()
                    return@coroutineScope CatalogPage(origin = result.origin, nextSkip = skip, exhausted = true)
                }
                failed += "${result.origin.addon.name}: ${result.failure ?: "no titles"}"
            }
            _lastErrors.value = failed
            val examples = failed.take(3).joinToString("; ")
            CatalogPage(error = "Catalogues did not return titles ($examples). Check your connection or try again.")
        } finally {
            // Children can finish a send concurrently with cancellation; closing
            // here would turn an otherwise successful race into an exception.
            jobs.forEach { it.cancel() }
        }
    }

    fun genres(type: String, catalogId: String = "top"): List<String> =
        _addons.value.filter { it.enabled }.firstNotNullOfOrNull { addon ->
            addon.catalogDefs.firstOrNull { it.type == type && it.id == catalogId }
                ?.extra?.firstOrNull { it.name == "genre" }?.options
        }.orEmpty()

    suspend fun loadRow(type: String): List<MetaPreview> = loadPage(type, "top").items

    suspend fun loadMeta(type: String, id: String): MetaPreview? {
        val enabled = _addons.value.filter { it.enabled && it.supportsMeta() && it.acceptsId(id) }
        for (addon in enabled) {
            val meta = withTimeoutOrNull(PAGE_TIMEOUT_MS) { client.fetchMeta(addon, type, id) }
            if (meta != null) return meta
        }
        return null
    }

    suspend fun search(query: String): List<MetaPreview> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val calls = _addons.value.filter { it.enabled }.flatMap { addon ->
            addon.catalogDefs.filter { def ->
                def.extra.any { it.name == "search" } && def.extra.none { it.isRequired && it.name != "search" }
            }.distinctBy { it.type }.map { addon to it }
        }
        calls.map { (addon, catalog) ->
            async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    try {
                        catalogGate.withPermit { client.fetchCatalogPage(addon, catalog, mapOf("search" to query.trim())).items }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    suspend fun loadStreams(type: String, id: String): List<Stream> = coroutineScope {
        val enabled = _addons.value.filter { it.enabled && it.supportsStreams() && it.acceptsId(id) }
        AddonClient.rank(enabled.map { addon -> async { client.fetchStreams(addon, type, id) } }.awaitAll().flatten())
    }

    suspend fun loadSubtitles(type: String, id: String): List<SubtitleRef> = coroutineScope {
        _addons.value.filter { it.enabled && it.supportsSubtitles() && it.acceptsId(id) }.map { addon ->
            async {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    try { client.fetchSubtitles(addon, type, id) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    internal fun merge(items: List<MetaPreview>): List<MetaPreview> =
        items.groupBy { it.id }.mapNotNull { (_, copies) -> copies.maxByOrNull { it.rating } }.sortedByDescending { it.rating }

    private fun same(a: String, b: String): Boolean =
        a.trim().trimEnd('/').removeSuffix("/manifest.json").equals(b.trim().trimEnd('/').removeSuffix("/manifest.json"), true)

    private companion object {
        const val SEARCH_TIMEOUT_MS = 8_000L
        const val PAGE_TIMEOUT_MS = 7_000L
        const val INSTALL_TIMEOUT_MS = 7_000L
        const val INSTALL_ATTEMPTS = 2
        const val INSTALL_RETRY_MS = 700L
        const val CATALOG_CONCURRENCY = 8
        const val PER_HOST_CONCURRENCY = 2
        const val MANIFEST_CONCURRENCY = 4
        const val MAX_FALLBACKS = 6
        const val FALLBACK_STAGGER_MS = 150L
    }
}
