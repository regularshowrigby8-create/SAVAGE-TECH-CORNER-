package dev.nova.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.data.remote.NovaDns
import dev.nova.tv.data.remote.SubtitleFinder
import dev.nova.tv.finder.addons.AddonFinder
import dev.nova.tv.finder.addons.AddonFinderChecker
import dev.nova.tv.finder.addons.AddonFinderHarvester
import dev.nova.tv.finder.addons.AddonFinderStore
import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.finder.pool.WebsitePool
import dev.nova.tv.finder.servers.ServerFinder
import dev.nova.tv.finder.servers.ServerSeeds
import dev.nova.tv.finder.servers.ServerFinderChecker
import dev.nova.tv.finder.servers.ServerFinderHarvester
import dev.nova.tv.finder.servers.ServerFinderStore
import dev.nova.tv.finder.translated.FinderBrowser
import dev.nova.tv.data.repo.CatalogRepository
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
    val repository: CatalogRepository by lazy {
        CatalogRepository(
            addonClient,
            settings,
            // OpenSubtitles v3 + the release-matched REST index, cached on device.
            SubtitleFinder(http, File(cacheDir, "subtitles"))
        )
    }
    /** One crawler shared by the ZeroPlay finder and the pool harvester. */
    val webCrawler: dev.nova.tv.finder.WebCrawler by lazy { dev.nova.tv.finder.WebCrawler(http) }
    val zeroPlayFinder: SourceFinder by lazy { SourceFinder(http, addonClient, webCrawler) }
    val stremioFinder: StremioFinder by lazy { StremioFinder(http) }
    val websiteFinder: WebsiteFinder by lazy { WebsiteFinder(http) }
    /**
     * Translated Python finders (servers.py / addons.py), in Kotlin.
     *
     * Both live in `src/main`, so the `tv` and `mobile` flavours share them.
     * Playwright has no Android equivalent, but Android's own Chromium does the
     * same job: FinderBrowser renders through `WebView`. Without a context it
     * cannot render at all and every seed page falls back to plain HTTP, which
     * is what left the website pool at 0 on device.
     */
    val finderBrowser: FinderBrowser by lazy { FinderBrowser(this) }
    val serverFinderHttp by lazy { ServerSeeds.http(http.okHttp, finderBrowser) }
    /** Precision-governed discovery: no seed list, one Outcome per candidate. */
    val serverDiscovery: ServerFinder by lazy { ServerFinder(this) }
    val addonFinderHttp by lazy { AddonFinder.http(http.okHttp, finderBrowser) }

    private fun fileStore(name: String): PoolStore {
        val file = java.io.File(filesDir, name)
        return object : PoolStore {
            override fun read(): String? = if (file.exists()) file.readText() else null
            override fun write(text: String) {
                val tmp = java.io.File(filesDir, "$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
            }
        }
    }

    /** servers.py pool: servers_pool.json {schema, generated_at, cap, count, servers[]}. */
    val serverFinderStore: ServerFinderStore by lazy { ServerFinderStore(fileStore("servers_pool.json")) }
    /** addons.py pool: addons_pool.json {schema, generated_at, cap, count, addons[]}. */
    val addonFinderStore: AddonFinderStore by lazy { AddonFinderStore(fileStore("addons_pool.json")) }

    /**
     * servers.py harvester: replaces the old WebsiteSpider script.
     *
     * The profiler teaches each discovered host where its search lives, so the
     * website pool holds sites that can actually answer for a title. Without
     * it the pool filled with bare media origins that never matched anything.
     */
    val serverFinderHarvester: ServerFinderHarvester by lazy {
        ServerFinderHarvester(
            serverFinderStore, serverFinderHttp, finderBrowser,
            profiler = { url -> websiteFinder.profile(url) }
        )
    }
    /** addons.py harvester: replaces the old ServerHarvester script. */
    val addonFinderHarvester: AddonFinderHarvester by lazy {
        AddonFinderHarvester(addonFinderStore, addonFinderHttp, finderBrowser)
    }

    val serverFinderChecker: ServerFinderChecker by lazy {
        ServerFinderChecker(
            serverFinderStore, serverFinderHttp, serverFinderHarvester.prober, serverFinderHarvester,
            deadStore = fileStore("dead_servers.json"), deadTxtStore = fileStore("dead_servers.txt")
        )
    }
    val addonFinderChecker: AddonFinderChecker by lazy {
        AddonFinderChecker(
            addonFinderStore, addonFinderHarvester,
            deadStore = fileStore("addons_dead.json"), deadTxtStore = fileStore("addons_dead.txt")
        )
    }
    val probe: StreamProbe by lazy { StreamProbe(http.okHttp) }

    /** Community intro timestamps (keyless), for the player's skip-intro button. */
    val introLookup: dev.nova.tv.player.IntroLookup by lazy {
        dev.nova.tv.player.IntroLookup(http)
    }

    /** Up to 6000 server options, saved between launches. */
    val serverPool: ServerPool by lazy {
        val file = java.io.File(filesDir, "server_pool.json")
        ServerPool(object : PoolStore {
            override fun read(): String? = if (file.exists()) file.readText() else null
            override fun write(text: String) {
                // Write then rename, so a crash mid-save never leaves half a pool.
                val tmp = java.io.File(filesDir, "server_pool.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
            }
        })
    }

    /**
     * Up to 6000 streaming websites, kept apart from the addon pool so either
     * side can be switched off on its own.
     */
    val websitePool: WebsitePool by lazy {
        val file = java.io.File(filesDir, "website_pool.json")
        WebsitePool(object : PoolStore {
            override fun read(): String? = if (file.exists()) file.readText() else null
            override fun write(text: String) {
                val tmp = java.io.File(filesDir, "website_pool.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
            }
        })
    }

    /**
     * Offline downloads. Files live in app-specific external storage, which
     * needs no runtime permission and is removed with the app.
     */
    val downloads: DownloadManager by lazy {
        DownloadManager(
            dir = File(getExternalFilesDir(null) ?: filesDir, "Downloads"),
            baseClient = http.okHttp,
            scope = appScope
        )
    }

    /** One engine for the whole app so discovery and probes are cached across titles. */
    val engine: SourceEngine by lazy {
        SourceEngine(
            repository, stremioFinder, websiteFinder, zeroPlayFinder, library, probe,
            pool = serverPool, harvester = addonFinderHarvester,
            websitePool = websitePool, spider = serverFinderHarvester,
            discovery = serverDiscovery
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
    }

    companion object {
        lateinit var instance: NovaApplication
            private set
    }
}
