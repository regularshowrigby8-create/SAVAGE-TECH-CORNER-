# Reference sources

Every claim this project makes about another app's UI must point at one of
these. `tools/anti_hallucination.py` fails the build on an uncited claim, and
a citation is only accepted if it is a URL, a source file, a backticked symbol
or a document in this repository.

The checkouts below were read directly; nothing here is recalled from memory.

## ARVIO (Apache-2.0) — the TV player and navigation shape

- `PlayerEngine.kt` — play/pause/toggle/seekTo/seekBy/setPlaybackSpeed/
  setResizeMode/cycleResizeMode/selectAudioTrack/selectSubtitleTrack/
  setAudioDelayMs/setVolume/release. The browser engine implements this same
  surface.
- `PlayerEngineModels.kt` — `ResizeMode` AUTO/FIT/STRETCH/CROP and
  `PlayerPlaybackState` IDLE/BUFFERING/READY/ENDED.
- `ArvioTvPlayer.kt` — the TV player screen and section focus.
- `TvPlayerControls.kt` — chrome show/hide and the ~200ms `fadeOut` exit.
- `TvPlayerPanels.kt` — `TvSubtitleSettingsPanel` (delay/size/position) and the
  subtitle/audio tabbed panel; key handling that runs before the transport.
- `NextEpisodeOverlay.kt` — the Up Next card that owns the remote while open.
- `PlayerSourceDiscovery.kt` — `mergePlayerSourceDiscovery`, 30s timeout,
  sequential stream candidates.
- `StreamRepository.kt` / `SourceFailoverPolicy.kt` — `PlaybackRequestIdentity`,
  `canTryFailoverSource`, `safePlaybackHeaders`.

## NuvioTV

- `core/player/StreamAutoPlaySelector.kt` — `orderAddonStreams`,
  `selectBingeGroupMatch`, `selectAutoPlayStream`.
- `ui/screens/stream/StreamProviderGrouping.kt` — `groupStreamsByProvider`.
- `ui/screens/stream/StreamScreenViewModel.kt` — incremental result filling.
- `domain/model/Stream.kt` — one stream record carrying url, infoHash,
  fileIdx, externalUrl, proxyHeaders, addonName, quality, debrid cache state.

## Stremio

- `getActiveScope` (modal > dropdown > content) — the focus scope order.
- The Cinemeta catalogue addon, `https://v3-cinemeta.strem.io`, keyless and
  CORS-open, used as a fallback catalogue.

## Observed, not sourced

Where a figure comes from watching an app rather than reading it, it says so at
the point of use. Only one such figure exists: the poster focus scale in Stremio,
0.95 → 1, artwork only, recorded in the scale table comment in
`tv/zeroplay-debrid/index.html`. Treat it as an observation, not a spec.
