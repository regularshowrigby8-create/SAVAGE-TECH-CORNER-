package dev.nova.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nova.tv.NovaApplication
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.model.Video
import dev.nova.tv.data.repo.CatalogOrigin
import dev.nova.tv.data.repo.WatchProgress
import dev.nova.tv.download.DownloadJob
import dev.nova.tv.data.remote.DnsProvider
import dev.nova.tv.data.remote.NovaDns
import dev.nova.tv.finder.DualAudio
import dev.nova.tv.finder.FinderKind
import dev.nova.tv.finder.pool.PoolState
import dev.nova.tv.finder.pool.ServerHarvester
import dev.nova.tv.finder.pool.ServerPool
import dev.nova.tv.finder.website.SitePoolState
import dev.nova.tv.player.preload.PreloadMode
import dev.nova.tv.finder.FinderReport
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.SourceSnapshot
import dev.nova.tv.finder.TitleRequest
import dev.nova.tv.finder.website.SavedWebsite
import dev.nova.tv.player.ChromeController
import dev.nova.tv.player.HoldReason
import dev.nova.tv.player.PlayerUiState
import dev.nova.tv.player.SubtitleStyle
import dev.nova.tv.ui.nav.BackAction
import dev.nova.tv.ui.nav.NavSnapshot
import dev.nova.tv.ui.nav.resolveBack
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** Rail destinations, in rail order. */
enum class Route(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SERIES("Series"),
    DUAL_AUDIO("Dual Audio"),
    SEARCH("Search"),
    MY_LIST("My List"),
    SOURCES("Sources"),
    SETTINGS("Settings")
}

enum class ShelfStyle { POSTER, LANDSCAPE }

/** One endless row. [skip] is how many the catalogue has already given. */
data class Shelf(
    val key: String,
    val title: String,
    val type: String = "movie",
    val catalog: String = "top",
    val genre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val style: ShelfStyle = ShelfStyle.POSTER,
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    /** Progress for landscape cards, by title id. */
    val progress: Map<String, Float> = emptyMap(),
    /** The raw provider cursor and pinned origin survive filtering and paging. */
    val international: Boolean = false,
    val cursor: Int = 0,
    val origin: CatalogOrigin? = null
)

/** Movies / Series screens: one genre at a time, endless grid. */
data class BrowseState(
    val genre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val cursor: Int = 0,
    val origin: CatalogOrigin? = null,
    val error: String? = null
)

/** The opened title. Sources stream in while the viewer looks at it. */
data class DetailState(
    val meta: MetaPreview,
    val full: MetaPreview? = null,
    val season: Int = 1,
    val episode: Int = 1,
    val sources: SourceSnapshot = SourceSnapshot(),
    val resolving: Boolean = true,
    val inList: Boolean = false,
    val resume: WatchProgress? = null,
    val showSources: Boolean = false
) {
    val title: MetaPreview get() = full ?: meta
    val isSeries: Boolean get() = meta.isSeries
    val currentVideo: Video? get() = title.videos.firstOrNull { it.season == season && it.episode == episode }
    val streamId: String get() = FinderRules.catalogueId(meta.id, isSeries, season, episode, currentVideo?.id)
}

/** Which panel is open over the player. */
enum class PlayerPanel(val label: String) { SOURCES("Sources"), AUDIO("Audio"), SUBTITLES("Subtitles") }

/** Everything one playback needs: the whole ranked list, not one stream. */
data class PlaybackRequest(
    val title: String,
    val meta: MetaPreview,
    val season: Int,
    val episode: Int,
    val streams: List<Stream>,
    val startIndex: Int,
    val startPositionMs: Long = 0L,
    val bandwidthEstimate: Long = 0L,
    val subtitles: List<SubtitleRef> = emptyList(),
    /** Bumped on every Play press so the same list can be restarted. */
    val token: Long = System.nanoTime()
)

/** The up-next card at the end of an episode. */
data class UpNext(val season: Int, val episode: Int, val label: String, val thumbnail: String?)

data class NovaState(
    val route: Route = Route.HOME,
    val homeLoading: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val hero: List<MetaPreview> = emptyList(),
    val homeError: String? = null,
    val browse: Map<Route, BrowseState> = emptyMap(),
    val genres: Map<String, List<String>> = emptyMap(),
    val query: String = "",
    val results: List<MetaPreview> = emptyList(),
    val searching: Boolean = false,
    val myList: List<MetaPreview> = emptyList(),
    val detail: DetailState? = null,
    val playback: PlaybackRequest? = null,
    val playerPanel: PlayerPanel? = null,
    val upNext: UpNext? = null,
    val restoreFocusId: String? = null,
    val addons: List<NovaAddon> = emptyList(),
    val websites: List<SavedWebsite> = emptyList(),
    val finders: Set<FinderKind> = FinderKind.entries.toSet(),
    val finderReports: Map<FinderKind, FinderReport> = emptyMap(),
    val finderRunning: Set<FinderKind> = emptySet(),
    val toast: String? = null,
    val addingWebsite: Boolean = false,
    val websiteDraft: String = "",
    /** The same URL sheet, in addon mode: a Stremio-style server address. */
    val addingAddon: Boolean = false,
    val addonDraft: String = "",
    val reduceMotion: Boolean = false,
    val autoplayNext: Boolean = true,
    val preload: PreloadMode = PreloadMode.ON,
    val dns: DnsProvider = DnsProvider.AUTO,
    val preferEnglishAudio: Boolean = true,
    /** Caption size, box and tone; applied live by both player surfaces. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Standard play target; 720p unless changed in Settings. */
    val preferredQuality: String = "720p",
    /** Offline downloads, newest last. */
    val downloads: List<DownloadJob> = emptyList(),
    /** Pool servers that have answered for a title; the only ones listed. */
    val workingServers: List<String> = emptyList(),
    val dualShelves: List<Shelf> = emptyList(),
    val dualLoading: Boolean = false,
    val pool: PoolState = PoolState(),
    val poolBusy: Boolean = false,
    /** False when the fill loop has stopped (full, exhausted, or stopped by the viewer). */
    val poolFilling: Boolean = false,
    /** Measured harvester counters; null when no harvester is configured. */
    val harvest: ServerHarvester.HarvestState? = null,
    /**
     * The second pool: streaming sites the crawler verified. Counted separately
     * from [pool] because a site and an addon manifest are not the same thing.
     */
    val sites: SitePoolState = SitePoolState()
) {
    val opened: MetaPreview? get() = detail?.meta
}

class NovaViewModel : ViewModel() {

    private val app: NovaApplication = NovaApplication.instance
    private val settings = app.settings
    private val repository = app.repository
    private val library = app.library
    private val engine = app.engine

    private val _state = MutableStateFlow(NovaState())
    val state: StateFlow<NovaState> = _state.asStateFlow()

    /** video.js `computeVisible`, owned here so BACK can ask whether the controls show. */
    val chrome = ChromeController(viewModelScope)
    val chromeState: StateFlow<PlayerUiState> get() = chrome.state
    private var menuHold: (() -> Unit)? = null

    private var resolveJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private var autoPlayJob: Job? = null
    private var searchJob: Job? = null
    private var toastJob: Job? = null
    private val shelfJobs = HashMap<String, Job>()
    private var shelfPlan: List<Shelf> = emptyList()
    private var dualPlan: List<Shelf> = emptyList()
    private val dualEmpty = HashSet<String>()

    init {
        val saved = settings.settings.value
        _state.value = _state.value.copy(
            reduceMotion = saved.reduceMotion,
            autoplayNext = saved.autoplayNext,
            finders = saved.finders,
            preload = saved.preload,
            dns = saved.dns,
            preferEnglishAudio = saved.preferEnglishAudio,
            subtitleStyle = saved.subtitleStyle,
            preferredQuality = saved.preferredQuality,
            pool = engine.pool?.state?.value ?: PoolState(),
            workingServers = engine.pool?.working()?.map { it.name.ifBlank { it.baseUrl } }.orEmpty(),
            websites = library.websites.value,
            myList = library.myList.value.map { it.toMeta() }
        )
        refresh()
        // Background manifest refreshes and restored user addons should appear
        // without requiring the viewer to restart the app.
        viewModelScope.launch {
            repository.addons.collect { addons ->
                _state.update { it.copy(addons = addons, genres = mapOf("movie" to repository.genres("movie"), "series" to repository.genres("series"))) }
                if (!_state.value.homeLoading) {
                    val known = shelfPlan.map { it.key }.toSet()
                    shelfPlan = shelfPlan + planShelves().filter { it.origin != null && it.key !in known }
                }
            }
        }
        engine.pool?.let { pool ->
            viewModelScope.launch {
                pool.state.collect { p ->
                    _state.update {
                        it.copy(
                            pool = p,
                            poolFilling = engine.filling,
                            workingServers = pool.working().map { entry -> entry.name.ifBlank { entry.baseUrl } }
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            app.sitePool.state.collect { s -> _state.update { it.copy(sites = s) } }
        }
        // A bundled import may finish after the Sources screen is opened.
        viewModelScope.launch {
            library.websites.collect { sites -> _state.update { it.copy(websites = sites) } }
        }
        // The harvester's own counters: how many sources are done, how many
        // requests were made, whether anything is still being looked at. The
        // Sources screen prints these, so the progress it shows is measured.
        viewModelScope.launch {
            while (true) {
                val harvest = engine.harvestState
                _state.update { it.copy(harvest = harvest, poolFilling = engine.filling) }
                delay(HARVEST_POLL_MS)
            }
        }
        viewModelScope.launch {
            app.downloads.jobs.collect { jobs -> _state.update { it.copy(downloads = jobs) } }
        }
    }

    // -- navigation -----------------------------------------------------------

    fun navigate(route: Route) {
        val s = _state.value
        if (s.route == route && s.detail == null) return
        closeTitle()
        _state.update { it.copy(route = route, addingWebsite = false, addingAddon = false) }
        when (route) {
            Route.MOVIES, Route.SERIES -> if (_state.value.browse[route]?.items.isNullOrEmpty()) loadBrowse(route, reset = true)
            Route.DUAL_AUDIO -> if (_state.value.dualShelves.isEmpty()) loadDualAudio()
            Route.MY_LIST -> _state.update { it.copy(myList = library.myList.value.map { t -> t.toMeta() }) }
            Route.SOURCES -> _state.update { it.copy(addons = repository.addons.value, websites = library.websites.value) }
            else -> Unit
        }
    }

    private fun snapshot(): NavSnapshot {
        val s = _state.value
        return NavSnapshot(
            atHome = s.route == Route.HOME,
            detailOpen = s.detail != null,
            playing = s.playback != null,
            panelOpen = s.playerPanel != null,
            chromeVisible = chrome.state.value.chromeVisible,
            queryNotEmpty = s.route == Route.SEARCH && s.query.isNotEmpty(),
            sheetOpen = s.detail?.showSources == true || s.addingWebsite || s.addingAddon
        )
    }

    /** BACK: peel exactly one layer. Returns false only when the app should exit. */
    fun back(): Boolean {
        when (resolveBack(snapshot())) {
            BackAction.CLOSE_PLAYER_PANEL -> closePlayerPanel()
            BackAction.SHOW_PLAYER_CHROME -> chrome.onUserActive()
            BackAction.STOP_PLAYBACK -> stopPlayback()
            BackAction.CLOSE_SHEET -> closeSheet()
            BackAction.CLOSE_DETAIL -> closeTitle()
            BackAction.CLEAR_QUERY -> setQuery("")
            BackAction.GO_HOME -> navigate(Route.HOME)
            BackAction.EXIT -> return false
        }
        return true
    }

    private fun closeSheet() {
        _state.update { s ->
            s.copy(detail = s.detail?.copy(showSources = false), addingWebsite = false, addingAddon = false)
        }
    }

    // -- home -------------------------------------------------------------------

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        _state.update { it.copy(homeLoading = true, homeError = null) }
        refreshJob = viewModelScope.launch {
            var shelves: List<Shelf> = emptyList()
            var failure: String? = null
            try {
                repository.installDefaults()
                runCatching { engine.warmUp(_state.value.finders) }
                runCatching { engine.syncPoolFill(_state.value.finders) }
                shelfPlan = planShelves()
                val first = shelfPlan.take(INITIAL_SHELVES)
                var attempted = first.map { shelf -> async { loadShelf(shelf) } }.awaitAll()
                var loaded = attempted.map { it.first }.filter { it.items.isNotEmpty() }
                // If the general rows all fail, try the other named services
                // before presenting an error. A niche but available catalogue is
                // more useful than an empty screen.
                if (loaded.isEmpty()) {
                    val backups = shelfPlan.drop(INITIAL_SHELVES).filter { it.origin != null }
                    val extra = backups.map { shelf -> async { loadShelf(shelf) } }.awaitAll()
                    attempted += extra
                    loaded = extra.map { it.first }.filter { it.items.isNotEmpty() }
                }
                val emptyKeys = attempted.filter { it.first.items.isEmpty() }.map { it.first.key }.toSet()
                shelfPlan = shelfPlan.filterNot { it.key in emptyKeys }
                shelves = libraryShelves() + loaded
                if (loaded.isEmpty()) failure = attempted.mapNotNull { it.second }.firstOrNull()
                    ?: "No catalogue returned titles. Check the connection and retry."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = "Couldn't load the catalogue. Check the connection and retry."
            } finally {
                if (generation == refreshGeneration) {
                    val hero = shelves.firstOrNull { it.items.isNotEmpty() }?.items.orEmpty()
                        .filter { !it.background.isNullOrBlank() || !it.poster.isNullOrBlank() }.take(HERO_COUNT)
                    val result = shelves
                    val reason = failure
                    _state.update {
                        val visible = result.ifEmpty { it.shelves }
                        val hasCatalogue = visible.any { shelf -> shelf.key != KEY_CONTINUE && shelf.key != KEY_MY_LIST }
                        it.copy(homeLoading = false, shelves = visible,
                            hero = hero.ifEmpty { it.hero },
                            homeError = if (!hasCatalogue) reason ?: "Couldn't reach the catalogue." else null,
                            addons = repository.addons.value,
                            genres = mapOf("movie" to repository.genres("movie"), "series" to repository.genres("series")))
                    }
                }
            }
        }
    }

    private suspend fun loadShelf(shelf: Shelf): Pair<Shelf, String?> {
        val page = repository.loadPage(shelf.type, shelf.catalog, shelf.genre, origin = shelf.origin)
        return shelf.copy(items = page.items, origin = page.origin ?: shelf.origin,
            cursor = page.nextSkip, exhausted = page.exhausted) to page.error
    }

    /**
     * The endless home: Popular / Featured / New for both types, then every
     * genre for both, interleaved. Only the first few load; the rest load as
     * the viewer scrolls down ([loadMoreShelves]).
     */
    private fun planShelves(): List<Shelf> {
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        val base = listOf(
            Shelf("movie:top", "Popular Movies", "movie", "top"),
            Shelf("series:top", "Popular Series", "series", "top"),
            Shelf("movie:imdbRating", "Top Rated Movies", "movie", "imdbRating"),
            Shelf("series:imdbRating", "Top Rated Series", "series", "imdbRating"),
            Shelf("movie:year", "New Movies", "movie", "year", genre = year),
            Shelf("series:year", "New Series", "series", "year", genre = year)
        )
        val providerShelves = repository.homeCatalogs().map { origin ->
            val label = origin.catalog.name.ifBlank { origin.addon.name }
            Shelf(
                key = "addon:${origin.addon.baseUrl}:${origin.catalog.type}:${origin.catalog.id}",
                title = if (label.equals(origin.addon.name, true)) label else "${origin.addon.name} · $label",
                type = origin.catalog.type, catalog = origin.catalog.id, origin = origin
            )
        }
        val movieGenres = repository.genres("movie")
        val seriesGenres = repository.genres("series")
        val genreShelves = (0 until maxOf(movieGenres.size, seriesGenres.size)).flatMap { i ->
            listOfNotNull(
                movieGenres.getOrNull(i)?.let { Shelf("movie:top:$it", "$it Movies", "movie", "top", it) },
                seriesGenres.getOrNull(i)?.let { Shelf("series:top:$it", "$it Series", "series", "top", it) }
            )
        }
        // Put a couple of explicitly named services on the first screen; the
        // rest follow the main rows as the viewer scrolls.
        return base.take(2) + providerShelves.take(2) + base.drop(2) + providerShelves.drop(2) + genreShelves
    }

    private fun libraryShelves(): List<Shelf> = buildList {
        val cw = library.continueWatching()
        if (cw.isNotEmpty()) add(
            Shelf(
                key = KEY_CONTINUE, title = "Continue Watching", items = cw.map { it.toMeta() },
                style = ShelfStyle.LANDSCAPE, exhausted = true, progress = cw.associate { it.id to it.fraction }
            )
        )
        val list = library.myList.value
        if (list.isNotEmpty()) add(Shelf(key = KEY_MY_LIST, title = "My List", items = list.map { it.toMeta() }, exhausted = true))
    }

    private fun refreshLibraryShelves() {
        _state.update { s ->
            s.copy(
                shelves = libraryShelves() + s.shelves.filterNot { it.key == KEY_CONTINUE || it.key == KEY_MY_LIST },
                myList = library.myList.value.map { it.toMeta() }
            )
        }
    }

    /** Focus reached the last loaded rows: add the next few. */
    fun loadMoreShelves() {
        val s = _state.value
        if (s.homeLoading || shelfJobs.containsKey(KEY_MORE)) return
        val loadedKeys = s.shelves.map { it.key }.toSet()
        val next = shelfPlan.filter { it.key !in loadedKeys }.take(MORE_SHELVES)
        if (next.isEmpty()) return
        shelfJobs[KEY_MORE] = viewModelScope.launch {
            try {
                val loaded = next.map { shelf -> async { loadShelf(shelf).first } }.awaitAll()
                shelfPlan = shelfPlan.filterNot { p -> loaded.any { it.key == p.key && it.items.isEmpty() } }
                _state.update { st -> st.copy(shelves = st.shelves + loaded.filter { it.items.isNotEmpty() }) }
            } finally {
                shelfJobs.remove(KEY_MORE)
            }
        }
    }

    /** Focus reached the end of a row: fetch its next page. */
    fun loadMoreInShelf(key: String) {
        val shelf = (_state.value.shelves + _state.value.dualShelves).firstOrNull { it.key == key } ?: return
        if (shelf.exhausted || shelf.loading || shelfJobs.containsKey(key)) return
        updateShelf(key) { it.copy(loading = true) }
        if (shelf.international) {
            shelfJobs[key] = viewModelScope.launch {
                try {
                    val result = loadInternational(shelf, shelf.cursor, DUAL_MORE)
                    updateShelf(key) { current ->
                        val fresh = result.items.filter { p -> current.items.none { it.id == p.id } }
                        current.copy(items = current.items + fresh, loading = false,
                            exhausted = result.exhausted || fresh.isEmpty(), cursor = result.cursor, origin = result.origin)
                    }
                } finally { shelfJobs.remove(key) }
            }
            return
        }
        shelfJobs[key] = viewModelScope.launch {
            try {
                val page = repository.loadPage(shelf.type, shelf.catalog, shelf.genre, skip = shelf.cursor, origin = shelf.origin)
                updateShelf(key) { current ->
                    val fresh = page.items.filter { p -> current.items.none { it.id == p.id } }
                    current.copy(items = current.items + fresh, loading = false,
                        exhausted = page.exhausted || fresh.isEmpty(), cursor = page.nextSkip, origin = page.origin ?: current.origin)
                }
            } finally { shelfJobs.remove(key) }
        }
    }

    private fun updateShelf(key: String, change: (Shelf) -> Shelf) {
        _state.update { s ->
            s.copy(
                shelves = s.shelves.map { if (it.key == key) change(it) else it },
                dualShelves = s.dualShelves.map { if (it.key == key) change(it) else it }
            )
        }
    }

    // -- dual audio ---------------------------------------------------------------

    /**
     * Dual Audio: titles made outside the English-speaking world, where
     * releases often carry the original track plus an English dub. Rows are the
     * normal catalogues filtered by [DualAudio.isInternational]; the source
     * engine then ranks streams that carry English plus another language first.
     */
    fun loadDualAudio() {
        if (_state.value.dualLoading) return
        _state.update { it.copy(dualLoading = true) }
        viewModelScope.launch {
            if (repository.addons.value.isEmpty()) runCatching { repository.installDefaults() }
            dualPlan = buildList {
                add(Shelf("dual:movie:top", "World Cinema", "movie", "top", international = true))
                add(Shelf("dual:series:top", "International Series", "series", "top", international = true))
                add(Shelf("dual:movie:imdbRating", "Acclaimed Abroad", "movie", "imdbRating", international = true))
                add(Shelf("dual:series:imdbRating", "Top Rated International Series", "series", "imdbRating", international = true))
                DUAL_GENRES.forEach { g ->
                    add(Shelf("dual:series:top:$g", "$g · International", "series", "top", g, international = true))
                    add(Shelf("dual:movie:top:$g", "$g · World Cinema", "movie", "top", g, international = true))
                }
            }
            val loaded = loadDualShelves(dualPlan.take(INITIAL_SHELVES))
            _state.update { it.copy(dualShelves = loaded, dualLoading = false) }
        }
    }

    /** Focus reached the last Dual Audio rows: add the next few. */
    fun loadMoreDualShelves() {
        val s = _state.value
        if (s.dualLoading || shelfJobs.containsKey(KEY_MORE_DUAL)) return
        val shown = s.dualShelves.map { it.key }.toSet()
        val next = dualPlan.filter { it.key !in shown && it.key !in dualEmpty }.take(MORE_SHELVES)
        if (next.isEmpty()) return
        shelfJobs[KEY_MORE_DUAL] = viewModelScope.launch {
            val loaded = loadDualShelves(next)
            dualEmpty += next.map { it.key } - loaded.map { it.key }.toSet()
            _state.update { st -> st.copy(dualShelves = st.dualShelves + loaded) }
            shelfJobs.remove(KEY_MORE_DUAL)
        }
    }

    private suspend fun loadDualShelves(plan: List<Shelf>): List<Shelf> = coroutineScope {
        plan.map { shelf ->
            async {
                val result = loadInternational(shelf, 0, DUAL_FIRST)
                shelf.copy(items = result.items, cursor = result.cursor, exhausted = result.exhausted, origin = result.origin)
            }
        }.awaitAll().filter { it.items.isNotEmpty() }
    }

    private data class InternationalPage(val items: List<MetaPreview>, val cursor: Int, val exhausted: Boolean, val origin: CatalogOrigin?)

    /** Page the same provider until [want] international titles or [DUAL_PAGES] pages. */
    private suspend fun loadInternational(shelf: Shelf, start: Int, want: Int): InternationalPage {
        val out = LinkedHashMap<String, MetaPreview>()
        var cursor = start
        var origin = shelf.origin
        repeat(DUAL_PAGES) {
            val page = repository.loadPage(shelf.type, shelf.catalog, shelf.genre, skip = cursor, origin = origin)
            origin = page.origin ?: origin
            if (page.items.isEmpty()) return InternationalPage(out.values.toList(), cursor, true, origin)
            cursor = page.nextSkip
            page.items.filter { DualAudio.isInternational(it) }.forEach { out.putIfAbsent(it.id, it) }
            if (page.exhausted) return InternationalPage(out.values.toList(), cursor, true, origin)
            if (out.size >= want) return InternationalPage(out.values.toList(), cursor, false, origin)
        }
        return InternationalPage(out.values.toList(), cursor, false, origin)
    }

    // -- movies / series --------------------------------------------------------

    fun selectGenre(route: Route, genre: String?) {
        val current = _state.value.browse[route]
        if (current != null && current.genre == genre && current.items.isNotEmpty()) return
        shelfJobs.remove("browse:$route")?.cancel()
        _state.update { it.copy(browse = it.browse + (route to BrowseState(genre = genre))) }
        loadBrowse(route, reset = true)
    }

    fun loadBrowse(route: Route, reset: Boolean = false) {
        val type = if (route == Route.SERIES) "series" else "movie"
        val current = _state.value.browse[route] ?: BrowseState()
        if (current.loading || (current.exhausted && !reset)) return
        val key = "browse:$route"
        if (shelfJobs.containsKey(key)) return
        val skip = if (reset) 0 else current.cursor
        _state.update { it.copy(browse = it.browse + (route to current.copy(loading = true,
            items = if (reset) emptyList() else current.items, error = null))) }
        shelfJobs[key] = viewModelScope.launch {
            try {
                if (repository.addons.value.isEmpty()) repository.installDefaults()
                val page = repository.loadPage(type, "top", current.genre, skip, if (reset) null else current.origin)
                _state.update { st ->
                    val b = st.browse[route] ?: BrowseState()
                    if (b.genre != current.genre) return@update st
                    val fresh = page.items.filter { p -> b.items.none { it.id == p.id } }
                    st.copy(
                        browse = st.browse + (route to b.copy(items = b.items + fresh, loading = false,
                            exhausted = page.exhausted || fresh.isEmpty(), cursor = page.nextSkip,
                            origin = page.origin ?: b.origin, error = page.error)),
                        genres = st.genres + (type to repository.genres(type))
                    )
                }
            } finally {
                if (shelfJobs[key] === kotlinx.coroutines.currentCoroutineContext()[Job]) shelfJobs.remove(key)
            }
        }
    }

    // -- detail -------------------------------------------------------------------

    fun openTitle(title: MetaPreview) {
        val resume = library.progress.value.firstOrNull { it.id == title.id }
        val season = if (title.isSeries) resume?.season?.takeIf { it > 0 } ?: 1 else 0
        val episode = if (title.isSeries) resume?.episode?.takeIf { it > 0 } ?: 1 else 0
        _state.update {
            it.copy(
                detail = DetailState(
                    meta = title, season = season, episode = episode,
                    inList = library.inList(title.id),
                    resume = resume?.takeIf { r -> !r.finished && r.season == season && r.episode == episode }
                ),
                restoreFocusId = title.id
            )
        }
        viewModelScope.launch {
            val full = repository.loadMeta(title.type, title.id) ?: return@launch
            _state.update { s ->
                val d = s.detail ?: return@update s
                if (d.meta.id != title.id) return@update s
                // A series with a known list starts on its first real episode.
                val firstSeason = full.seasons.firstOrNull { it > 0 } ?: d.season
                val keep = full.videos.any { it.season == d.season && it.episode == d.episode }
                val enriched = full.copy(imdbId = full.imdbId ?: d.meta.imdbId)
                s.copy(detail = d.copy(full = enriched, season = if (keep) d.season else firstSeason, episode = if (keep) d.episode else (enriched.episodes(firstSeason).firstOrNull()?.episode ?: 1)))
            }
            if (title.isSeries) resolve()
        }
        resolve()
    }

    /**
     * Play straight from a card: open the title, then start the best source
     * the moment one is ranked. Leaving the page cancels the wait.
     */
    fun openAndPlay(title: MetaPreview) {
        openTitle(title)
        autoPlayJob?.cancel()
        autoPlayJob = viewModelScope.launch {
            val ready = withTimeoutOrNull(NEXT_WAIT_MS) {
                state.first { s ->
                    val d = s.detail
                    d == null || d.meta.id != title.id || s.playback != null ||
                        (d.sources.streams.isNotEmpty() && (d.sources.probes.isNotEmpty() || d.sources.done)) || d.sources.done
                }
            }
            val s = _state.value
            val d = s.detail
            if (d == null || d.meta.id != title.id || s.playback != null) return@launch
            when {
                d.sources.streams.isNotEmpty() -> play(0)
                ready != null -> toast("No source found yet")
                else -> Unit // still searching: the page's Play button takes over
            }
        }
    }

    fun selectEpisode(season: Int, episode: Int) {
        val d = _state.value.detail ?: return
        if (!d.isSeries || (d.season == season && d.episode == episode)) return
        val resume = library.progressFor(d.meta.id, season, episode)?.takeIf { !it.finished }
        _state.update { it.copy(detail = d.copy(season = season, episode = episode, resume = resume, sources = SourceSnapshot(), resolving = true)) }
        resolve()
    }

    fun selectSeason(season: Int) {
        val d = _state.value.detail ?: return
        val first = d.title.episodes(season).firstOrNull()?.episode ?: 1
        selectEpisode(season, first)
    }

    /** Start (or restart) the source engine for whatever the detail shows. */
    private fun resolve() {
        val d = _state.value.detail ?: return
        resolveJob?.cancel()
        val st = _state.value
        val dual = st.preferEnglishAudio && (st.route == Route.DUAL_AUDIO || DualAudio.isInternational(d.title))
        val request = TitleRequest(
            d.title, d.season, d.episode, st.finders,
            preferDualAudio = dual, preferredQuality = st.preferredQuality
        )
        val id = d.streamId
        _state.update { s -> s.copy(detail = s.detail?.copy(sources = SourceSnapshot(), resolving = true)) }
        resolveJob = viewModelScope.launch {
            engine.resolve(request).collect { snap ->
                _state.update { s ->
                    val cur = s.detail ?: return@update s
                    if (cur.streamId != id) return@update s
                    s.copy(detail = cur.copy(sources = snap, resolving = !snap.done))
                }
                if (snap.installed.isNotEmpty()) _state.update { it.copy(addons = repository.addons.value) }
            }
        }
    }

    fun toggleSourcesSheet() {
        _state.update { s -> s.copy(detail = s.detail?.let { it.copy(showSources = !it.showSources) }) }
    }

    fun toggleMyList() {
        val d = _state.value.detail ?: return
        val now = library.toggleList(d.title)
        _state.update { it.copy(detail = d.copy(inList = now)) }
        refreshLibraryShelves()
        toast(if (now) "Added to My List" else "Removed from My List")
    }

    fun closeTitle() {
        resolveJob?.cancel()
        autoPlayJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    // -- playback -------------------------------------------------------------------

    /** Play the best source (or [index]); the player keeps the rest as failover. */
    fun play(index: Int = 0, fromStart: Boolean = false) {
        val s = _state.value
        val d = s.detail ?: return
        val streams = d.sources.streams
        if (streams.isEmpty()) return
        val label = if (d.isSeries) "${d.title.name}  ·  S${d.season} E${d.episode}" else d.title.name
        val resumeAt = if (fromStart) 0L else d.resume?.positionMs ?: 0L
        _state.update {
            it.copy(
                playback = PlaybackRequest(
                    title = label, meta = d.title, season = d.season, episode = d.episode,
                    streams = streams, startIndex = index.coerceIn(0, streams.lastIndex),
                    startPositionMs = resumeAt, bandwidthEstimate = d.sources.bandwidthEstimate
                ),
                playerPanel = null, upNext = null,
                detail = d.copy(showSources = false)
            )
        }
        chrome.clear()
        chrome.onUserActive()
        val type = if (d.isSeries) "series" else "movie"
        // Subtitles after playback starts (WuPlay SDK rule): they never delay Play.
        viewModelScope.launch {
            val subtitleId = TitleRequest(d.title, d.season, d.episode).imdbStreamId ?: d.streamId
            val subs = runCatching { repository.loadSubtitles(type, subtitleId) }.getOrDefault(emptyList())
            _state.update { st -> st.playback?.let { pb -> st.copy(playback = pb.copy(subtitles = subs)) } ?: st }
        }
    }

    /** The player reports where it is; saved for Continue Watching and resume. */
    fun saveProgress(pb: PlaybackRequest, positionMs: Long, durationMs: Long, stream: Stream?) {
        if (durationMs <= 0) return
        library.saveProgress(
            WatchProgress(
                id = pb.meta.id, type = pb.meta.type, name = pb.meta.name,
                poster = pb.meta.poster, background = pb.meta.background,
                season = pb.season, episode = pb.episode,
                positionMs = positionMs, durationMs = durationMs
            )
        )
        if (stream != null) library.saveLastSource(pb.meta.id, stream.behaviorHints?.bingeGroup, stream.sourceBase)
        // The final save arrives after the player closed: refresh what shows it.
        if (_state.value.playback?.token != pb.token) afterPlayback()
    }

    private fun afterPlayback() {
        refreshLibraryShelves()
        val d = _state.value.detail ?: return
        val resume = library.progressFor(d.meta.id, d.season, d.episode)?.takeIf { !it.finished }
        _state.update { it.copy(detail = d.copy(resume = resume)) }
    }

    fun retrySources() = resolve()

    /** The next episode after the one playing, from the meta episode list. */
    fun nextEpisode(): UpNext? {
        val pb = _state.value.playback ?: return null
        if (!pb.meta.isSeries) return null
        val ordered = pb.meta.videos.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        val i = ordered.indexOfFirst { it.season == pb.season && it.episode == pb.episode }
        val next = ordered.getOrNull(i + 1) ?: return null
        return UpNext(next.season, next.episode, "S${next.season} E${next.episode}  ·  ${next.label}", next.thumbnail)
    }

    fun onPlaybackEnded() {
        if (!_state.value.autoplayNext) return
        _state.update { it.copy(upNext = nextEpisode()) }
    }

    fun cancelUpNext() = _state.update { it.copy(upNext = null) }

    /**
     * Autoplay next (ARVIO NextEpisodeOverlay / WuPlay bingeGroup): resolve
     * the next episode with the last source preferred, then play as soon as
     * anything is ready.
     */
    fun playNext() {
        val next = _state.value.upNext ?: nextEpisode() ?: return
        val pb = _state.value.playback ?: return
        _state.update { it.copy(upNext = null, playback = null, playerPanel = null) }
        if (_state.value.detail?.meta?.id != pb.meta.id) {
            _state.update { it.copy(detail = DetailState(meta = pb.meta, full = pb.meta, inList = library.inList(pb.meta.id))) }
        }
        selectEpisode(next.season, next.episode)
        viewModelScope.launch {
            val ready = withTimeoutOrNull(NEXT_WAIT_MS) {
                state.first { s -> s.detail?.let { it.sources.streams.isNotEmpty() && (it.sources.probes.isNotEmpty() || it.sources.done) } == true }
            }
            if (ready != null) play(0, fromStart = true) else toast("No source for the next episode yet")
        }
    }

    fun stopPlayback() {
        menuHold?.invoke()
        menuHold = null
        chrome.clear()
        _state.update { it.copy(playback = null, playerPanel = null, upNext = null) }
        afterPlayback()
    }

    fun openPlayerPanel(panel: PlayerPanel) {
        menuHold?.invoke()
        menuHold = chrome.hold(HoldReason.MENU)
        _state.update { it.copy(playerPanel = panel) }
    }

    fun closePlayerPanel() {
        menuHold?.invoke()
        menuHold = null
        _state.update { it.copy(playerPanel = null) }
    }

    // -- search ---------------------------------------------------------------------

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, results = if (query.isBlank()) emptyList() else it.results) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            val found = runCatching { repository.search(query) }.getOrDefault(emptyList())
                .filter { it.name.isNotBlank() }
                .sortedByDescending { if (it.name.equals(query.trim(), true)) 100.0 else it.rating }
            if (_state.value.query == query) _state.update { it.copy(results = found, searching = false) }
        }
    }

    fun appendQuery(character: Char) = setQuery(_state.value.query + character)
    fun backspaceQuery() = setQuery(_state.value.query.dropLast(1))

    // -- sources: finders, websites, addons ----------------------------------------

    fun toggleFinder(kind: FinderKind) {
        val set = _state.value.finders
        val next = if (kind in set) set - kind else set + kind
        settings.setFinders(next)
        _state.update { it.copy(finders = next) }
        // The finders share one 3000-slot pool: whichever are on keep filling
        // it, so switching one off leaves the others to close the gap.
        engine.syncPoolFill(next)
    }

    /** Run one finder now and install whatever plays the top title. */
    fun runFinder(kind: FinderKind) {
        if (kind in _state.value.finderRunning) return
        _state.update { it.copy(finderRunning = it.finderRunning + kind) }
        viewModelScope.launch {
            val report = if (kind == FinderKind.TITLE) null else runCatching { engine.discover(kind, force = true) }.getOrNull()
            if (report != null) _state.update { it.copy(finderReports = it.finderReports + (kind to report)) }
            val probe = _state.value.shelves.firstOrNull { it.type == "movie" && it.style == ShelfStyle.POSTER }?.items?.firstOrNull()
            var installed = 0
            var found = 0
            var noServers = false
            if (probe != null) {
                val candidates = report?.candidates ?: engine.cachedCandidates()
                val snap = engine.resolve(TitleRequest(probe, finders = setOf(kind))).let { flow ->
                    var last = SourceSnapshot()
                    flow.collect { last = it }
                    last
                }
                installed = snap.installed.size
                found = snap.streams.size
                if (candidates.isEmpty() && (engine.pool?.state?.value?.size ?: 0) == 0 && kind == FinderKind.TITLE) noServers = true
            }
            _state.update {
                it.copy(
                    finderRunning = it.finderRunning - kind,
                    finderReports = if (report != null) it.finderReports + (kind to report) else it.finderReports,
                    addons = repository.addons.value
                )
            }
            toast(
                when {
                    installed > 0 -> "Added $installed new source${if (installed == 1) "" else "s"}"
                    noServers -> "No servers yet: run the ZeroPlay or Stremio finder"
                    report != null && report.sources.isNotEmpty() && report.sourcesFailed == report.sources.size -> "${kind.label}: discovery unavailable; check Sources"
                    probe == null -> "Catalogue not loaded yet"
                    found > 0 -> "${kind.label}: sources ready"
                    else -> "${kind.label}: nothing new found"
                }
            )
        }
    }

    fun removeAddon(baseUrl: String) {
        repository.remove(baseUrl)
        _state.update { it.copy(addons = repository.addons.value) }
    }

    fun startAddWebsite() = _state.update { it.copy(addingWebsite = true, websiteDraft = "") }
    fun cancelAddWebsite() = _state.update { it.copy(addingWebsite = false) }
    fun typeWebsite(c: Char) = _state.update { it.copy(websiteDraft = (it.websiteDraft + c).take(120)) }
    fun backspaceWebsite() = _state.update { it.copy(websiteDraft = it.websiteDraft.dropLast(1)) }
    /** Phones type with the system keyboard, so the whole field arrives at once. */
    fun setWebsiteDraft(text: String) = _state.update { it.copy(websiteDraft = text.trim().take(120)) }

    fun saveWebsite() {
        val draft = _state.value.websiteDraft.trim()
        // Saving used to return silently for a short draft, so a mistyped
        // address looked like a dead button.
        if (draft.length < 4 || !draft.contains('.')) {
            toast("Type a website address, like example.com")
            return
        }
        _state.update { it.copy(addingWebsite = false) }
        viewModelScope.launch {
            // Bounded: the sheet is already closed, so an unbounded profile
            // meant the button did nothing visible for as long as the host
            // took to time out.
            val site = withTimeoutOrNull(WEBSITE_ADD_TIMEOUT_MS) {
                runCatching { app.websiteFinder.profile(draft) }.getOrNull()
            }
            if (site == null) {
                toast("Couldn't add ${draft.take(40)}")
                return@launch
            }
            library.addWebsite(site)
            _state.update { it.copy(websites = library.websites.value) }
            toast("Added ${site.name}")
        }
    }

    fun removeWebsite(url: String) {
        library.removeWebsite(url)
        _state.update { it.copy(websites = library.websites.value) }
    }

    // -- addons ---------------------------------------------------------------
    //
    // CatalogRepository.add() existed but nothing in the UI ever called it, so
    // the Installed list could only ever show the built-ins and there was no
    // way to point Nova at another Stremio-compatible server.

    fun startAddAddon() = _state.update { it.copy(addingAddon = true, addonDraft = "") }
    fun cancelAddAddon() = _state.update { it.copy(addingAddon = false) }
    fun typeAddon(c: Char) = _state.update { it.copy(addonDraft = (it.addonDraft + c).take(160)) }
    fun backspaceAddon() = _state.update { it.copy(addonDraft = it.addonDraft.dropLast(1)) }
    fun setAddonDraft(text: String) = _state.update { it.copy(addonDraft = text.trim().take(160)) }

    fun saveAddon() {
        val draft = _state.value.addonDraft.trim()
        if (draft.length < 4 || !draft.contains('.')) {
            toast("Type a server address, like example.com")
            return
        }
        _state.update { it.copy(addingAddon = false) }
        viewModelScope.launch {
            val result = withTimeoutOrNull(ADDON_ADD_TIMEOUT_MS) { repository.add(draft) }
            val addon = result?.getOrNull()
            if (addon == null) {
                toast(result?.exceptionOrNull()?.message ?: "Couldn't add that server")
                return@launch
            }
            _state.update { it.copy(addons = repository.addons.value) }
            toast("Added ${addon.name}")
            // The new server may carry catalogues, so rebuild the shelves.
            refresh()
        }
    }



    // -- settings ---------------------------------------------------------------------

    fun setReduceMotion(enabled: Boolean) {
        settings.setReduceMotion(enabled)
        _state.update { it.copy(reduceMotion = enabled) }
    }

    // -- downloads ------------------------------------------------------------

    /** Queue the best-ranked source for the open title. */
    fun downloadBest() {
        val detail = _state.value.detail ?: return
        val stream = detail.sources.streams.firstOrNull()
        val url = stream?.url
        if (stream == null || url.isNullOrBlank()) {
            toast(if (detail.resolving) "Still finding sources" else "No source to download")
            return
        }
        val label = if (detail.isSeries) "${detail.title.name}  S${detail.season} E${detail.episode}" else detail.title.name
        val id = app.downloads.start(detail.title.name, label, url, stream.requestHeaders)
        toast(if (id == null) "Already downloading" else "Download started")
    }

    fun pauseDownload(id: String) = app.downloads.pause(id)

    fun resumeDownload(id: String) = app.downloads.resume(id)

    fun removeDownload(id: String) = app.downloads.remove(id)

    /**
     * 720p is the standard; 1080p is the absolute limit. 4K is deliberately
     * not an option: it is four times the bandwidth for no visible gain on a
     * phone or a TV stick, and it is the first thing to stall on a weak link.
     */
    fun cycleQuality() {
        val options = listOf("720p", "1080p")
        val next = options[(options.indexOf(_state.value.preferredQuality).coerceAtLeast(0) + 1) % options.size]
        settings.setPreferredQuality(next)
        _state.update { it.copy(preferredQuality = next) }
        toast(next)
    }

    fun toggleAutoplay() {
        val next = !_state.value.autoplayNext
        settings.setAutoplayNext(next)
        _state.update { it.copy(autoplayNext = next) }
    }

    /** The player's Preload button and the Settings row both land here. */
    fun setPreload(mode: PreloadMode) {
        settings.setPreload(mode)
        _state.update { it.copy(preload = mode) }
    }

    fun cyclePreload() {
        val next = _state.value.preload.next()
        setPreload(next)
        toast(next.label)
    }

    /** The Subtitles panel's style rows land here, from either surface. */
    fun setSubtitleStyle(style: SubtitleStyle) {
        settings.setSubtitleStyle(style)
        _state.update { it.copy(subtitleStyle = style) }
    }

    fun cycleDns() {
        val all = DnsProvider.entries
        val next = all[(_state.value.dns.ordinal + 1) % all.size]
        NovaDns.provider = next
        NovaDns.clearCache()
        settings.setDns(next)
        _state.update { it.copy(dns = next) }
    }

    fun togglePreferEnglishAudio() {
        val next = !_state.value.preferEnglishAudio
        settings.setPreferEnglishAudio(next)
        _state.update { it.copy(preferEnglishAudio = next) }
    }

    // -- server pool ------------------------------------------------------------------

    fun scrambleServers() {
        if (_state.value.poolBusy) return
        _state.update { it.copy(poolBusy = true) }
        viewModelScope.launch {
            val result = runCatching { engine.scramblePool() }
            _state.update { it.copy(poolBusy = false) }
            // A failure and a successful-but-empty run are different things and
            // are worded differently, so "nothing found" is never reported for a
            // request that never completed.
            toast(
                when {
                    result.isFailure -> "Could not reach the server directories"
                    result.getOrDefault(0) > 0 -> "${result.getOrDefault(0)} new servers"
                    // Scramble rotates servers that are already in the pool, so
                    // "new" is the wrong word when nothing was added.
                    else -> "Servers rotated — none were new"
                }
            )
        }
    }

    /**
     * Start filling the 3000-slot pool, or stop it.
     *
     * The button used to run exactly one round and then say "Pool is up to date"
     * whatever the outcome, which is how a pool sitting at 120 servers looked
     * finished. Now it starts the continuous fill (idempotent: the engine
     * cancels the previous job) and the same button stops it, so the viewer has
     * the control the continuous crawler needs.
     */
    fun fillServers() {
        if (_state.value.poolFilling) {
            engine.stopPoolFill()
            _state.update { it.copy(poolFilling = false) }
            toast("Filling stopped at ${engine.pool?.state?.value?.size ?: 0} servers")
            return
        }
        engine.syncPoolFill(_state.value.finders)
        _state.update { it.copy(poolFilling = engine.filling) }
        toast(
            when {
                engine.pool?.state?.value?.full == true -> "Pool is already full (${ServerPool.CAPACITY})"
                else -> "Filling servers — ${engine.pool?.free ?: 0} slots to go"
            }
        )
    }

    fun revertServers() {
        toast(if (engine.revertPool()) "Previous servers restored" else "Nothing to restore")
    }

    fun clearHistory() {
        library.progress.value.forEach { library.removeProgress(it.id) }
        refreshLibraryShelves()
        toast("Watch history cleared")
    }

    fun consumeFocusRestore() = _state.update { it.copy(restoreFocusId = null) }

    private fun toast(message: String) {
        toastJob?.cancel()
        _state.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            _state.update { it.copy(toast = null) }
        }
    }

    private companion object {
        /** How often the Sources screen picks up harvester counters. */
        private const val HARVEST_POLL_MS = 1_000L
        const val SEARCH_DEBOUNCE_MS = 400L
        const val TOAST_MS = 2_600L
        const val INITIAL_SHELVES = 6
        const val MORE_SHELVES = 4
        const val HERO_COUNT = 6
        const val NEXT_WAIT_MS = 20_000L
        /** Adding a website must resolve in this long, or it reports failure. */
        const val WEBSITE_ADD_TIMEOUT_MS = 14_000L
        /** A manifest fetch plus its retry must fit inside this. */
        const val ADDON_ADD_TIMEOUT_MS = 16_000L
        const val KEY_CONTINUE = "continue"
        const val KEY_MY_LIST = "mylist"
        const val KEY_MORE = "more-shelves"
        const val KEY_MORE_DUAL = "more-dual"
        const val DUAL_FIRST = 12
        const val DUAL_MORE = 10
        const val DUAL_PAGES = 6
        val DUAL_GENRES = listOf("Drama", "Action", "Crime", "Thriller", "Romance", "Comedy", "Animation", "Horror", "Mystery", "Fantasy")
    }
}
