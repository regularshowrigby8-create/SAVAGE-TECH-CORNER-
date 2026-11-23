# nova-tv — app component plan (phase 1)

**Phase 1 is planning. Nothing here is code, and nothing is compiled by this document.**

The compiler is settled and green. `nova-tv Android` (`.github/workflows/nova-tv-android.yml`)
ran `assembleDebug` and `testDebugUnitTest` to success on run **44333505082** and
produced a **15,089,426-byte** debug APK. That factory is imported, not built, and
is not touched by this phase.

What this phase decides is *what the app is made of*, and for each part: which
existing repository it is imported from, or — where nothing usable exists — that
it has to be written.

## The rule applied to every row

Every source below was resolved through the GitHub API on 2026-11-18. Licence is
what the API reports, not what I assumed. A component with no permissive source
is listed as **write it**, not quietly borrowed from a GPL project.

---

## 1. The licence wall, stated up front

This is the finding that shapes the whole plan.

| Project | Licence | Consequence |
|---|---|---|
| **NuvioMobile** (4671★), **NuvioTV** (3562★) | **GPL-3.0** | Cannot be copied into this app |
| **Lumera**, **Alder**, **VortX** | GPL-3.0 | Cannot be copied |
| **cove** | AGPL-3.0 | Cannot be copied; AGPL is stricter still |
| **unchained-android**, **debforge** (Real-Debrid Kotlin) | GPL-3.0 | Cannot be copied |
| **wuplayapp/wuplay-releases** (579★) | **no licence** | Release binaries only, no source. Nothing to import |
| **arvio** | only 2★/0★ bridges | No client source exists to import |
| **debridstream** | 0–1★ scrapers, mostly no licence | Nothing usable |
| **perpetus/stremio-core-kotlin** | **no licence** | Official-ish Kotlin bindings, unusable |

Those are the projects named as examples. Four of the five cannot be legally
imported, and one has no source at all. That is a finding, not an excuse: the
plan below uses what *can* be imported and says plainly what must be written.

**The one large, legitimate exception:** Stremio's own protocol work is **MIT** —
`Stremio/stremio-addon-sdk`, `stremio-addon-client`, and the Rust engine
`stremio-core` (2567★). The addon protocol is the single most valuable thing to
import, and it is available.

---

## 2. Components

### 2.1 Addon protocol (catalog, meta, stream, subtitles, library)

**Import.** `Stremio/stremio-addon-sdk` — MIT, 1448★. Ships `docs/protocol.md`,
the authoritative spec, plus `docs/api/responses/` for the JSON shapes.

Verified from that spec:
- Manifest at `/manifest.json`; resources at `/{resource}/{type}/{id}.json`
- Resources: `catalog`, `meta`, `stream`, `subtitles`, `player`, `library`
- Extra args are a stringified query object in the path:
  `/{resource}/{type}/{id}/{extraArgs}.json` (e.g. `search=x&skip=100`)
- Every route, including the manifest, must serve CORS for all origins
- `/stream/{type}/{videoID}.json` — for a series, `videoID` is the episode, not
  the show. For live `tv`, it is the channel id
- `/player/...` and `/library/...` are **events**: sent to every matching addon,
  response ignored. They carry `action=start|pause|stop`, `libraryAdd`,
  `watched`, `unwatched`, with `currentTime`/`duration` in ms

**Import.** `Stremio/stremio-addon-client` — MIT. The reference client: how a
client selects a transport and builds requests. Small (10★), but it is the
maintainer's own, so it defines the expected behaviour.

**What this replaces:** the hand-rolled `AddonClient.kt` currently in the app.
Its `supportsCatalog`/`supportsStreams` logic is a guess at the manifest shape;
the spec defines it exactly.

### 2.2 Catalogue and metadata rendering

**Import.** `chrisbanes/tivi` — **Apache-2.0**, 6715★, Kotlin, Android TV-aware.
The strongest permissive Kotlin reference for: modelling `MetaItem`/episode
hierarchies, paging catalogue rows, and Compose UI state for a media grid. Import
*patterns and data modelling*, not whole files.

**Also.** `subinps/TelePlay` — **MIT**, 125★, Kotlin, Android TV. Directly relevant
as the closest permissive TV-form-factor example.

**Write.** The merge/dedupe across multiple addons stays ours — it already exists
in `CatalogRepository.kt` and no upstream matches our ranking rules.

### 2.3 Playback

**Use, do not copy.** `androidx/media` (Media3/ExoPlayer) — **Apache-2.0**, 3086★.
This is a dependency, not an import. The existing `NovaPlayer.kt` already wraps it
correctly: failure modelled as data, ranked fallback list, one 500 ms position
tick. Keep it.

**Import.** Timing and gesture constants already come from the web player via
`nova/importers.py` (`ImportedTimings.kt`, generated). That pipeline stays.

### 2.4 Subtitles

**Import.** `oz/osdb` — **BSD-2-Clause**, 76★, Go. The cleanest reference for the
OpenSubtitles hash-based flow (`/subtitles/{type}/{id}.json` uses the file hash,
with `videoID` and `videoSize` as extra args). Reference only; the Go code is not
portable, the *flow* is.

**Write.** A Kotlin client for the OpenSubtitles REST API. No permissive,
maintained Kotlin client was found worth importing — the MIT hits are 0–2★.

### 2.5 Debrid

**Write, deliberately.** Every maintained Real-Debrid Kotlin client is GPL-3.0
(`unchained-android`, `debforge`, and three others). Importing any of them would
relicense this app.

The good news: it is small. Resolve a magnet → get an unrestricted HTTPS URL →
hand it to Media3. That is an HTTP call and a URL swap, which is why the GPL
projects are being skipped rather than imitated.

### 2.6 Storage and settings

**Use.** `androidx.datastore` (Preferences DataStore, Apache-2.0) for addon lists
and playback settings. The existing `SettingsStore.kt` already defines the shape
(addonUrls, autoplayNext, preferredQuality, subtitleSize, reduceMotion, volume,
playbackRate) over `SharedPreferences`; DataStore replaces the backing store with
an async one so writes never block the TV's single thread doing playback.

**Write.** Nothing else. The `Settings` model is already right.

### 2.7 Watching activity (library/player events)

**Import the semantics** from `protocol.md` §2.1 — the exact event shapes and the
rule that a seek re-sends `start`/`pause` and watched state is derived from
`currentTime`/`duration` on `stop`.

**Write** the dispatcher. **Do not import** Trakt clients: `SeriesGuide` ships no
licence, `MovieDB` and `glassio` are GPL-3.0, and `tivi`'s Trakt code is
Apache-2.0 but tied to its own auth stack — importing it would drag in more than
it saves.

---

## 3. What must be written, and why

| Component | Why there is no import |
|---|---|
| Multi-addon catalogue merge | Ranking and dedupe rules are product decisions |
| Debrid resolver | Every Kotlin implementation is GPL-3.0 |
| OpenSubtitles Kotlin client | Only unmaintained 0–2★ MIT options |
| Player event dispatcher | Tied to our playback state machine |
| The TV shell itself | No permissive source matches this layout and D-pad model |

That is five components. Everything else in the app is imported, a dependency, or
already exists and compiles.

---

## 4. Hosts assessed

The request was to consider GitHub and any of Stack Overflow, Supabase, GitLab,
Bitbucket, SourceForge or Hugging Face.

| Host | Reachable here | Contributes |
|---|---|---|
| **GitHub** | yes (200) | Every component above |
| **PyPI** | yes (200/404) | Python tooling only, not the app |
| **GitLab** | **blocked** (000) | Not verifiable from this environment |
| **Bitbucket** | **blocked** (000) | Not verifiable |
| **Hugging Face** | **blocked** (000) | Model hosting; nothing for a Kotlin client |
| **Stack Exchange API** | **blocked** (000) | Not a code host; documentation only |
| **SourceForge** | not reachable | No API probe succeeded |
| **Supabase** | n/a | BaaS/Postgres; could hold release metadata later, nothing for the APK |

Four of these were probed and returned no response from this sandbox. That is
recorded as "not verifiable here", not as "empty" — a claim I could not check.

---

## 5. What phase 1 does not claim

- **No code changes.** This document changes nothing that compiles.
- **The APK has still never been run.** It builds; nothing about its behaviour on
  a device is known.
- **Nothing here has been imported yet.** Every row is a verified *source*, not a
  completed import.

## 6. Next phase, in order

1. Replace `AddonClient.kt`'s guessed manifest parsing with the protocol as
   specified (2.1).
2. Write the five components in §3, against the imported specs.
3. Wire the player that already exists — today, tapping a poster sets `opened`
   and no screen renders it.
4. Run it on a device. That is the first evidence any of this works.
