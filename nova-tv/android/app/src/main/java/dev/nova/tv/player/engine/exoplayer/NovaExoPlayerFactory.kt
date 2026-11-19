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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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

    fun create(
        context: Context,
        baseClient: OkHttpClient,
        userAgent: String,
        preferredAudioLanguage: String? = null
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
        val dataSourceFactory = DefaultDataSource.Factory(context, http)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.bufferForPlaybackMs,
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

        val player = ExoPlayer.Builder(context)
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
