package dev.nova.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.remote.HttpClient
import dev.nova.tv.data.remote.NovaDns
import dev.nova.tv.data.remote.SubtitleFinder
import dev.nova.tv.finder.pool.PoolStore
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.finder.pool.WebsitePool
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.download.DownloadManager
import dev.nova.tv.data.repo.LibraryStore
import dev.nova.tv.data.repo.SettingsStore
import dev.nova.tv.finder.SourceEngine
import dev.nova.tv.finder.SourceFinder
import dev.nova.tv.finder.StremioFinder
import dev.nova.tv.finder.website.WebsiteFinder
import dev.nova.tv.finder.website.WebsiteSpider
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
    /** The website-server finder engine; it profiles and probes real sites. */
    val websiteSpider: WebsiteSpider by lazy { WebsiteSpider(http, websiteFinder) }
    val probe: StreamProbe by lazy { StreamProbe(http.okHttp) }

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
            pool = serverPool, harvester = ServerHarvester(http, webCrawler),
            websitePool = websitePool, spider = websiteSpider
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
