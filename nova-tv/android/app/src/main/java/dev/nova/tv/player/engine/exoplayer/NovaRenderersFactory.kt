// Adapted from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original:
//   app/src/main/kotlin/com/arflix/tv/ui/screens/player/engine/exoplayer/AiSubtitleRenderersFactory.kt
// Nova keeps ARVIO's audio/video renderer ordering and the AudioDecoderFallback
// wiring, and drops the AI subtitle translation, cue offset and audio-capture
// renderers, which depend on ARVIO services Nova does not ship.
package dev.nova.tv.player.engine.exoplayer

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NovaRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    private val audioDecoderFallback = AudioDecoderFallback()

    /** Retry a crashed decoder only when another renderer can handle the failing track. */
    fun blockAudioDecoder(decoderName: String, format: Format?): Boolean =
        audioDecoderFallback.blockIfSupported(decoderName, format)

    fun blockAudioPassthrough(format: Format?): Boolean =
        audioDecoderFallback.blockPassthroughIfSupported(format)

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // ARVIO: audio is always built with mode ON so the platform renderer
        // (with AVR passthrough) goes first and FFmpeg is strictly a fallback.
        val firstNewIndex = out.size
        super.buildAudioRenderers(
            context, EXTENSION_RENDERER_MODE_ON, audioDecoderFallback.wrapSelector(mediaCodecSelector),
            enableDecoderFallback, audioSink, eventHandler, eventListener, out
        )
        audioDecoderFallback.softwareCapabilities = out.subList(firstNewIndex, out.size)
            .filterNot { it is MediaCodecAudioRenderer }
            .map { it.capabilities }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // ARVIO: video is hardware-first on every device; software 4K decode
        // cannot keep up on TV boxes. Decoder fallback handles real failures.
        super.buildVideoRenderers(
            context, EXTENSION_RENDERER_MODE_ON, mediaCodecSelector,
            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return audioDecoderFallback.wrapSink(sink)
    }
}
