package dev.nova.tv.data.repo

import android.content.SharedPreferences
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.finder.website.SavedWebsite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What a Continue Watching card needs, and where to resume. */
@Serializable
data class WatchProgress(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Within the last 5 % (credits) counts as finished. */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95

    fun toMeta() = MetaPreview(id = id, type = type, name = name, poster = poster, background = background)
}

@Serializable
data class SavedTitle(val id: String, val type: String, val name: String, val poster: String? = null, val background: String? = null) {
    fun toMeta() = MetaPreview(id = id, type = type, name = name, poster = poster, background = background)
}

/** The source family that last played a series, so the next episode starts there. */
@Serializable
data class LastSource(val titleId: String, val bingeGroup: String? = null, val sourceBase: String = "")

@Serializable
private data class WebsiteRecord(val url: String, val name: String, val templates: List<String> = emptyList())

/**
 * ZeroPlay's library features on the device: Continue Watching, My List, the
 * last good source per series, and the user's websites. One JSON value per
 * list in SharedPreferences; nothing here needs an account.
 */
class LibraryStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _progress = MutableStateFlow(read(KEY_PROGRESS, WatchProgress.serializer()))
    val progress: StateFlow<List<WatchProgress>> = _progress.asStateFlow()

    private val _myList = MutableStateFlow(read(KEY_LIST, SavedTitle.serializer()))
    val myList: StateFlow<List<SavedTitle>> = _myList.asStateFlow()

    private val _websites = MutableStateFlow(
        read(KEY_SITES, WebsiteRecord.serializer()).map { SavedWebsite(it.url, it.name, it.templates) }
    )
    val websites: StateFlow<List<SavedWebsite>> = _websites.asStateFlow()

    private val lastSources = read(KEY_LAST, LastSource.serializer()).associateBy { it.titleId }.toMutableMap()

    // -- continue watching -------------------------------------------------

    fun continueWatching(): List<WatchProgress> =
        _progress.value.filter { !it.finished && it.positionMs > MIN_POSITION_MS }.sortedByDescending { it.updatedAt }

    fun progressFor(id: String, season: Int = 0, episode: Int = 0): WatchProgress? =
        _progress.value.firstOrNull { it.id == id && it.season == season && it.episode == episode }

    fun saveProgress(p: WatchProgress) {
        if (p.positionMs < MIN_POSITION_MS && !p.finished) return
        // One entry per title: a series shows its latest episode.
        val next = listOf(p.copy(updatedAt = System.currentTimeMillis())) + _progress.value.filterNot { it.id == p.id }
        _progress.value = next.take(MAX_PROGRESS)
        write(KEY_PROGRESS, WatchProgress.serializer(), _progress.value)
    }

    fun removeProgress(id: String) {
        _progress.value = _progress.value.filterNot { it.id == id }
        write(KEY_PROGRESS, WatchProgress.serializer(), _progress.value)
    }

    // -- my list -------------------------------------------------------------

    fun inList(id: String) = _myList.value.any { it.id == id }

    fun toggleList(meta: MetaPreview): Boolean {
        val present = inList(meta.id)
        _myList.value = if (present) _myList.value.filterNot { it.id == meta.id }
        else listOf(SavedTitle(meta.id, meta.type, meta.name, meta.poster, meta.background)) + _myList.value
        write(KEY_LIST, SavedTitle.serializer(), _myList.value)
        return !present
    }

    // -- last source -----------------------------------------------------------

    fun lastSource(titleId: String): LastSource? = lastSources[titleId.substringBefore(':')]

    fun saveLastSource(titleId: String, bingeGroup: String?, sourceBase: String) {
        val key = titleId.substringBefore(':')
        lastSources[key] = LastSource(key, bingeGroup, sourceBase)
        write(KEY_LAST, LastSource.serializer(), lastSources.values.toList().takeLast(MAX_PROGRESS))
    }

    // -- websites --------------------------------------------------------------

    fun addWebsite(site: SavedWebsite) {
        _websites.value = _websites.value.filterNot { sameHost(it.url, site.url) } + site
        persistSites()
    }

    fun removeWebsite(url: String) {
        _websites.value = _websites.value.filterNot { it.url == url }
        persistSites()
    }

    private fun persistSites() =
        write(KEY_SITES, WebsiteRecord.serializer(), _websites.value.map { WebsiteRecord(it.url, it.name, it.templates) })

    private fun sameHost(a: String, b: String) = runCatching {
        java.net.URI(a).host.removePrefix("www.").equals(java.net.URI(b).host.removePrefix("www."), true)
    }.getOrDefault(false)

    fun clear() {
        prefs.edit().remove(KEY_PROGRESS).remove(KEY_LIST).remove(KEY_LAST).remove(KEY_SITES).apply()
        _progress.value = emptyList(); _myList.value = emptyList(); _websites.value = emptyList(); lastSources.clear()
    }

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull() }.orEmpty()

    private fun <T> write(key: String, serializer: kotlinx.serialization.KSerializer<T>, value: List<T>) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), value)).apply()
    }

    companion object {
        private const val KEY_PROGRESS = "library_progress"
        private const val KEY_LIST = "library_my_list"
        private const val KEY_LAST = "library_last_source"
        private const val KEY_SITES = "library_websites"
        private const val MAX_PROGRESS = 60
        private const val MIN_POSITION_MS = 30_000L
    }
}
