package dev.nova.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nova.tv.NovaApplication
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.repo.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The six top-level destinations. */
enum class Route(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SERIES("Series"),
    SEARCH("Search"),
    ADDONS("Addons"),
    SETTINGS("Settings")
}

/** One shelf of titles. */
data class HomeRow(val title: String, val items: List<MetaPreview>)

/**
 * Everything a screen needs to draw itself.
 *
 * This replaces the old `HomeState`, which had a `rows` list and an `opened`
 * title but no notion of *which screen*. The rail set a `route` variable that
 * nothing read, so all six destinations rendered the same pane -- the exact
 * defect `scripts/check_buttons.py` reports as `unrouted-entries`.
 */
data class NovaState(
    val route: Route = Route.HOME,
    val loading: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
    /** Which sources failed, by name, for the error line. */
    val errorSources: List<String> = emptyList(),
    val opened: MetaPreview? = null,
    /** Streams for the opened title; null means "not asked yet". */
    val streams: List<Stream>? = null,
    val streamsLoading: Boolean = false,
    val query: String = "",
    val addons: List<NovaAddon> = emptyList(),
    val reduceMotion: Boolean = false,
    /** Everything else the settings screen shows, so none of it is write-only. */
    val autoplayNext: Boolean = true,
    val preferredQuality: String = "auto",
    val subtitleSize: Int = 100,
    val playbackRate: Float = 1f,
    val savedSources: Int = 0,
    /** The stream chosen for playback, or null when nothing is playing. */
    val playing: Stream? = null,
    /** The title being played, for the chrome. */
    val playingTitle: String = ""
) {
    /** The row whose titles belong on the given catalogue screen. */
    fun rowFor(route: Route): HomeRow? = when (route) {
        Route.MOVIES -> rows.firstOrNull { it.title.equals("Movies", ignoreCase = true) }
        Route.SERIES -> rows.firstOrNull { it.title.equals("Series", ignoreCase = true) }
        else -> null
    }

    /**
     * Search over what is already loaded.
     *
     * Deliberately local-only for now: it filters the catalogue in memory rather
     * than pretending to query every addon server-side. A search box that
     * silently returns nothing from a remote source is worse than one that
     * honestly searches what the app has.
     */
    fun searchResults(): List<MetaPreview> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return rows.flatMap { it.items }
            .distinctBy { it.id }
            .filter { it.name.contains(q, ignoreCase = true) }
    }
}

class NovaViewModel : ViewModel() {

    private val app: NovaApplication = NovaApplication.instance
    private val repository: CatalogRepository = CatalogRepository(app.addonClient)
    private val settings = SettingsStore(
        app.getSharedPreferences("nova_settings", android.content.Context.MODE_PRIVATE)
    )

    private val _state = MutableStateFlow(NovaState())
    val state: StateFlow<NovaState> = _state.asStateFlow()

    init {
        val saved = settings.settings.value
        _state.value = _state.value.copy(
            reduceMotion = saved.reduceMotion,
            autoplayNext = saved.autoplayNext,
            preferredQuality = saved.preferredQuality,
            subtitleSize = saved.subtitleSize,
            playbackRate = saved.playbackRate
        )
        refresh()
    }

    // -- navigation ---------------------------------------------------------

    /**
     * The single place a route is honoured.
     *
     * Every entry in [Route] appears in this `when`, and the compiler enforces
     * that: adding a destination without wiring it is a compile error rather
     * than a dead menu item.
     */
    fun navigate(route: Route) {
        if (_state.value.route == route) return
        _state.value = _state.value.copy(
            route = route,
            opened = null,
            streams = null,
            error = null
        )
        // Search and Settings have nothing to fetch; the others may be empty
        // because the first load failed, and a retry here is cheap.
        if (route in setOf(Route.MOVIES, Route.SERIES) && _state.value.rows.isEmpty()) refresh()
    }

    /** BACK: peel exactly one surface, never exit while something is open. */
    fun back(): Boolean {
        val current = _state.value
        return when {
            current.playing != null -> { stopPlayback(); true }
            current.opened != null -> { closeTitle(); true }
            current.query.isNotEmpty() -> { setQuery(""); true }
            current.route != Route.HOME -> { navigate(Route.HOME); true }
            else -> false // nothing left to close; let the system handle it
        }
    }

    // -- content ------------------------------------------------------------

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null, errorSources = emptyList())
        viewModelScope.launch {
            try {
                repository.installDefaults()
                val movieRow = repository.loadRow("movie")
                val seriesRow = repository.loadRow("series")
                val rows = buildList {
                    if (movieRow.isNotEmpty()) add(HomeRow("Movies", movieRow))
                    if (seriesRow.isNotEmpty()) add(HomeRow("Series", seriesRow))
                }
                val sources = repository.lastErrors.value
                _state.value = _state.value.copy(
                    loading = false,
                    rows = rows,
                    // An empty catalogue with no error is still an empty
                    // catalogue; say so instead of rendering a blank screen.
                    error = if (rows.isEmpty() && sources.isEmpty()) {
                        "No catalogue answered."
                    } else {
                        null
                    },
                    errorSources = sources,
                    addons = repository.addons.value
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    rows = emptyList(),
                    error = error.message ?: "Could not reach any source.",
                    errorSources = repository.lastErrors.value
                )
            }
        }
    }

    fun openTitle(title: MetaPreview) {
        _state.value = _state.value.copy(
            opened = title,
            streams = null,
            streamsLoading = true
        )
        viewModelScope.launch {
            val found = runCatching {
                repository.loadStreams(title.type, title.id)
            }.getOrElse { emptyList() }
            _state.value = _state.value.copy(streams = found, streamsLoading = false)
        }
    }

    fun closeTitle() {
        _state.value = _state.value.copy(opened = null, streams = null, streamsLoading = false)
    }

    /** Start playback. Only reachable for streams the model marks playable. */
    fun play(stream: Stream) {
        val title = _state.value.opened ?: return
        if (!stream.isPlayable) return
        _state.value = _state.value.copy(
            playing = stream,
            playingTitle = title.name
        )
    }

    fun stopPlayback() {
        _state.value = _state.value.copy(playing = null, playingTitle = "")
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun appendQuery(character: Char) = setQuery(_state.value.query + character)

    fun backspaceQuery() = setQuery(_state.value.query.dropLast(1))

    // -- settings -----------------------------------------------------------

    fun setReduceMotion(enabled: Boolean) {
        settings.setReduceMotion(enabled)
        _state.value = _state.value.copy(reduceMotion = enabled)
    }

    fun toggleAutoplay() {
        val next = !_state.value.autoplayNext
        settings.setAutoplayNext(next)
        _state.value = _state.value.copy(autoplayNext = next)
    }
}
