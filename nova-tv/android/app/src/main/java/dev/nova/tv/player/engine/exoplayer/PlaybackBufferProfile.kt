// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original: PlayerScreen.kt, PlaybackBufferProfile and
// buildPlaybackBufferProfile (unchanged apart from the package).
package dev.nova.tv.player.engine.exoplayer

import androidx.compose.runtime.Immutable

@Immutable
internal data class PlaybackBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int,
    val prioritizeTimeOverSizeThresholds: Boolean
)

internal fun buildPlaybackBufferProfile(
    memoryClassMb: Int,
    isLowRamDevice: Boolean,
    isTvDevice: Boolean
): PlaybackBufferProfile {
    val heapMb = memoryClassMb.coerceAtLeast(256)
    val targetMb = when {
        isLowRamDevice || heapMb <= 256 -> 80
        heapMb <= 384 -> 128
        heapMb <= 512 -> 224
        heapMb <= 768 -> 288
        else -> 384
    }
    val minBufferMs = when {
        isLowRamDevice || heapMb <= 256 -> 20_000
        heapMb <= 384 -> 26_000
        heapMb <= 512 -> 34_000
        else -> 40_000
    }
    val maxBufferMs = when {
        isLowRamDevice || heapMb <= 256 -> 70_000
        heapMb <= 384 -> 95_000
        heapMb <= 512 -> 130_000
        else -> 170_000
    }
    val startBufferMs = when {
        isTvDevice && (isLowRamDevice || heapMb <= 384) -> 550
        isTvDevice -> 450
        else -> 350
    }
    val rebufferMs = when {
        isLowRamDevice || heapMb <= 256 -> 4_000
        heapMb <= 384 -> 5_000
        heapMb <= 512 -> 6_500
        else -> 8_000
    }
    val backBufferMs = when {
        isLowRamDevice || heapMb <= 256 -> 2_000
        heapMb <= 384 -> 3_000
        heapMb <= 768 -> 5_000
        else -> 20_000
    }

    return PlaybackBufferProfile(
        minBufferMs = minBufferMs,
        maxBufferMs = maxBufferMs,
        bufferForPlaybackMs = startBufferMs,
        bufferForPlaybackAfterRebufferMs = rebufferMs,
        targetBufferBytes = targetMb * 1024 * 1024,
        backBufferMs = backBufferMs,
        prioritizeTimeOverSizeThresholds = !isLowRamDevice && heapMb > 768
    )
}
