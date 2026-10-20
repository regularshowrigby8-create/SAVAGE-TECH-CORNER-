<p align="center"><img src="docs/screenshots/2.2/zeroplay-logo.svg" alt="ZeroPlay — white Zero and mint-gradient Play" width="100%"></p>

# 🎬 ZeroPlay 2.2.2

### Movies. Series. Anime. Live TV. Sports. Your own space to watch.

ZeroPlay brings TMDB discovery, a local library, multiple playback sources and customizable interfaces to **Android phones/tablets, Android TV / Google TV, Windows and Web**. Version **2.2.2** keeps the polished 2.2.1 experience and refreshes playback sources: VidStuck now uses `vidstuck.cc`, ZXCStream (Beta) is available, and Settings controls the default source for all videos.

**[⬇️ Get the latest release](https://github.com/Zer0Spce/ZeroPlay/releases/latest)** · **[📋 2.2.2 release notes](docs/release-2.2.2.md)** · **[📺 TV controls](docs/android-tv-controls.md)** · **[🖼️ Genuine screenshot gallery](#-real-app-screenshots)** · **[🔧 Fix history](#-fixes-and-improvements)**

> [!IMPORTANT]
> **Windows 2.0–2.0.3 users: update manually once.** Download the current Windows ZIP, extract it into a fresh folder and launch `ZeroPlay.exe`. Those old versions contain the broken updater. Versions 2.1 and newer include the repaired in-app update flow.

## ⬇️ Choose your edition

| Edition | Download | What you get |
| --- | --- | --- |
| 📱 Android | [Full Android APK](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.2/ZeroPlay-2.2.2-Android.apk) | Full phone/tablet presentation and native player core. |
| 📺 Android TV / Google TV | [Full TV APK](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.2/ZeroPlay-2.2.2-Android-TV.apk) | Remote-friendly browsing, Hybrid / D-pad / Mouse player navigation. |
| 🪶 Android Lite | [Lite Android APK](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.2/ZeroPlay-2.2.2-Android-Lite.apk) | Less artwork and presentation work for slower devices. |
| 🪶📺 Android TV Lite | [Lite TV APK](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.2/ZeroPlay-2.2.2-Android-TV-Lite.apk) | Lightweight presentation with the same 2.2 Hybrid/player core. |
| 🖥️ Windows 10 / 11 x64 | [Portable Windows ZIP](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.2/ZeroPlay-2.2.2-Windows-x64.zip) | Extract and run `ZeroPlay.exe`; no installer. |
| ✅ Checksums | [SHA256SUMS.txt](https://github.com/Zer0Spce/ZeroPlay/releases/download/v2.2.1/SHA256SUMS.txt) | Verify the published files. |

Android requires Android 6.0 or newer. Full and Lite apps have separate package IDs and can be installed independently. Signed production updates retain the established signing identity for each package.

## ⭐ What’s new in 2.2.2

**VidStuck → vidstuck.cc · NEW ZXCStream (Beta) · Restored global default playback source · VidStuck remains the default · Existing 2.2.1 player behavior preserved**

> **Top 10 Today** is enabled by default on Android, Android TV and Windows and can be disabled from Homepage panels / Home customization. **Coming Soon is hidden by default.**

[![Actual released Windows 2.2 homepage](docs/screenshots/2.2/windows-home.png)](docs/screenshots/2.2/windows-home.png)

## ✨ Current feature inventory

### 🔎 Find your next watch

- **TMDB-powered catalog:** movies, TV series and anime, metadata and artwork without rebuilding the app for every new title.
- **Discovery shelves:** trending, popular, recommendations, now playing, upcoming movies and series worth watching.
- **Explicit search:** submit with Search / Enter; typing does not issue a catalog search on every letter.
- **People search and filmography:** actor/person discovery, biographies and combined movie/TV credits using TMDB identities.
- **Infinite browsing:** load subsequent pages in place, retain earlier results and deduplicate titles instead of using Next/Back page buttons.
- **Artwork categories:** movie and TV genres, Anime, Japanese, Korean, Filipino, French, Spanish and Hindi collections.
- **Catalog filters:** genre, year/date, rating, language, runtime and country where exposed by the active interface.
- **Sorting:** featured, popular, top rated, newest, oldest and alphabetical orders where available.
- **Surprise Me:** discover a released random movie beyond the currently visible homepage; Surprise Me Again on surprise-opened titles.
- **Because You Watched:** recommendations informed by local viewing history.
- **Hidden Gems:** well-rated, lower-popularity discovery that deliberately leans away from the newest mainstream releases and rotates over time.
- **Tonight’s Picks:** a rotating quality-focused mix for watching now rather than another newest-release shelf.
- **Mood discovery:** funny, scary, action, romance, family, mind-bending, highly rated and shorter watches.
- **TV discovery:** airing today, recent/returning shows and upcoming episode-calendar helpers where exposed.
- **Franchise/collection discovery:** connected movie collections and related titles when TMDB supplies those relationships.
- **Streaming-provider discovery:** region-aware provider availability from TMDB / JustWatch; availability is separate from embed playback.

### 🎞️ Title pages and personal library

- Posters/backdrops, synopsis, genres, release information, rating/vote information, cast, trailers, recommendations and provider information.
- Clickable cast and filmography using the selected person’s exact TMDB ID.
- Optional **Rotten Tomatoes** ratings through OMDb when configured.
- **Watchlist**, **Plan to Watch**, custom collections, watch history and saved search history.
- **Continue Watching** registers when playback starts and retains season/episode context; reopening a series restores the last season/episode you actually played.
- **Up Next** using saved series progress; Windows deduplicates shows by TMDB series ID.
- Episode watched / in-progress indicators, season and episode selection and next-episode calculation.
- **Autoplay next episode** setting and episode utility-navigation support.
- Configurable intro/recap durations; these are user timings, not guaranteed exact provider timestamps.
- **Local profiles:** independent history, progress, watchlist, planned titles, searches, collections and Not Interested preferences without a cloud account.
- **Not Interested:** hide or down-rank unwanted recommendations locally.
- Quick title actions where supported: play, details, watchlist, mark watched, collection and Not Interested.
- Dedicated watched-history clearing that preserves watchlist, downloads and settings.
- Reset application/settings action that retains downloaded media files.

### ▶️ Playback and TV remote controls

- **VidStuck**, **Embedwave (BETA)** and **VidSrc.sh** in native apps; optional **RawCast** when enabled and configured. Web offers VidStuck and Embedwave.
- Title-level source selection, saved per-title choices and global preferences.
- Initial source priority: **one-time title choice → saved title choice → Settings choice → fallback**.
- Source-health tracking with optional **Automatic Source Recovery**, **OFF by default**.
- Movie/series embeds keep their **provider-native controls**; the experimental permanent ZeroPlay video toolbar was removed.
- Fullscreen and staged Back handling, subject to the provider’s exposed controls.
- **Android TV Hybrid / Smart mode:** focus navigation when reliable controls are detected, virtual-mouse fallback when required.
- **Fixed D-pad mode:** arrows navigate detected controls and OK activates the focused control; navigation does not intentionally map Left/Right to seeking.
- **Fixed Mouse mode:** arrows move the pointer and OK clicks.
- Hidden-control wake handling, spatial focus, iframe/Shadow DOM support and provider menu detection.
- Menu opens supported playback utilities; holding Menu/OK does not silently switch the source.
- Playback exit safeguards and Android app exit confirmation; ordinary Windows window closing exits immediately.
- Player diagnostics for provider, navigation and recovery troubleshooting where supported.
- Windows mini-player infrastructure and keyboard playback shortcuts; support varies with the playback surface.
- Compatible embedded audio boost: **Off / 1.5× / 2×**.

### 📡 Native live entertainment

| Section | Features |
| --- | --- |
| **Live PPV / PPV-Sports** | Separate live-event playlist, groups/logos, submitted channel search, manual refresh and in-app playback. |
| **Live TV** | IPTV groups, logos, favorites and refresh. Cignal and Converge options can be controlled separately; Windows defaults both off. |
| **Live Sports** | Event categories, schedules, artwork, alternate streams, manual refresh, retry and fullscreen; memory-only Windows sports browsing session. |
| **Live players** | Platform-appropriate playback controls, channel switching, retry/live-edge handling, audio/subtitles and fit/fill where supported; HLS/DASH and playlist request-header/ClearKey support depend on stream/device. |

**Live TV, PPV and Live Sports are native-app features. They remain intentionally absent from the website.** Playlist/event refresh behavior and playback availability depend on the upstream source.

### 🎨 Make ZeroPlay yours

- **Four layouts:** Clean, Modern, Flix and Native / Original.
- Fresh full-app defaults: **Windows → Clean**, **Android phone/tablet → Modern**, **Android TV → Native / Original**. Saved choices are retained.
- **17 themes:** Zero Dark, Zero Light, Ocean, Orchid, Sunset, Midnight Blue, Ember Glow, Forest Moss, Rose Noir, Amethyst, Cyber Mint, Cobalt Sky, Golden Hour, Coral Night, Aurora, Slate Ice and Mocha.
- Theme-aware branding, light/dark choices, artwork atmosphere and optional animations.
- Homepage panel toggles, saved panel ordering and category shelves, including an optional **Top 10 Today** rail; **Coming Soon is off by default**.
- Carousel sources: **This Week Top Picks, Watchlist, Recommended Movies, Popular Movies, Now Playing, Continue Watching**.
- Carousel capped at **10 cards**, with **15-second** rotation and transitions respecting animation settings.
- Focus information, configurable preview delay, focus trailers and homepage trailers linked to the displayed title; official TMDB title-logo artwork is used in the hero when available.
- Compact navigation/sidebar behavior, responsive card presentation and visible TV focus states.

### ⚡ Performance and Lite editions

- Persistent TMDB catalog caching; **Windows memory + disk** cache and Android persistent cache helpers.
- Bounded cache age/size and stale-cache fallback when catalog requests temporarily fail.
- Fewer repeated requests, better section-state retention and less unnecessary page regeneration.
- Lazy artwork, adaptive quality choices and reduced work for offscreen images.
- **Performance Mode** reduces presentation/preview overhead on slower hardware.
- Reduced unrelated homepage/background work during playback.
- **Lite defaults:** Classic/Original lightweight layout, Performance Mode on, saver artwork, smaller artwork requests, fewer homepage cards, no rotating hero or backdrop/blur atmosphere, animations and focus/home trailers off.
- Lite shares the current catalog/player core: TV Lite includes Hybrid Mode, Embedwave and the current source-selection fixes.

### 📥 Downloads and offline playback — native apps

- Configurable **TSP Search**, a separate provider key, result/quality/health filters and download confirmation.
- Download queue, progress, active-download limits, delete controls and completed-download history for media you are authorized to download.
- Completed local media can be opened in an installed external player.
- Local subtitles: **SRT / VTT / ASS / SSA** support varies by platform/player; optional automatic-subtitle configuration is available in native settings.
- Windows bundles an **mpv-based offline engine** and native video surface with hardware decoding where available.
- Windows offline controls include play/pause, seeking, volume/mute, playback speed, fit/fill, fullscreen, embedded audio/subtitle tracks and external subtitle selection.
- The release preserves the established Windows offline-player/updater stack while integrating the 2.2 discovery/player changes.

### 🛡️ Adblock 2.0 and updates

- Known ad/tracker-host blocking, including **HiStats** rules.
- Native player network filtering, denied popup/popunder windows and restricted external navigation.
- Positively decoded QR-ad host learning, exact-host session quarantine and learned-host expiry after **seven days**.
- Provider/video-CDN allowlists and local diagnostic logs to reduce playback breakage.
- QR recognition does not open its destination or interact with verification controls.
- In-app version/changelog/update flows for Windows, Android and Android TV.
- Windows **`ZeroPlayUpdater.exe`** helper with checksum verification, versioned installation folders, atomic activation, independent relaunch, pending-version confirmation and rollback handling.
- Android updater selects the matching signed package and hands installation to Android.

### 🌐 Website-specific features

- Movies/Series discovery, filters, profiles/person discovery, recommendations and episode-calendar helpers.
- VidStuck and **Embedwave (BETA)** source selection.
- Continuous pagination with retry, deduplication, end-of-results handling and improved tab/scroll restoration.
- Single-bar website navigation plus muted hero trailer autoplay previews with an audio toggle and artwork fallback.
- Shared/deep movie and series links close cleanly instead of immediately reopening their detail dialog.
- Single-bar website navigation plus muted hero trailer autoplay previews with an audio toggle and artwork fallback.
- Shared/deep movie and series links close cleanly instead of immediately reopening their detail dialog.
- Single-bar website navigation plus muted hero trailer autoplay previews with an audio toggle and artwork fallback.
- Shared/deep movie and series links close cleanly instead of immediately reopening their detail dialog.
- Shareable title/catalog/search routes and browser Back/Forward support.
- Rich metadata, movie franchise rows, browser-local library and homepage section toggles.
- Hover quick actions and keyboard navigation: `/` focuses Search; arrow keys navigate cards; `W` toggles the focused title’s watchlist state.
- Installable PWA app shell, lazy-image improvements, bounded caching and smarter next-page loading.
- Browser restrictions mean native QR inspection, TV mouse, native offline playback and audio boost do not run inside a third-party web iframe.

Features with provider events, local media or interface-specific controls depend on the active platform/provider. A feature helper or setting does not guarantee every third-party player exposes the required events.

## 🖼️ Real app screenshots

**These are screenshots of the actual 2.2 app, not AI images or recreated interfaces.** Windows captures come from the checksum-verified published executable. Android/mobile and TV captures come from the native 2.2 application on emulators. The logo above is a branding asset, not a screenshot. [Capture provenance](docs/screenshots/2.2/README.md).

### 🖥️ Windows — four actual layouts

**Clean homepage**

[![Clean homepage](docs/screenshots/2.2/windows-home.png)](docs/screenshots/2.2/windows-home.png)


<details>
<summary>Modern layout</summary>

[![Modern layout](docs/screenshots/2.2/windows-modern.png)](docs/screenshots/2.2/windows-modern.png)

</details>

<details>
<summary>Flix layout</summary>

[![Flix layout](docs/screenshots/2.2/windows-flix.png)](docs/screenshots/2.2/windows-flix.png)

</details>

<details>
<summary>Native / Original layout</summary>

[![Native / Original layout](docs/screenshots/2.2/windows-original.png)](docs/screenshots/2.2/windows-original.png)

</details>

### 🔎 Discovery, categories and title controls

**Movie discovery and filters**

[![Movie discovery and filters](docs/screenshots/2.2/windows-movies-filters.png)](docs/screenshots/2.2/windows-movies-filters.png)


<details>
<summary>Series discovery and filters</summary>

[![Series discovery and filters](docs/screenshots/2.2/windows-series-filters.png)](docs/screenshots/2.2/windows-series-filters.png)

</details>

<details>
<summary>Artwork categories</summary>

[![Artwork categories](docs/screenshots/2.2/windows-categories.png)](docs/screenshots/2.2/windows-categories.png)

</details>

**Real title details**

[![Real title details](docs/screenshots/2.2/windows-title-details.png)](docs/screenshots/2.2/windows-title-details.png)


**Title-level playback source picker**

[![Title-level playback source picker](docs/screenshots/2.2/windows-sources.png)](docs/screenshots/2.2/windows-sources.png)


### ⚙️ Profiles, performance and customization

**Local profiles, Performance Mode and episode preferences**

[![Local profiles, Performance Mode and episode preferences](docs/screenshots/2.2/windows-profiles-performance.png)](docs/screenshots/2.2/windows-profiles-performance.png)


**Homepage panels and ordering**

[![Homepage panels and ordering](docs/screenshots/2.2/windows-home-panels.png)](docs/screenshots/2.2/windows-home-panels.png)


<details>
<summary>Carousel-source settings</summary>

[![Carousel-source settings](docs/screenshots/2.2/windows-carousel.png)](docs/screenshots/2.2/windows-carousel.png)

</details>

<details>
<summary>Playback preferences</summary>

[![Playback preferences](docs/screenshots/2.2/windows-playback-settings.png)](docs/screenshots/2.2/windows-playback-settings.png)

</details>

<details>
<summary>Theme selection</summary>

[![Theme selection](docs/screenshots/2.2/windows-themes.png)](docs/screenshots/2.2/windows-themes.png)

</details>

<details>
<summary>Settings overview</summary>

[![Settings overview](docs/screenshots/2.2/windows-settings.png)](docs/screenshots/2.2/windows-settings.png)

</details>

### 📡 Live sections and your library

<details>
<summary>Live PPV playlist</summary>

[![Live PPV playlist](docs/screenshots/2.2/windows-live-ppv.png)](docs/screenshots/2.2/windows-live-ppv.png)

</details>

<details>
<summary>Live TV channel browser</summary>

[![Live TV channel browser](docs/screenshots/2.2/windows-live-tv.png)](docs/screenshots/2.2/windows-live-tv.png)

</details>

**Live Sports events**

[![Live Sports events](docs/screenshots/2.2/windows-live-sports.png)](docs/screenshots/2.2/windows-live-sports.png)


<details>
<summary>Fresh-profile local library</summary>

[![Fresh-profile local library](docs/screenshots/2.2/windows-library.png)](docs/screenshots/2.2/windows-library.png)

</details>

<details>
<summary>Downloads view before provider configuration</summary>

[![Downloads view before provider configuration](docs/screenshots/2.2/windows-downloads.png)](docs/screenshots/2.2/windows-downloads.png)

</details>

### 📱 Android phone / tablet

<img src="docs/screenshots/2.2/android-mobile-home.png" alt="Actual Android 2.2 homepage" width="360">

<details>
<summary>Homepage</summary>

<img src="docs/screenshots/2.2/android-mobile-home.png" alt="ZeroPlay 2.2 mobile — Homepage" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-home.png)

</details>

<details>
<summary>Categories</summary>

<img src="docs/screenshots/2.2/android-mobile-categories.png" alt="ZeroPlay 2.2 mobile — Categories" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-categories.png)

</details>

<details>
<summary>Live Sports</summary>

<img src="docs/screenshots/2.2/android-mobile-live-sports.png" alt="ZeroPlay 2.2 mobile — Live Sports" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-live-sports.png)

</details>

<details>
<summary>Native settings</summary>

<img src="docs/screenshots/2.2/android-mobile-settings.png" alt="ZeroPlay 2.2 mobile — Native settings" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-settings.png)

</details>

<details>
<summary>Homepage customization</summary>

<img src="docs/screenshots/2.2/android-mobile-home-panels.png" alt="ZeroPlay 2.2 mobile — Homepage customization" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-home-panels.png)

</details>

<details>
<summary>Modern layout</summary>

<img src="docs/screenshots/2.2/android-mobile-layout-google.png" alt="ZeroPlay 2.2 mobile — Modern layout" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-layout-google.png)

</details>

<details>
<summary>Native / Original layout</summary>

<img src="docs/screenshots/2.2/android-mobile-layout-classic.png" alt="ZeroPlay 2.2 mobile — Native / Original layout" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-layout-classic.png)

</details>

<details>
<summary>Clean layout</summary>

<img src="docs/screenshots/2.2/android-mobile-layout-youtube.png" alt="ZeroPlay 2.2 mobile — Clean layout" width="420">

[Open full-resolution capture](docs/screenshots/2.2/android-mobile-layout-youtube.png)

</details>

### 📺 Android TV / Google TV

[![Actual native TV homepage in landscape](docs/screenshots/2.2/android-tv-home.png)](docs/screenshots/2.2/android-tv-home.png)

**Hybrid player controls**

[![Actual TV Hybrid navigation settings](docs/screenshots/2.2/android-tv-hybrid-controls.png)](docs/screenshots/2.2/android-tv-hybrid-controls.png)

**Local profiles and mood discovery**

| Local profiles | What should I watch? |
| --- | --- |
| ![Actual TV profiles](docs/screenshots/2.2/android-tv-profiles.png) | ![Actual TV mood picker](docs/screenshots/2.2/android-tv-moods.png) |

<details>
<summary>Homepage</summary>

<img src="docs/screenshots/2.2/android-tv-home.png" alt="ZeroPlay 2.2 tv — Homepage" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-home.png)

</details>

<details>
<summary>Categories</summary>

<img src="docs/screenshots/2.2/android-tv-categories.png" alt="ZeroPlay 2.2 tv — Categories" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-categories.png)

</details>

<details>
<summary>Live Sports</summary>

<img src="docs/screenshots/2.2/android-tv-live-sports.png" alt="ZeroPlay 2.2 tv — Live Sports" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-live-sports.png)

</details>

<details>
<summary>Native settings</summary>

<img src="docs/screenshots/2.2/android-tv-settings.png" alt="ZeroPlay 2.2 tv — Native settings" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-settings.png)

</details>

<details>
<summary>Homepage customization</summary>

<img src="docs/screenshots/2.2/android-tv-home-panels.png" alt="ZeroPlay 2.2 tv — Homepage customization" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-home-panels.png)

</details>

<details>
<summary>Modern layout</summary>

<img src="docs/screenshots/2.2/android-tv-layout-google.png" alt="ZeroPlay 2.2 tv — Modern layout" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-layout-google.png)

</details>

<details>
<summary>Native / Original layout</summary>

<img src="docs/screenshots/2.2/android-tv-layout-classic.png" alt="ZeroPlay 2.2 tv — Native / Original layout" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-layout-classic.png)

</details>

<details>
<summary>Clean layout</summary>

<img src="docs/screenshots/2.2/android-tv-layout-youtube.png" alt="ZeroPlay 2.2 tv — Clean layout" width="960">

[Open full-resolution capture](docs/screenshots/2.2/android-tv-layout-youtube.png)

</details>

## 🔧 Fixes and improvements

The lists below distinguish the **current 2.2 changes** from earlier fixes that built the current experience. Earlier release notes are historical records; a removed experimental feature is not a current feature. [Full release-by-release archive](docs/changelog.md).

### 2.2.1 — Home, resume and player polish

1. Added **Top 10 Today** on Android, Android TV and Windows, with regional labeling, real portrait posters and large rank numerals.
2. Added horizontal Top 10 navigation/scrolling and a **Homepage panels** toggle so the shelf can be disabled.
3. Hid **Coming Soon** by default while retaining it as an opt-in panel.
4. Added official TMDB title-logo artwork to featured carousels when available, with plain-title fallback.
5. Added muted website hero trailer autoplay with sound toggle and fallback artwork.
6. Fixed website deep/shared title links reopening immediately after the detail dialog was closed.
7. Fixed website trailer metadata requests being rejected by the API allowlist.
8. Improved Continue Watching so playback start is recorded immediately instead of waiting on provider progress callbacks.
9. Restored the last played **season and episode** when reopening a series on Android, Android TV, Windows and Web.
10. Reworked **Tonight’s Picks** and **Hidden Gems** so they no longer behave like duplicate newest-movie shelves.
11. Prioritized Android/TV Top 10 poster loading and reduced poster request size for a much faster Home appearance.
12. Fixed Android TV rank drawing, clipping, `10` sizing and poster overlap; ranks are now solid, bold and condensed in front of the poster edge.
13. Fixed Windows Top 10 cards not opening details.
14. Removed Top 10 poster letterboxing and preserved proper vertical artwork presentation.
15. Made the Android/TV carousel **Watch now** focus treatment consistent with the ZeroPlay accent UI.
16. Added a provider-safe close fallback for the VidSrc subtitle/options overlay on Android/TV and Windows.
17. Preserved the existing 2.2 source-selection, Hybrid controls, updater, ad-filtering, Lite and offline-playback foundations.

[Full 2.2.1 release notes](docs/release-2.2.1.md).

### 2.2 — navigation, discovery, personalization and performance

1. Fixed manual playback sources being silently replaced by Embedwave; explicit per-title/global preferences now win.
2. Restored VidSrc.sh to Android/TV’s visible source picker after Embedwave insertion shifted its index.
3. Set Automatic Source Recovery OFF by default for fresh and migrated settings.
4. Removed the experimental movie/series toolbar; restored provider-native controls while retaining specialized Sports controls.
5. Added Embedwave to trusted-provider/network allowlists without relaxing general popup/navigation protection.
6. Retained the production Windows player network filter and HiStats protection in the 2.2 player stack.
7. Improved TV spatial focus, hidden-control wake-up, iframe fallback and menu detection through Hybrid navigation.
8. Kept staged Back handling and prevented Menu/Center holds from silently changing the provider.
9. Normalized/deduplicated Windows homepage panel order to prevent duplicate Up Next sections.
10. Deduplicated Windows Up Next titles by series ID and newest progress record.
11. Improved Continue Watching persistence, season/episode context, next-episode calculation and completion handling.
12. Reduced repeated catalog requests and unnecessary regeneration; added bounded persistent cache and stale fallback.
13. Improved artwork loading/quality choices and reduced unrelated work during playback.
14. Restored dedicated Lite flavors and lightweight defaults on the shared 2.2 core.
15. Fixed website tab/pagination behavior that continuously appended or loaded unrelated Movies/Series.
16. Made web pages append in place and tightened bundle/cache refresh behavior after pagination fixes.
17. Fixed stale source-count regression tests after adding Embedwave.
18. Fixed stale homepage-panel expectations after adding the new discovery shelves.
19. Updated web guards/tests that still assumed a single provider.
20. Fixed the Windows smoke-test shutdown hang caused by fixture-server keep-alive connections.
21. Hardened CI generated-source handling against unrelated platform diffs and push races.
22. Expanded regressions for Hybrid controls, ad protection, manual sources, recovery defaults, native controls, panel order and unique-series Up Next.

[Full 2.2 record, including feature and validation details](docs/release-2.2.md).

<details>
<summary><strong>2.1.2 — Windows source/close regression and stronger ad filtering</strong></summary>

- Fixed `Cannot access 'action' before initialization` in the Windows title/source interface.
- Restored series details Close/X and source-selection actions.
- Retained per-title source confirmation and the verified Windows completion logic.
- Added HiStats blocking on Windows, Android and TV.
- Added Windows network-level cancellation of blocked ad/tracker frames while preserving provider/CDN allowlists.
- This hotfix did not claim the unfinished Android episode-parity experiment was fixed.

[Full 2.1.2 notes](docs/release-2.1.2.md).

</details>

<details>
<summary><strong>2.1.1 — mpv offline playback, episode completion and per-title history</strong></summary>

- Replaced Windows Chromium-only offline playback with mpv and a native child video surface.
- Added embedded audio/subtitle tracks, local subtitle loading and hardware-decoding support.
- Refreshed episode progress while details remain open.
- Captured real video position/duration and `ended` events; episodes at 95%+ finalize as 100% / Watched.
- Final completion bypasses progress throttling; finished episodes are locked before auto-next can overwrite them.
- Fixed Continue % / Watched selector state.
- Retained one-time/per-title source preferences, per-episode history and dedicated history clearing.
- Retained finalized carousel-source parity, stronger selectors and reset behavior on Android/TV.

[Full 2.1.1 notes](docs/release-2.1.1.md).

</details>

<details>
<summary><strong>2.1 — Windows updater rebuild, carousel parity and settings fixes</strong></summary>

- Replaced fragile in-place ZIP replacement with the dedicated updater helper.
- Added verified download, versioned extraction, atomic activation, process handoff, rollback and staging cleanup.
- Fixed updated app relaunch by launching the active version independently.
- Added helper error reporting and retained version confirmation safeguards.
- Fixed carousel settings not changing the actual visible hero on Windows/Android/TV.
- Kept all six carousel sources distinct; empty Watchlist/Continue Watching no longer silently become Trending.
- Capped the carousel at ten titles and linked trailer previews to the displayed movie.
- Improved Android/TV season/episode focused, pressed and selected states in light/dark themes.
- Clarified exit choices and added reset while retaining downloaded files.
- Removed ordinary Windows app exit confirmation.

[Full 2.1 notes](docs/release-2.1.md).

</details>

<details>
<summary><strong>2.0 — all 52 interface, TV, player, update and protection changes</strong></summary>

1. **Windows updater rebuilt** — checks GitHub on launch and manually, shows version/changelog, supports Update / Cancel / Skip, download progress, verification and relaunch.
2. **Android / Android TV updater added** — checks the GitHub release, downloads the matching APK and hands it to the Android installer with TV/mobile-friendly controls.
3. **Windows Settings reorganized** into General, Home, Appearance, Experience, Playback & Downloads, Player and About while preserving existing settings handlers.
4. **Android / Android TV Settings cleanup** brought the same grouping and readability improvements to the mobile/TV builds.
5. **Clean UI rebuilt around a YouTube-TV-style navigation model** with a full sidebar that collapses to a compact icon rail when browsing content.
6. **Modern UI rebuilt around a Google-TV-style presentation** with balanced header/navigation, cinematic discovery and portrait movie cards.
7. **Flix UI added** as a fourth cinematic layout with its own isolated layout rules so it does not leak styling into the other interfaces.
8. **Native / Original UI preserved** for users who prefer the original ZeroPlay layout.
9. **Fresh-install defaults corrected:** Windows → Clean UI, Android phone/tablet → Modern UI, Android TV → Native / Original UI. Existing saved choices remain untouched.
10. **Modern Movies & Shows shelves made more compact** so more content fits naturally without oversized cards.
11. **Portrait vs. landscape card behavior corrected** across content sections.
12. **Carousel timing standardized to 15 seconds** with directional transitions that respect the UI Animations setting.
13. **17 exact themes finalized:** Zero Dark, Zero Light, Ocean, Orchid, Sunset, Midnight Blue, Ember Glow, Forest Moss, Rose Noir, Amethyst, Cyber Mint, Cobalt Sky, Golden Hour, Coral Night, Aurora, Slate Ice and Mocha.
14. **Theme-aware ZeroPlay branding added** so `Zero` remains the primary word while `Play` follows the active accent color.
15. **Clickable cast added on Windows** using the exact TMDB person ID rather than name guessing.
16. **Clickable cast / filmography added on Android** using TMDB combined credits.
17. **Filmography pages corrected** to use combined movie + TV credits and the selected person’s exact TMDB identity.
18. **Categories redesigned** with clearer artwork, alphabetical browsing, international/language collections and Anime using the same visual system as the other categories.
19. **Surprise Me Again scoped correctly** so the control appears only on a title that was actually opened through Surprise Me.
20. **Rotten Tomatoes ratings added** through OMDb when `OMDB_API_KEY` is configured.
21. **Android TV Settings focus visibility fixed** so dropdowns and selectable settings show a clear highlight.
22. **Android TV Home navigation restored** to normal Android focus dispatch after custom key interception caused focus regressions.
23. **Android TV player mode made Settings-only** — Mouse / D-pad selection is no longer accidentally changed by a playback key.
24. **Android TV D-pad player navigation rewritten from scratch.** The previous collection of iframe guesses, wake interceptors and competing handlers was removed in favor of one TV input controller.
25. **D-pad focus highlight cleaned up** with a slimmer teal focus treatment that also works with open Shadow DOM controls.
26. **D-pad left/right navigation no longer intentionally maps to provider seek actions** while navigating the control surface.
27. **D-pad hidden-control wake handling rebuilt** so the first remote input is reserved for restoring provider controls before normal navigation continues.
28. **Android TV Mouse mode kept independent** from the D-pad controller; the smooth virtual pointer remains the Mouse-mode input path.
29. **Mouse-mode Back sequence fixed:** an open settings/quality/subtitle function closes first; visible player controls hide next; only the following Back exits playback.
30. **Player Back behavior is no longer allowed to dump the user out of playback just because an embedded provider is slow to answer.**
31. **Playback exit confirmation added** so leaving a movie/episode requires an explicit `Yes, exit` choice; `No` returns to playback.
32. **App exit confirmation added** to reduce accidental exits from the main application.
33. **Live TV reliability improved** with safer retry/error handling on Windows and Android.
34. **Cignal and Converge controls separated** so each provider can be enabled independently; Windows defaults both off while keeping other Live TV channels available.
35. **Android TV icon centering corrected** across focusable navigation surfaces.
36. **Windows Flix top-right controls corrected** and kept isolated from the other layouts.
37. **Windows Offline Player rebuilt around local/native playback** with local SRT/VTT/ASS/SSA parsing, playback controls, Fit / Fill, speed and fullscreen support.
38. **Windows Offline Player compositor handling improved** with the Direct Composition video-overlay workaround retained for files that previously hid controls behind the video surface.
39. **Manual Offline Player show/hide control added** as a fallback after automatic hide proved unreliable across Windows media/compositor combinations.
40. **Windows app fullscreen button added** beside Surprise Me / Update. It enters true app fullscreen, removes the normal Windows chrome and hides the visual scrollbar while preserving scrolling.
41. **Live PPV / Live TV / Live Sports player behavior retained** while the movie/offline-player work was isolated from those stacks.
42. **Adblock ruleset versioned** as `2.0-known-good-2026-10-08` so the known-good baseline is identifiable and reversible.
43. **Confirmed-host quarantine added:** a host positively identified by the QR scanner is blocked exactly for the rest of the current session.
44. **Temporary learned ad-host list added:** confirmed hosts are remembered for seven days rather than becoming permanent rules forever.
45. **Explicit provider/CDN allowlist added** so VidStuck, VidSrc.sh and common player/video CDN hosts cannot be accidentally caught by future ad rules.
46. **Local adblock debug log added** with the ruleset version, blocked host and reason, making playback regressions easier to diagnose without sending browsing data anywhere.
47. **Safer cosmetic ad cleanup added:** the app first removes an exact confirmed-host ad frame/container; the broader legacy QR cleanup is used only as a confirmed-QR fallback.
48. **Popunder/new-window hardening retained and reinforced** through denied popup windows, denied unsupported top-level redirects and allowlist-aware network rules.
49. **Windows adblock now mirrors the learned-host model** with an expiring local learned-host file and rolling local debug log.
50. **QR confirmation never opens the QR destination and never interacts with CAPTCHA/verification controls.**
51. **Release/test coverage expanded** for the rewritten TV controller, Shadow DOM focus, staged Back behavior, adblock allowlist, ruleset version and exact-host quarantine.
52. **Windows portable packaging remains installer-free** and keeps all normal browsing/playback in one application window.

Some 2.0 behavior was subsequently replaced: Windows updater/offline playback were rebuilt in 2.1/2.1.1, Windows app exit confirmation was removed in 2.1, and TV Hybrid mode was added in 2.2.

[Full 2.0 notes](docs/release-2.0.md).

</details>

<details>
<summary><strong>Earlier releases — complete fixes and historical changes</strong></summary>

- [2.0.3: full fixes and release notes](docs/release-2.0.3.md)
- [2.0.2: full fixes and release notes](docs/release-2.0.2.md)
- [2.0.1: full fixes and release notes](docs/release-2.0.1.md)
- [1.9: full fixes and release notes](docs/release-1.9.md)
- [1.8.2: full fixes and release notes](docs/release-1.8.2.md)
- [1.8.1: full fixes and release notes](docs/release-1.8.1.md)
- [1.8.0: full fixes and release notes](docs/release-1.8.0.md)
- [1.7.5: full fixes and release notes](docs/release-1.7.5.md)
- [1.7.3: full fixes and release notes](docs/release-1.7.3.md)
- [1.7.2: full fixes and release notes](docs/release-1.7.2.md)
- [1.7.1.1: full fixes and release notes](docs/release-1.7.1.1.md)
- [1.7.1: full fixes and release notes](docs/release-1.7.1.md)
- [1.7: full fixes and release notes](docs/release-1.7.md)
- [1.6.1: full fixes and release notes](docs/release-1.6.1.md)
- [1.6.0: full fixes and release notes](docs/release-1.6.0.md)
- [1.5.1: full fixes and release notes](docs/release-1.5.1.md)
- [1.5: full fixes and release notes](docs/release-1.5.md)
- [1.0.1: full fixes and release notes](docs/release-1.0.1.md)
- [1.0: full fixes and release notes](docs/release-1.0.md)
- [0.4.5: full fixes and release notes](docs/release-0.4.5.md)

Every retained release note is included in the [full changelog archive](docs/changelog.md), including the updater/series maintenance releases, trailer/layout fixes, download/subtitle changes, live-player repairs and early ad/remote-navigation fixes.

</details>

## 🧪 Release verification

The production pipeline covers native unit tests, all four APK builds/lint/signatures/package IDs, TV-navigation regressions, Windows unit/UI/player-guard/Electron smoke checks, preserved updater/mpv packaging, packaged-ASAR checks, Microsoft Defender scanning, web checks, credential audits and SHA-256 checksums. [Details](docs/release-2.2.md#-release-validation).

## ℹ️ Playback notes

- Providers can change independently; **Embedwave remains BETA**.
- Progress, remote controls, subtitles and audio boost depend on provider/device support.
- A changed QR creative may require its own X or timeout until recognized.
- ZeroPlay does not solve or bypass CAPTCHA/Cloudflare verification.
- Local media compatibility varies; **Open in external player** remains available.
- Libraries/profiles are local; there is no cloud account synchronization.

## 🙌 Credits

Metadata/artwork: **TMDB** and the respective rights holders. This product uses the TMDB API but is not endorsed or certified by TMDB. Availability: **JustWatch through TMDB**. Optional ratings: **OMDb**. Embed providers: **VidStuck, Embedwave, VidSrc.sh**; optional RawCast where configured. QR decoding: **ZXing**. Android media: **AndroidX Media3**. Windows downloads: **WebTorrent**; offline playback: **mpv**.
