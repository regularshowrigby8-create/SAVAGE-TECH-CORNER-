# Third-party notices

## ARVIO player engine (Apache License 2.0)

Nova's playback engine is imported from ARVIO,
https://github.com/ProdigyV21/ARVIO at commit `31ce7d7`, licensed under the
Apache License 2.0 (full text: `licenses/ARVIO-LICENSE-Apache-2.0.txt`).
Every imported file keeps a header naming its original path.

| Nova file (`app/src/main/java/dev/nova/tv/player/engine/`) | ARVIO original (`app/src/main/kotlin/com/arflix/tv/ui/screens/player/`) |
|---|---|
| `PlayerEngine.kt` | `engine/PlayerEngine.kt` |
| `PlayerEngineModels.kt` | `engine/PlayerEngineModels.kt` |
| `PlayerEngineType.kt` | `engine/PlayerEngineType.kt` |
| `PlayerEngineFactory.kt` | `engine/PlayerEngineFactory.kt` |
| `SourceFailoverPolicy.kt` | `SourceFailoverPolicy.kt` |
| `exoplayer/ExoPlayerEngine.kt` | `engine/exoplayer/ExoPlayerEngine.kt` |
| `exoplayer/VodLoadErrorHandlingPolicy.kt` | `engine/exoplayer/VodLoadErrorHandlingPolicy.kt` |
| `exoplayer/AudioDecoderFallback.kt` | `engine/exoplayer/AudioDecoderFallback.kt` |
| `exoplayer/SurroundAudioRecovery.kt` | `engine/exoplayer/SurroundAudioRecovery.kt` |
| `exoplayer/NovaRenderersFactory.kt` | adapted from `engine/exoplayer/AiSubtitleRenderersFactory.kt` |
| `exoplayer/PlaybackBufferProfile.kt`, `exoplayer/NovaExoPlayerFactory.kt` | adapted from the ExoPlayer builder in `PlayerScreen.kt` |

Changes made for Nova (Apache 2.0 section 4(b)):

- Package renamed from `com.arflix.tv...` to `dev.nova.tv.player.engine...`.
- `SourceFailoverPolicy`: works on Nova's `Stream` instead of ARVIO's
  `StreamSource`; adds `nextFailoverIndex`, which walks the ranked source list
  and skips tried sources and mirrors of the failed URL.
- `VodLoadErrorHandlingPolicy`: the IPTV / live-channel branch is removed
  (Nova has no live TV).
- `NovaRenderersFactory`: keeps ARVIO's renderer order and audio decoder
  fallback; drops the AI subtitle translation, cue offset and audio-capture
  renderers, which need ARVIO services Nova does not ship.
- `ExoPlayerEngine`: publishes the selected audio / subtitle track and whether
  a subtitle track is external, so Nova's Audio and Subtitles panels can mark
  the current choice.
- `NovaExoPlayerFactory`: OkHttp data source sharing Nova's guarded client,
  with per-stream request headers from the addon (`proxyHeaders`).

## FFmpeg audio decoder

`org.jellyfin.media3:media3-ffmpeg-decoder` (Jellyfin's build of the Media3
FFmpeg extension, the same artifact ARVIO uses) is a binary dependency. The
Media3 extension is Apache 2.0; the bundled FFmpeg libraries are LGPL-2.1 or
later. Check the Jellyfin project's notices before distributing builds
outside personal use.

## ZeroPlay source finder (this repository)

`app/src/main/java/dev/nova/tv/finder/` is a Kotlin port of ZeroPlay's
`tv/zeroplay-debrid/lib/server-finder/core.js` (directory search and manifest
rules) and `tv/zeroplay-debrid/lib/global-resolver/sources.js` (stream id and
playability rules), both from this repository. The rules are kept; the one
deliberate change is that streams with `proxyHeaders` are accepted, because
ExoPlayer can send request headers and a browser `<video>` cannot.

## ARVIO additions in v2 (Apache-2.0)

From https://github.com/ProdigyV21/ARVIO at commit 31ce7d7, Apache License 2.0:

- `ui/focus/ArvioDpadFocus.kt`: D-pad focus helpers, ported; the deprecated
  `BringIntoViewResponder` part was dropped.
- `ui/components/Shimmer.kt`: adapted from ARVIO's `SkeletonLoader`.
- `FinderRules.parseSizeBytes`: adapted from ARVIO's `parseSizeToBytes`.
- `player/StreamProbe.kt` `HostHealth` and connection warm-up: the idea follows
  ARVIO's host health tracking and warm connections; the code is new.
- The Up Next card follows ARVIO's `NextEpisodeOverlay` concept; the code is new.

`ui/focus/PivotScroll.kt` uses the pivot formula from the Android TV
documentation for Compose `BringIntoViewSpec`
(https://developer.android.com/training/tv/playback/compose).
- `data/remote/NovaDns.kt`: DNS-over-HTTPS resolver after ARVIO's
  `OkHttpProvider` (pinned bootstrap IPs, system fallback, IPv4 first); the
  provider list and TTL cache are new.
- `ui/focus/NovaDpad.kt`: held-key gating at ARVIO's 80 ms / 112 ms intervals
  (`HomeScreen.kt`, `DetailsScreen.kt`, `SettingsScreen.kt`).

## FastStream (mechanics only, no code)

The Preload button follows the behaviour of FastStream
(https://github.com/Andrews54757/FastStream, commit 4c5cb57). FastStream's
licence reserves all rights and requires permission to reuse its code, so no
FastStream code was copied. Only the publicly described mechanics were
reimplemented from scratch in `player/preload/`: fixed-size byte-range
fragments, a window kept a few minutes ahead of the playhead, up to six
parallel downloaders, and an adaptive speed test that adds a downloader only
while throughput keeps rising.

## Player buffering work (mechanics, no code copied)

The additions to `player/` and `player/preload/` follow published behaviour
from players that solved the same problems. No source was copied from any of
them; the implementations are new Kotlin on Media3:

- **mpv** `--cache-secs` / **VLC** network caching: a lookahead depth that
  grows with the measured link headroom (`FragmentPlan.depthMs`), and a
  sequential whole-file cache for hosts without byte ranges
  (`FragmentPreloader.startSequential`).
- **FastStream**: fragment sizing and the connection governor (see above);
  fragment shrinking after repeated range failures is Nova's addition.
- **AndroidX Media3** `DefaultLoadControl`, `CacheWriter`, `CacheDataSource`,
  `CaptionStyleCompat` are used as published, Apache License 2.0.
- **ExoPlayer seek behaviour**: `SeekParameters.CLOSEST_SYNC` for VOD seeks,
  which is the setting Android's own media samples use for instant skipping.
- Caption presets (Cinema / Outline / Minimal / High contrast) follow the
  BBC Subtitle Guidelines and iPlayer's outline style, implemented with
  `CaptionStyleCompat`.
