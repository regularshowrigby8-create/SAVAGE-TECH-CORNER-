# nova-tv vs ZeroPlay — what "100% better" has to mean

**Measured 2026-11-18 against `tv/zeroplay-debrid` and `tv/android-tv` at `88fe9fe`.**
Numbers are from the tree, not from memory.

## 1. They are not the same kind of program

| | ZeroPlay | nova-tv |
|---|---|---|
| Android shell | **1 Java file, 753 LOC**, a `WebView` | 17 Kotlin files, 1394 LOC, native |
| App | 44 web files, **23,830 LOC** of JS/HTML | Compose + Media3 |
| Playback | browser `<video>` inside a WebView | **ExoPlayer** (Media3), native |
| Compilation | Gradle, green in CI | Gradle, green in CI (run 44336856359) |

ZeroPlay is a web app in a WebView. nova-tv is a native Android app. That is the
one sense in which it is already "completely different" — and it is real.

## 2. Where nova-tv is currently WORSE

This is the part that matters, and it is not close.

| Capability | ZeroPlay | nova-tv today |
|---|---|---|
| Source resolver | 3 defined sources, 4 torrent / 8 addon references | **none** |
| Resume / continue watching | `resume` ×53, `progress` ×101, `continueWatching` ×3 | **none** |
| Subtitles | present | **none** |
| Debrid | not found in the resolver | **none** |
| Downloads | HLS/DASH downloader with resume, CORS handling, 5 GB cap | **none** |
| Addon depth | Stremio catalogs, `idPrefixes`, torrent→HTTPS | manifest parsing only |
| Can actually play something | yes | **no** — tapping a poster sets `opened` and nothing renders it |
| Wiring | everything connected | `NovaPlayer`, `SettingsStore` are **dead code** |

By any functional measure, **ZeroPlay is currently far ahead.** It was built over
a long session with the resolver, player and tests; nova-tv is a wireframe that
compiles. Claiming otherwise would be false.

## 3. Where nova-tv is structurally better

These are architectural facts, not opinions:

- **Native playback.** ExoPlayer decodes in-process. ZeroPlay hands video to the
  WebView's `<video>`, which on the target device — Xiaomi TV Box S, 32-bit
  Android 14, 2 GB RAM, per ZeroPlay's own README §146 — is the heavier path.
  ZeroPlay's README documents `trim-memory` signals to survive there.
- **No origin restrictions.** A WebView is bound by CORS and mixed-content rules
  on every request. ZeroPlay's own limitations section lists CORS-blocked
  downloads, IndexedDB quota, and CORS-gated remote hosts. A native `OkHttp`
  client has no such layer.
- **WebView is not a media stack.** ZeroPlay's README concedes it cannot control
  codecs, quality, or DRM inside cross-origin embeds. ExoPlayer owns all of it.
- **Resource cost.** A WebView plus a 23,830-LOC JavaScript app on 2 GB of RAM is
  a fundamentally heavier starting point than Compose + ExoPlayer.

So: **better architecture, much worse completeness.** Both halves are true and
the second one is the one that has to be fixed.

## 4. What "100% better" must therefore mean — measurable, not a slogan

Targets, each checkable:

1. **Every ZeroPlay capability exists in nova-tv.** Resolver, resume, subtitles,
   downloads, addon depth. Parity first; it cannot be "better" while missing
   things.
2. **Native playback path for all of it.** Nothing routes through a WebView.
3. **Runs on the same 2 GB ARMv7 TV box.** Same device, measurable memory.
4. **Keeps ZeroPlay's provenance discipline.** `PLAYER-SOURCES.md` records the
   repo, file and rule for every borrowed mechanism — nova-tv already has the
   equivalent in `docs/BUILD-STATUS.md` and `nova/importers.py`.
5. **Differs in structure, not just in language.** No file, module or naming
   carried over. Already true: ZeroPlay is `com.savage.zeroplay.tv`, this is
   `dev.nova.tv`; one 753-line shell vs 17 focused Kotlin files.

## 5. The honest scoreboard

| | ZeroPlay | nova-tv | Winner |
|---|---|---|---|
| Compiles | yes | yes | tie |
| Compiles natively | no | yes | **nova-tv** |
| Can play a video | yes | **no** | **ZeroPlay** |
| Resolves sources | yes (3) | no | **ZeroPlay** |
| Resumes playback | yes | no | **ZeroPlay** |
| Peak memory on 2 GB box | measured, tuned | unmeasured | **unknown** |

**Two wins, four losses, one tie.** That is the real position, and it is the
baseline this phase has to beat. Any claim of "100% better" before parity is a
claim without evidence.

## 6. Consequence for the plan

`APP-COMPONENT-PLAN.md` phase ordering was about imports and licensing. This
document adds a hard gate: **nova-tv is not better until the resolver, resume
and player wiring exist and one title plays on a real device.** The import
sources are known; the gap is now measured rather than assumed.
