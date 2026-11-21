package dev.nova.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.data.remote.NovaDns
import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.finder.website.SitePool
import dev.nova.tv.finder.website.SitePoolStore
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.store.AtomicFileStore
import dev.nova.tv.download.DownloadManager
import dev.nova.tv.data.repo.LibraryStore
import dev.nova.tv.data.repo.SettingsStore
import dev.nova.tv.finder.SourceEngine
import dev.nova.tv.finder.SourceFinder
import dev.nova.tv.finder.StremioFinder
import dev.nova.tv.finder.website.WebsiteFinder
import dev.nova.tv.player.StreamProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency graph.
 *
 * Deliberately not Hilt: the graph is small enough that a single object is easier
 * to read, and an Android TV app has enough startup cost without codegen.
 */
class NovaApplication : Application(), ImageLoaderFactory {

    /** Survives every screen; cancelled only when the process dies. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: HttpClient by lazy { HttpClient() }
    val addonClient: AddonClient by lazy { AddonClient(http) }

    val settings: SettingsStore by lazy { SettingsStore(getSharedPreferences("nova_settings", MODE_PRIVATE)) }
    val library: LibraryStore by lazy { LibraryStore(getSharedPreferences("nova_library", MODE_PRIVATE)) }
    val repository: CatalogRepository by lazy { CatalogRepository(addonClient, settings, appScope) }
    val zeroPlayFinder: SourceFinder by lazy { SourceFinder(http, addonClient) }
    val stremioFinder: StremioFinder by lazy { StremioFinder(http, zeroPlayFinder) }
    val websiteFinder: WebsiteFinder by lazy { WebsiteFinder(http) }
    val probe: StreamProbe by lazy { StreamProbe(http.okHttp) }

    /** Up to 3000 server options, saved between launches. */
    val serverPool: ServerPool by lazy {
        val file = AtomicFileStore(java.io.File(filesDir, "server_pool.json")) { reason ->
            android.util.Log.w("NovaServerPool", reason)
        }
        ServerPool(object : PoolStore {
            override fun read(): String? = file.read()
            override fun write(text: String) = file.write(text)
        })
    }

    /**
     * Offline downloads. Files live in app-specific external storage, which
     * needs no runtime permission and is removed with the app.
     */
    val downloads: DownloadManager by lazy {
        DownloadManager(
            dir = File(getExternalFilesDir(null) ?: filesDir, "Downloads"),
            client = http.okHttp,
            scope = appScope
        )
    }

    /**
     * Streaming sites discovered by the crawler, kept separate from [serverPool].
     *
     * A manifest and a site are different things: the app asks a manifest for
     * streams, while a site is resolved by [WebsiteFinder]. Two pools, two
     * 3000-entry ceilings, so neither is counted against the other.
     */
    val sitePool: SitePool by lazy {
        val file = AtomicFileStore(java.io.File(filesDir, "site_pool.json")) { reason ->
            android.util.Log.w("NovaSitePool", reason)
        }
        SitePool(
            store = object : SitePoolStore {
                override fun read(): String? = file.read()
                override fun write(text: String) = file.write(text)
            },
            sites = dev.nova.tv.finder.website.SiteSink { sites -> library.addWebsitesIfAbsent(sites) }
        )
    }

    /** Merge the crawler's manifest pool into the saved pool on an IO thread. */
    fun importBundledServerPool() {
        appScope.launch(Dispatchers.IO) {
            val text = runCatching {
                assets.open("server_pool.json").bufferedReader().use { it.readText() }
            }.getOrElse { error ->
                serverPool.noteBundledPoolMissing(error.message ?: "missing")
                android.util.Log.w("NovaServerPool", "no bundled pool", error)
                return@launch
            }
            val added = serverPool.importBundled(text)
            val error = serverPool.state.value.lastError
            if (error.isNotBlank()) android.util.Log.w("NovaServerPool", error)
            else android.util.Log.i("NovaServerPool", "imported $added bundled servers")
        }
    }

    /**
     * Import the pool that ships in the APK's assets, once.
     *
     * CI publishes `site_pool.json` and the build copies it into assets, so the
     * crawler's work becomes sites the viewer can use without a crawl on the
     * phone. Off the main thread, and safe twice: [SitePool.import] dedupes by host.
     */
    fun importBundledSitePool() {
        appScope.launch(Dispatchers.IO) {
            val text = runCatching {
                assets.open("site_pool.json").bufferedReader().use { it.readText() }
            }.getOrElse { error ->
                android.util.Log.w("NovaSitePool", "no bundled pool in assets", error)
                sitePool.noteBundledPoolError(error.message ?: "missing asset")
                return@launch
            }
            val added = runCatching { sitePool.import(text) }.getOrElse { error ->
                android.util.Log.w("NovaSitePool", "bundled pool not imported", error)
                sitePool.noteBundledPoolError(error.message ?: "import failed")
                return@launch
            }
            val error = sitePool.state.value.lastError
            if (error.isNotBlank()) android.util.Log.w("NovaSitePool", error)
            else android.util.Log.i("NovaSitePool", "imported $added bundled sites")
        }
    }

    /** One engine for the whole app so discovery and probes are cached across titles. */
    val engine: SourceEngine by lazy {
        SourceEngine(
            repository, zeroPlayFinder, stremioFinder, websiteFinder, zeroPlayFinder, library, probe,
            pool = serverPool, harvester = ServerHarvester(http), sitePool = sitePool
        )
    }

    /**
     * Artwork goes through the same guarded OkHttp client as addon calls, so a
     * poster URL from an addon cannot reach a private address either.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { http.okHttp }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        NovaDns.provider = settings.settings.value.dns
        // Each import uses Dispatchers.IO so neither 3000-entry JSON file
        // blocks the first frame. They remain independent pools and capacities.
        importBundledServerPool()
        importBundledSitePool()
    }

    companion object {
        lateinit var instance: NovaApplication
            private set
    }
}
