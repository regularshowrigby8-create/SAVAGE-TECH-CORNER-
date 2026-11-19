package dev.nova.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nova.tv.NovaApplication
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.model.NovaAddon
import dev.nova.tv.data.model.Stream
import dev.nova.tv.data.model.SubtitleRef
import dev.nova.tv.data.remote.AddonClient
import dev.nova.tv.data.repo.CatalogRepository
import dev.nova.tv.data.repo.SettingsStore
import dev.nova.tv.finder.FinderCandidate
import dev.nova.tv.finder.FinderReport
import dev.nova.tv.finder.FinderRules
import dev.nova.tv.finder.SourceFinder
import dev.nova.tv.player.ChromeController
import dev.nova.tv.player.HoldReason
import dev.nova.tv.player.PlayerUiState
import dev.nova.tv.ui.nav.BackAction
import dev.nova.tv.ui.nav.NavSnapshot
import dev.nova.tv.ui.nav.resolveBack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The seven top-level destinations, in rail order. */
enum class Route(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SERIES("Series"),
    SEARCH("Search"),
    ADDONS("Addons"),
    FINDER("Source Finder"),
    SETTINGS("Settings")
}

/** One shelf of titles. */
data class HomeRow(val title: String, val items: List<MetaPreview>)

/** Which panel is open over the player. */
enum class PlayerPanel(val label: String) { SOURCES("Sources"), AUDIO("Audio"), SUBTITLES("Subtitles") }

/** Everything one playback needs: the whole ranked list, not one stream. */
data class PlaybackRequest(
    val title: String,
    val streams: List<Stream>,
    val startIndex: Int,
    val subtitles: List<SubtitleRef> = emptyList(),
    /** Bumped on every Play press so the same list can be restarted. */
    val token: Long = System.nanoTime()
)

/** A candidate's progress through the finder's promotion rule. */
sealed interface CandidateStatus {
    data object Untested : CandidateStatus
    data object Testing : CandidateStatus
    data class Verified(val streams: Int, val title: String) : CandidateStatus
    data class NoStreams(val title: String) : CandidateStatus
    data object Installed : CandidateStatus
}

data class FinderUiState(
    val running: Boolean = false,
    val report: FinderReport? = null,
    val enabledSources: Set<String> = FinderRules.SOURCE_IDS.toSet(),
    val status: Map<String, CandidateStatus> = emptyMap(),
    val message: String? = null
) {
    fun statusOf(candidate: FinderCandidate): CandidateStatus =
        status[candidate.manifestUrl] ?: CandidateStatus.Untested
}

/**
 * Everything a screen needs to draw itself.
 */
data class NovaState(
    val route: Route = Route.HOME,
    val loading: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
    /** Which sources failed, by name, for the error line. */
    val errorSources: List<String> = emptyList(),
    val opened: MetaPreview? = null,
    /** Season / episode for series; streams are asked for `tt..:s:e`. */
    val season: Int = 1,
    val episode: Int = 1,
    /** Streams for the opened title; null means "not asked yet". */
    val streams: List<Stream>? = null,
    val streamsLoading: Boolean = false,
    /** Extra streams the source finder verified for this title. */
    val finderStreams: Int = 0,
    val finderSearching: Boolean = false,
    val query: String = "",
    val remoteResults: List<MetaPreview> = emptyList(),
    val searching: Boolean = false,
    val addons: List<NovaAddon> = emptyList(),
    val addonMessage: String? = null,
    val reduceMotion: Boolean = false,
    val autoplayNext: Boolean = true,
    val preferredQuality: String = "auto",
    val subtitleSize: Int = 100,
    val playbackRate: Float = 1f,
    val playback: PlaybackRequest? = null,
    val playerPanel: PlayerPanel? = null,
    /** Id of the title whose card should take focus back after the overlay closes. */
    val restoreFocusId: String? = null,
    val finder: FinderUiState = FinderUiState()
) {
    /** Kept for callers that only need "is something playing". */
    val playing: Stream? get() = playback?.streams?.getOrNull(playback.startIndex)

    /** The row whose titles belong on the given catalogue screen. */
    fun rowFor(route: Route): HomeRow? = when (route) {
        Route.MOVIES -> rows.firstOrNull { it.title.equals("Movies", ignoreCase = true) }
        Route.SERIES -> rows.firstOrNull { it.title.equals("Series", ignoreCase = true) }
        else -> null
    }

    /**
     * Local matches first (instant), then whatever the addons' search
     * catalogues returned, de-duplicated by id.
     */
    fun searchResults(): List<MetaPreview> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val local = rows.flatMap { it.items }.filter { it.name.contains(q, ignoreCase = true) }
        return (local + remoteResults).distinctBy { it.id }
    }

    /** The id streams are requested with. */
    fun streamId(): String? = opened?.let { FinderRules.catalogueId(it.id, it.isSeries, season, episode) }
}

class NovaViewModel : ViewModel() {

    private val app: NovaApplication = NovaApplication.instance
    private val settings = SettingsStore(
        app.getSharedPreferences("nova_settings", android.content.Context.MODE_PRIVATE)
    )
    private val repository: CatalogRepository = CatalogRepository(app.addonClient, settings)
    private val finder = SourceFinder(app.http, app.addonClient)

    private val _state = MutableStateFlow(NovaState())
    val state: StateFlow<NovaState> = _state.asStateFlow()

    /**
     * The player's chrome rule (video.js `computeVisible`), owned here so BACK
     * can ask whether the controls are showing. It used to exist and be read
     * by nothing; PlayerSurface kept a second, private flag.
     */
    val chrome = ChromeController(viewModelScope)
    val chromeState: StateFlow<PlayerUiState> get() = chrome.state
    private var menuHold: (() -> Unit)? = null

    private var streamsJob: Job? = null
    private var searchJob: Job? = null
    private var finderJob: Job? = null

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

    fun navigate(route: Route) {
        val current = _state.value
        if (current.route == route && current.opened == null) return
        streamsJob?.cancel()
        _state.value = current.copy(
            route = route,
            opened = null,
            streams = null,
            streamsLoading = false,
            error = if (route in setOf(Route.HOME, Route.MOVIES, Route.SERIES)) current.error else null,
            addonMessage = null
        )
        if (route in setOf(Route.MOVIES, Route.SERIES) && current.rows.isEmpty() && !current.loading) refresh()
    }

    private fun snapshot(): NavSnapshot {
        val s = _state.value
        return NavSnapshot(
            atHome = s.route == Route.HOME,
            detailOpen = s.opened != null,
            playing = s.playback != null,
            panelOpen = s.playerPanel != null,
            chromeVisible = chrome.state.value.chromeVisible,
            queryNotEmpty = s.route == Route.SEARCH && s.query.isNotEmpty()
        )
    }

    /** BACK: peel exactly one layer. Returns false only when the app should exit. */
    fun back(): Boolean {
        when (resolveBack(snapshot())) {
            BackAction.CLOSE_PLAYER_PANEL -> closePlayerPanel()
            BackAction.SHOW_PLAYER_CHROME -> chrome.onUserActive()
            BackAction.STOP_PLAYBACK -> stopPlayback()
            BackAction.CLOSE_DETAIL -> closeTitle()
            BackAction.CLEAR_QUERY -> setQuery("")
            BackAction.GO_HOME -> navigate(Route.HOME)
            BackAction.EXIT -> return false
        }
        return true
    }

    // -- content ------------------------------------------------------------

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null, errorSources = emptyList())
        viewModelScope.launch {
            try {
                repository.installDefaults()
                val movieRow = repository.loadRow("movie")
                val movieErrors = repository.lastErrors.value
                val seriesRow = repository.loadRow("series")
                val sources = (movieErrors + repository.lastErrors.value).distinct()
                val rows = buildList {
                    if (movieRow.isNotEmpty()) add(HomeRow("Movies", movieRow))
                    if (seriesRow.isNotEmpty()) add(HomeRow("Series", seriesRow))
                }
                _state.value = _state.value.copy(
                    loading = false,
                    rows = rows,
                    error = if (rows.isEmpty() && sources.isEmpty()) "No catalogue answered." else null,
                    errorSources = sources,
                    addons = repository.addons.value
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = _state.value.copy(
                    loading = false,
                    rows = emptyList(),
                    error = error.message ?: "Could not reach any source.",
                    errorSources = repository.lastErrors.value,
                    addons = repository.addons.value
                )
            }
        }
    }

    fun openTitle(title: MetaPreview) {
        _state.value = _state.value.copy(opened = title, season = 1, episode = 1, restoreFocusId = title.id)
        loadStreams()
    }

    /** Series only: move the episode the streams are asked for. */
    fun stepEpisode(seasonDelta: Int, episodeDelta: Int) {
        val s = _state.value
        if (s.opened?.isSeries != true) return
        val season = (s.season + seasonDelta).coerceIn(1, 99)
        val episode = if (seasonDelta != 0) 1 else (s.episode + episodeDelta).coerceIn(1, 999)
        if (season == s.season && episode == s.episode) return
        _state.value = s.copy(season = season, episode = episode)
        loadStreams()
    }

    private fun loadStreams() {
        val s = _state.value
        val title = s.opened ?: return
        val id = s.streamId() ?: return
        val type = if (title.isSeries) "series" else "movie"
        streamsJob?.cancel()
        _state.value = s.copy(streams = null, streamsLoading = true, finderStreams = 0, finderSearching = false)
        streamsJob = viewModelScope.launch {
            val found = runCatching { repository.loadStreams(type, id) }.getOrElse { emptyList() }
            if (_state.value.opened?.id == title.id) {
                _state.value = _state.value.copy(streams = found, streamsLoading = false)
            }
        }
    }

    fun closeTitle() {
        streamsJob?.cancel()
        _state.value = _state.value.copy(opened = null, streams = null, streamsLoading = false, finderSearching = false)
    }

    /**
     * The detail overlay's "Find more sources": run the source finder, ask
     * every candidate for this exact title, and merge what plays into the list.
     * Verified candidates become installable in the Source Finder pane.
     */
    fun findMoreSources() {
        val s = _state.value
        val title = s.opened ?: return
        val id = s.streamId() ?: return
        val type = if (title.isSeries) "series" else "movie"
        if (s.finderSearching) return
        _state.value = s.copy(finderSearching = true)
        viewModelScope.launch {
            val report = _state.value.finder.report ?: runFinderNow()
            val candidates = report?.candidates.orEmpty().filterNot { repository.isInstalled(it.baseUrl) }
            markStatus(candidates, CandidateStatus.Testing)
            val verified = runCatching { finder.verify(candidates, type, id) }.getOrDefault(emptyList())
            val label = title.name
            _state.update { st ->
                st.copy(finder = st.finder.copy(status = st.finder.status + candidates.associate { c ->
                    val hit = verified.firstOrNull { it.candidate.manifestUrl == c.manifestUrl }
                    c.manifestUrl to (if (hit != null) CandidateStatus.Verified(hit.streams.size, label) else CandidateStatus.NoStreams(label))
                }))
            }
            val extra = verified.flatMap { v -> v.streams.map { it.copy(sourceName = v.candidate.name, sourceBase = v.candidate.baseUrl) } }
            if (_state.value.opened?.id == title.id && _state.value.streamId() == id) {
                val merged = AddonClient.rank(_state.value.streams.orEmpty() + extra)
                _state.value = _state.value.copy(
                    streams = merged,
                    finderStreams = merged.size - _state.value.streams.orEmpty().size,
                    finderSearching = false
                )
            } else {
                _state.value = _state.value.copy(finderSearching = false)
            }
        }
    }

    // -- playback -----------------------------------------------------------

    /** Play the ranked list starting at [index]; the player can switch and fail over. */
    fun play(streams: List<Stream>, index: Int) {
        val s = _state.value
        val title = s.opened ?: return
        val playable = streams.filter { it.isPlayable }
        if (playable.isEmpty()) return
        val start = playable.indexOf(streams.getOrNull(index)).coerceAtLeast(0)
        val label = if (title.isSeries) "${title.name} · S${s.season} E${s.episode}" else title.name
        _state.value = s.copy(
            playback = PlaybackRequest(label, playable, start),
            playerPanel = null
        )
        chrome.clear()
        chrome.onUserActive()
        val type = if (title.isSeries) "series" else "movie"
        val id = s.streamId() ?: return
        viewModelScope.launch {
            val subs = runCatching { repository.loadSubtitles(type, id) }.getOrDefault(emptyList())
            _state.update { st ->
                val pb = st.playback ?: return@update st
                st.copy(playback = pb.copy(subtitles = subs))
            }
        }
    }

    /** Compatibility shim for the old one-stream call. */
    fun play(stream: Stream) {
        val list = _state.value.streams.orEmpty()
        val index = list.indexOf(stream)
        if (index >= 0) play(list, index) else play(listOf(stream), 0)
    }

    fun stopPlayback() {
        menuHold?.invoke()
        menuHold = null
        chrome.clear()
        _state.value = _state.value.copy(playback = null, playerPanel = null)
    }

    fun openPlayerPanel(panel: PlayerPanel) {
        menuHold?.invoke()
        menuHold = chrome.hold(HoldReason.MENU)
        _state.value = _state.value.copy(playerPanel = panel)
    }

    fun closePlayerPanel() {
        menuHold?.invoke()
        menuHold = null
        _state.value = _state.value.copy(playerPanel = null)
    }

    // -- search -------------------------------------------------------------

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, remoteResults = if (query.isBlank()) emptyList() else _state.value.remoteResults)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.value = _state.value.copy(searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce: one request per pause, not one per letter.
            delay(SEARCH_DEBOUNCE_MS)
            _state.value = _state.value.copy(searching = true)
            val found = runCatching { repository.search(query) }.getOrDefault(emptyList())
            if (_state.value.query == query) {
                _state.value = _state.value.copy(remoteResults = found, searching = false)
            }
        }
    }

    fun appendQuery(character: Char) = setQuery(_state.value.query + character)

    fun backspaceQuery() = setQuery(_state.value.query.dropLast(1))

    // -- addons -------------------------------------------------------------

    fun removeAddon(baseUrl: String) {
        repository.remove(baseUrl)
        _state.value = _state.value.copy(addons = repository.addons.value, addonMessage = "Removed.")
        // Finder candidates for this base become installable again.
        _state.update { st ->
            st.copy(finder = st.finder.copy(status = st.finder.status.filterValues { it != CandidateStatus.Installed } +
                st.finder.report?.candidates.orEmpty()
                    .filter { it.baseUrl.equals(baseUrl.trimEnd('/'), true) }
                    .associate { it.manifestUrl to CandidateStatus.Untested }))
        }
    }

    // -- source finder ------------------------------------------------------

    fun toggleFinderSource(id: String) {
        _state.update { st ->
            val set = st.finder.enabledSources
            val next = if (id in set) set - id else set + id
            st.copy(finder = st.finder.copy(enabledSources = next.ifEmpty { set }))
        }
    }

    fun runFinder() {
        if (_state.value.finder.running) return
        finderJob = viewModelScope.launch { runFinderNow() }
    }

    private suspend fun runFinderNow(): FinderReport? {
        _state.update { it.copy(finder = it.finder.copy(running = true, message = null)) }
        val report = runCatching {
            finder.find(
                sourceIds = _state.value.finder.enabledSources.toList(),
                exclude = repository.addons.value.map { it.baseUrl }.toSet()
            )
        }.getOrNull()
        _state.update { st ->
            st.copy(
                finder = st.finder.copy(
                    running = false,
                    report = report ?: st.finder.report,
                    message = when {
                        report == null -> "The finder could not run. Check the connection."
                        report.candidates.isEmpty() -> "No new stream addons found in the selected directories."
                        else -> "${report.candidates.size} candidate source(s). Test one to add it."
                    }
                )
            )
        }
        return report
    }

    /** The title candidates are tested against: what the viewer opened, or the top film. */
    private fun probeTitle(): Pair<String, MetaPreview>? {
        val s = _state.value
        s.opened?.let { t -> return (if (t.isSeries) "series" else "movie") to t }
        val film = s.rowFor(Route.MOVIES)?.items?.firstOrNull { it.id.startsWith("tt") } ?: return null
        return "movie" to film
    }

    /**
     * Test candidates against a real title and install the ones that return a
     * playable stream. This is the core.js promotion rule: discovery alone
     * never installs anything.
     */
    fun testCandidates(candidates: List<FinderCandidate>) {
        val (type, title) = probeTitle() ?: run {
            _state.update { it.copy(finder = it.finder.copy(message = "Load the catalogue first; candidates are tested against a real title.")) }
            return
        }
        val id = FinderRules.catalogueId(title.id, type == "series")
        val pending = candidates.filter { _state.value.finder.statusOf(it) != CandidateStatus.Installed }
        if (pending.isEmpty()) return
        markStatus(pending, CandidateStatus.Testing)
        viewModelScope.launch {
            val verified = runCatching { finder.verify(pending, type, id) }.getOrDefault(emptyList())
            var installed = 0
            for (candidate in pending) {
                val hit = verified.firstOrNull { it.candidate.manifestUrl == candidate.manifestUrl }
                val status: CandidateStatus = if (hit == null) {
                    CandidateStatus.NoStreams(title.name)
                } else {
                    val added = repository.add(candidate.baseUrl).isSuccess
                    if (added) installed++
                    if (added) CandidateStatus.Installed else CandidateStatus.Verified(hit.streams.size, title.name)
                }
                markStatus(listOf(candidate), status)
            }
            _state.update {
                it.copy(
                    addons = repository.addons.value,
                    finder = it.finder.copy(
                        message = if (installed > 0) "Added $installed source(s) that play \"${title.name}\"."
                        else "No candidate returned a playable stream for \"${title.name}\"."
                    )
                )
            }
        }
    }

    private fun markStatus(candidates: List<FinderCandidate>, status: CandidateStatus) {
        _state.update { st ->
            st.copy(finder = st.finder.copy(status = st.finder.status + candidates.associate { it.manifestUrl to status }))
        }
    }

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

    fun consumeFocusRestore() {
        _state.value = _state.value.copy(restoreFocusId = null)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
    }
}
