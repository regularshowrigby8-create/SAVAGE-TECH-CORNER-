// The ExoPlayer.Builder configuration is ARVIO's (PlayerScreen.kt,
// https://github.com/ProdigyV21/ARVIO commit 31ce7d7, Apache License 2.0):
// load control from the buffer profile, hardware-first renderers with decoder
// fallback and synchronous codec queueing, permissive track selection, and
// movie audio attributes with audio focus. The data source is OkHttp, as in
// ARVIO, so addon request headers actually reach the server.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.nova.tv.player.engine.exoplayer

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One player plus the handles Nova needs to recover it. */
class NovaExoPlayerBundle internal constructor(
    val player: ExoPlayer,
    val renderers: NovaRenderersFactory,
    val dataSource: OkHttpDataSource.Factory,
    private val mediaSources: DefaultMediaSourceFactory
) {
    /**
     * Build the source for one stream with its own retry policy. ARVIO fails
     * fast on 400/404/410 only for progressive files and manifests; a single
     * missing HLS segment must not end the stream.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun mediaSource(item: androidx.media3.common.MediaItem, progressive: Boolean): androidx.media3.exoplayer.source.MediaSource =
        mediaSources
            .setLoadErrorHandlingPolicy(VodLoadErrorHandlingPolicy(progressive))
            .createMediaSource(item)

    /** Per-stream headers (Referer, User-Agent...) for the next prepare. */
    fun setRequestHeaders(headers: Map<String, String>) {
        dataSource.setDefaultRequestProperties(headers)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NovaExoPlayerFactory {

    private const val NOVA_START_BUFFER_MS = 1_200

    fun create(
        context: Context,
        baseClient: OkHttpClient,
        userAgent: String,
        preferredAudioLanguage: String? = null,
        /** bits/s measured by StreamProbe; 0 lets ExoPlayer start from its own table. */
        initialBitrateEstimate: Long = 0L,
        /** Nova preload cache: the player reads through it, the preloader fills it. */
        cache: Cache? = null
    ): NovaExoPlayerBundle {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val profile = buildPlaybackBufferProfile(
            memoryClassMb = activityManager?.memoryClass ?: 256,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            isTvDevice = context.packageManager.hasSystemFeature("android.software.leanback")
        )

        // Debrid/CDN files are large and slow to first byte; ARVIO uses a
        // 30 s read timeout for VOD rather than the 20 s addon-call default.
        val playbackClient = baseClient.newBuilder()
            .readTimeout(VOD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        val http = OkHttpDataSource.Factory(playbackClient).setUserAgent(userAgent)
        val direct = DefaultDataSource.Factory(context, http)
        // Reads come from disk when the preloader got there first; a cache
        // error never fails playback, it falls through to the network.
        val dataSourceFactory = if (cache == null) direct else CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(direct)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        // Nova anti-buffering: ARVIO starts after 450 ms of media, which is
        // quick but means the first network dip is a visible stall. 1.2 s is
        // still well under a second of extra wait on a healthy source.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                maxOf(profile.bufferForPlaybackMs, NOVA_START_BUFFER_MS),
                profile.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(profile.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(profile.prioritizeTimeOverSizeThresholds)
            .setBackBuffer(profile.backBufferMs, profile.prioritizeTimeOverSizeThresholds)
            .build()

        val renderers = NovaRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            forceDisableMediaCodecAsynchronousQueueing()
            setEnableDecoderFallback(true)
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(
                    preferredAudioLanguage?.takeUnless { it.isBlank() || it.equals("none", ignoreCase = true) }
                )
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setForceLowestBitrate(false)
                // TV: ARVIO keeps video strict only on touch devices.
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        }

        // Seed adaptive selection with what the probe measured, so HLS starts
        // on a variant this connection can actually sustain.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).apply {
            if (initialBitrateEstimate > 0) setInitialBitrateEstimate(initialBitrateEstimate)
        }.build()

        val player = ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { volume = 1.0f }

        return NovaExoPlayerBundle(player, renderers, http, mediaSourceFactory)
    }
}
