package dev.nova.tv.data.repo

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted preferences. Addon URLs are user data, never hard-coded. */
class SettingsStore(private val prefs: SharedPreferences) : CatalogRepository.AddonUrlStore {

    data class Settings(
        val addonUrls: List<String> = emptyList(),
        val autoplayNext: Boolean = true,
        val preferredQuality: String = "auto",
        val subtitleSize: Int = 100,
        val reduceMotion: Boolean = false,
        val volume: Float = 1f,
        val playbackRate: Float = 1f
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        addonUrls = prefs.getString(KEY_ADDONS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
        autoplayNext = prefs.getBoolean(KEY_AUTOPLAY, true),
        preferredQuality = prefs.getString(KEY_QUALITY, "auto") ?: "auto",
        subtitleSize = prefs.getInt(KEY_SUBTITLE, 100),
        reduceMotion = prefs.getBoolean(KEY_MOTION, false),
        volume = prefs.getFloat(KEY_VOLUME, 1f),
        playbackRate = prefs.getFloat(KEY_RATE, 1f)
    )

    override fun savedAddonUrls(): List<String> = _settings.value.addonUrls

    override fun saveAddonUrls(urls: List<String>) = saveAddons(urls)

    fun saveAddons(urls: List<String>) {
        prefs.edit().putString(KEY_ADDONS, urls.distinct().joinToString("\n")).apply()
        _settings.value = _settings.value.copy(addonUrls = urls.distinct())
    }

    fun setAutoplayNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
        _settings.value = _settings.value.copy(autoplayNext = enabled)
    }

    fun setReduceMotion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MOTION, enabled).apply()
        _settings.value = _settings.value.copy(reduceMotion = enabled)
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_VOLUME, clamped).apply()
        _settings.value = _settings.value.copy(volume = clamped)
    }

    fun setPlaybackRate(rate: Float) {
        val clamped = rate.coerceIn(0.25f, 3f)
        prefs.edit().putFloat(KEY_RATE, clamped).apply()
        _settings.value = _settings.value.copy(playbackRate = clamped)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _settings.value = Settings()
    }

    companion object {
        private const val KEY_ADDONS = "addon_urls"
        private const val KEY_AUTOPLAY = "autoplay_next"
        private const val KEY_QUALITY = "preferred_quality"
        private const val KEY_SUBTITLE = "subtitle_size"
        private const val KEY_MOTION = "reduce_motion"
        private const val KEY_VOLUME = "volume"
        private const val KEY_RATE = "playback_rate"
    }
}
