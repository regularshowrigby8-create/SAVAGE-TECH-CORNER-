// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),
// Apache License 2.0. Original path:
//   app/src/main/kotlin/com/arflix/tv/ui/screens/player/engine/PlayerEngineType.kt
// Changes for Nova are listed in nova-tv/android/THIRD_PARTY_NOTICES.md.
package dev.nova.tv.player.engine

enum class PlayerEngineType(val displayName: String) {
    EXOPLAYER("ExoPlayer"),
    MPV("MPV"),
    VLC("VLC");

    companion object {
        fun fromString(name: String?): PlayerEngineType {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: EXOPLAYER
        }
    }
}
