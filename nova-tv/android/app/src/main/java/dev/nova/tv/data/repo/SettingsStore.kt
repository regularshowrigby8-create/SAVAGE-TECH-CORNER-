package dev.nova.tv.data.repo

import android.content.SharedPreferences
import dev.nova.tv.data.remote.DnsProvider
import dev.nova.tv.finder.FinderKind
import dev.nova.tv.player.preload.PreloadMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted preferences. Addon URLs are user data, never hard-coded. */
class SettingsStore(private val prefs: SharedPreferences) : CatalogRepository.AddonUrlStore {

    data class Settings(
        val addonUrls: List<String> = emptyList(),
        val autoplayNext: Boolean = true,
        /** Standard play starts at 720p: less buffering than 1080p or 4K. */
        val preferredQuality: String = "720p",
        val subtitleSize: Int = 100,
        val reduceMotion: Boolean = false,
        val volume: Float = 1f,
        val playbackRate: Float = 1f,
        val finders: Set<FinderKind> = FinderKind.entries.toSet(),
        /** FastStream-style pre-download; switched from the player. */
        val preload: PreloadMode = PreloadMode.OFF,
        val dns: DnsProvider = DnsProvider.AUTO,
        /** Dual Audio picks: prefer English audio and dual/multi audio files. */
        val preferEnglishAudio: Boolean = true
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        addonUrls = prefs.getString(KEY_ADDONS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
        autoplayNext = prefs.getBoolean(KEY_AUTOPLAY, true),
        preferredQuality = prefs.getString(KEY_QUALITY, "720p") ?: "720p",
        subtitleSize = prefs.getInt(KEY_SUBTITLE, 100),
        reduceMotion = prefs.getBoolean(KEY_MOTION, false),
        volume = prefs.getFloat(KEY_VOLUME, 1f),
        playbackRate = prefs.getFloat(KEY_RATE, 1f),
        finders = FinderKind.entries.filter { prefs.getBoolean(KEY_FINDER + it.name, true) }.toSet(),
        preload = PreloadMode.entries.firstOrNull { it.name == prefs.getString(KEY_PRELOAD, null) } ?: PreloadMode.OFF,
        dns = DnsProvider.entries.firstOrNull { it.name == prefs.getString(KEY_DNS, null) } ?: DnsProvider.AUTO,
        preferEnglishAudio = prefs.getBoolean(KEY_ENGLISH, true)
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

    fun setPreferredQuality(quality: String) {
        val normalised = quality.ifBlank { "720p" }
        prefs.edit().putString(KEY_QUALITY, normalised).apply()
        _settings.value = _settings.value.copy(preferredQuality = normalised)
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

    fun setFinders(kinds: Set<FinderKind>) {
        val edit = prefs.edit()
        FinderKind.entries.forEach { edit.putBoolean(KEY_FINDER + it.name, it in kinds) }
        edit.apply()
        _settings.value = _settings.value.copy(finders = kinds)
    }

    fun setPreload(mode: PreloadMode) {
        prefs.edit().putString(KEY_PRELOAD, mode.name).apply()
        _settings.value = _settings.value.copy(preload = mode)
    }

    fun setDns(provider: DnsProvider) {
        prefs.edit().putString(KEY_DNS, provider.name).apply()
        _settings.value = _settings.value.copy(dns = provider)
    }

    fun setPreferEnglishAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENGLISH, enabled).apply()
        _settings.value = _settings.value.copy(preferEnglishAudio = enabled)
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
        private const val KEY_FINDER = "finder_"
        private const val KEY_PRELOAD = "preload_mode"
        private const val KEY_DNS = "dns_provider"
        private const val KEY_ENGLISH = "prefer_english_audio"
    }
}
