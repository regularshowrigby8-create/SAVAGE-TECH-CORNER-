# ZeroPlay TV for Android TV

ZeroPlay TV packages the ZeroPlay web experience as a remote-first Android TV app for constrained TV boxes. The first target is the **Xiaomi TV Box S (3rd Gen), MDZ-32-AA / MiTV-AFMU0 (“twilight”)**.

## Target-device decisions

The Xiaomi box has 2 GB RAM and a 64-bit-capable Amlogic S905X5M, but its Google TV/Android 14 userspace is 32-bit. The release is therefore explicitly built as an **`armeabi-v7a`** split APK with no universal APK. The native shell contains no bundled native libraries of its own; the APK verifier still rejects any future `lib/` entries outside `armeabi-v7a`.

The app is designed around the patterns that work on Android TV:

- D-pad first, with predictable Back/Menu/Search and media transport keys.
- A large Continue Watching row before the featured hero when something can be resumed.
- Big poster rows and image-led categories instead of dense text/filter screens.
- A tiny quick-settings list. Detailed source, playback and device counters stay behind **Advanced & diagnostics**.
- Source readiness shown in words a viewer can act on: **Ready**, **Searching**, **Found sources**, or **Problem**.
- Right-sized artwork, reduced decorative work, and low-memory behaviour for a 2 GB TV box.

## Artwork with no setup

Posters and backdrops are filled automatically. The user never creates an account or pastes an artwork API key. ZeroPlay tries, in order:

1. artwork already supplied by the catalogue;
2. keyless **Cinemeta / MetaHub** by IMDb ID;
3. keyless **TVmaze** series and episode artwork;
4. public **AniList** anime artwork;
5. keyless **Apple iTunes Search** artwork as a final fallback.

Candidates must match the title/year (or an exact IMDb route) and the returned image must actually load before it replaces a placeholder. Results are cached. On Android TV, poster renditions are constrained (`w342` TMDB paths and MetaHub `medium`) to avoid wasting memory on images far larger than a card.

Optional deployment-side TMDB artwork is different: if the host operator privately configures the existing server proxy, its artwork is simply catalogue artwork. There is no in-app TMDB key field.

## Remote behaviour

The native shell exposes a small read-only profile bridge and forwards remote keys to the web app:

- **Back** closes the current player/menu/dialog first. It exits only from Home when nothing is open.
- **Menu/Guide** opens or closes the TV menu.
- **Search** focuses search.
- **Play/Pause, Play, Pause, Fast-forward, Rewind, Next, Previous, Stop** drive the web player when it can control the active media.
- **Channel Up/Down** map to next/previous where the player supports it.
- **Left at the browsing edge** opens the menu.
- Android trim-memory callbacks ask the web app to stop previews and shed nonessential image work.

Cross-origin embedded players still control some of their own internal behaviour; the shell does not claim to reach inside them.

## Build from source

Requirements:

- JDK 17
- Android SDK Platform 34 and Build Tools
- Internet access for the Gradle/Android plugin downloads

```bash
cd tv/android-tv
chmod +x gradlew
./gradlew clean :app:assembleRelease --no-daemon
```

Gradle writes the split under:

```text
app/build/outputs/apk/release/
```

The GitHub workflow copies and names the deliverable:

```text
ZeroPlay-TV-1.0.0-armeabi-v7a.apk
```

## Verify an APK

```bash
python3 tv/android-tv/tools/verify_apk.py \
  tv/android-tv/dist/ZeroPlay-TV-1.0.0-armeabi-v7a.apk \
  --require-sdk-tools \
  --json tv/android-tv/dist/ZeroPlay-TV-1.0.0-armeabi-v7a.verify.json
```

The verifier checks:

- package `com.savage.zeroplay.tv`, version, min/target SDK;
- Leanback launcher and Leanback feature declarations;
- `.MainActivity` and the 320×180 TV banner;
- launcher PNGs and the bundled offline page;
- DEX magic, Adler-32 checksum and SHA-1 signature;
- stored-entry alignment plus `zipalign` when available;
- `apksigner` v2/v3 signature verification;
- native-library ABIs (only `armeabi-v7a` is allowed);
- Internet/network-state permissions;
- SHA-256 and APK size.

## Download on the TV (Downloader)

The verified APK is published as a GitHub Release asset with a direct,
no-login download URL for the **Downloader** app on the TV:

```text
https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/releases/download/zeroplay-tv-v1.0.1/zeroplay-tv.apk
```

1. Open **Downloader** on the Xiaomi TV Box and type the URL above into the URL box, then press **Go**.
2. When the download finishes, choose **Install**. If Google TV asks, allow Downloader to install unknown apps (Settings → Apps → Security & restrictions → Unknown sources → Downloader).
3. Open **ZeroPlay TV** from the TV home screen. Choose **Delete** in Downloader afterwards to remove the downloaded APK file if you want the space back.

The release is published by CI only after the full verification gate passes
(package, Leanback, ARMv7 ABI, DEX and signature). The `.sha256` and
`.verify.json` assets on the release page carry the checksum and the full
verification report for the exact APK.

## Sideloading notes

This sideload build uses Android's debug certificate so no private release key is committed to the repository. Future updates must keep the same package name and signing certificate, or Android will require uninstalling the previous copy first. A production store release should move to a privately held release key before its first public distribution.

Install only an APK whose SHA-256 matches the value published with the corresponding GitHub Actions artifact. Exact “unknown apps” prompts vary by Google TV build and file-manager app.

## Architecture

The native layer is intentionally thin and dependency-light:

- `MainActivity.java` — Leanback activity, WebView configuration, fullscreen media, file chooser, direct HTTP downloads, profile bridge, lifecycle/low-memory handling and remote keys.
- `assets/offline.html` — local retry screen used when the hosted app cannot be reached.
- `../zeroplay-debrid/index.html` — catalogue, artwork resolution, identity-checked source lookup, playback, downloads, M3U extraction and Settings.
- `tools/verify_apk.py` — TV-specific release gate.

The shell loads the production web app with `?androidtv=1`, so web fixes can ship without rebuilding the APK. The APK still needs a rebuild when native key handling, the bridge, launcher assets, permissions or WebView policy changes.

## Honest limits

- Android TV WebView/Chromium version and codec support vary by device update.
- Some third-party sites block WebView, Range requests, CORS requests, or downloads.
- DRM, SAMPLE-AES, DASH downloads, live downloads and separate-audio HLS muxing are outside the existing web download engine.
- Browser/WebView storage is finite; it is not an unlimited provider registry or download disk.
- The shell does not magically make a third-party stream legal, available, or fast.
