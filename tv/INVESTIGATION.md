# TV investigation, rewire & Xiaomi optimisation — 2026-10-21

Consolidated findings for the launched TV site, the repo branch behind it,
and the target device. This is the working record for the "debug · rewire ·
rebrand" programme; the prioritised backlog is at the end.

---

## 1. The launched TV site (Vercel)

| Item | Value |
| --- | --- |
| Project | `zeroplay-rd` (`prj_5aO1yXRs2R1TMx66LVoie2xMejXJ`, team `savage-coo`) |
| Production domain | **https://zeroplay-rd.vercel.app** (+ `zeroplay-rd-savage-coo.vercel.app`) |
| Root directory | `tv/zeroplay-debrid` |
| Framework | `vite` preset; real build is `npm run build` = copy `index.html` → `dist/` |
| Functions | 2 Node lambdas: `api/tmdb.js`, `api/extract.js` |
| Environment variables | **none** — live runs fully keyless (Cinemeta catalogue; TMDB only activates if `TMDB_API_KEY`/`TMDB_ACCESS_TOKEN` is ever added server-side) |
| Latest production | `dpl_EwwzxAP1qUiTQF5o977gFMJ7kr7g`, READY/PROMOTED, 2026-10-21 15:55 UTC |
| Deployments | 20+ since 2026-10-20 16:46 UTC, all READY except the very first |

### Critical deployment findings

1. **Vercel is not Git-connected.** The first deployment
   (`dpl_2q3Ko4…`, commit `83d5e43`) failed with `git_info_fail` —
   *"A Github account is not connected to this Vercel account"*. Every
   deployment since has been a manual/CLI upload with **no commit metadata**.
2. **Production does not provably match any commit.** Vercel file UIDs are
   raw SHA-1s (the deployed `vercel.json` UID matches `sha1sum` exactly), but
   the production `api/extract.js`, `api/tmdb.js` and `package.json` UIDs
   match **no committed version in the entire repo history**. Production was
   uploaded from an uncommitted working tree (most likely 5 minutes after
   commit `e8957f4`, but with local edits). Treat live as *drifted/unknown*
   until redeployed from a known commit (§9, P0).
3. The Android shell hardcodes `zeroplay-rd.vercel.app`, so web fixes reach
   TV boxes on next launch — no APK release needed for web-only changes.

## 2. Repo branch & commits behind it

- Branch **`tv`** (`1fc5b40`) is the branch relating to the Vercel project:
  30 commits on top of `main` (`bdf87d1`), adding `tv/` (web app +
  `android-tv/` shell + `upstream-zeroplay/` reference), the
  `zeroplay-android-tv.yml` workflow and `plan.md`.
- TV work had **diverged into two heads** at `ef1a522`:
  - `tv` → `74f20f6` player action shelf · `7cdea4c` browser/Back parity ·
    `1fc5b40` FastStream default.
  - `arena/ae20c772…` → `b07383c` nav + QR-scam blocking · `b55388e` iframe
    sandbox removal · `e8957f4` dedicated cached-row nav engine, spotlight,
    FastStream surface, native D-pad cursor.
- **This session branch (`arena/6cd374b4…`) now contains both**: fast-forward
  to `tv`, then merge commit `3b1b100` reconciling 7 `index.html` hunks +
  1 test hunk (ae20 engine kept; tv shelf, Back parity and FastStream intent
  preserved). Followed by a debug batch and the TV-shell rewire below.
  240 automated checks pass (`npm test` in `tv/zeroplay-debrid`).

## 3. Target device: Xiaomi TV Box S (3rd Gen) — MDZ-32-AA

Sources: Xiaomi specs <https://www.mi.com/global/product/xiaomi-tv-box-s-3rd-gen/specs/>,
AndroidTV Guide <https://www.androidtv-guide.com/streaming-gaming/xiaomi-tv-box-s-v3/>,
androidpctv review (system inspection, model `MiTV-AFMU0`, codename *twilight*)
<https://androidpctv.com/xiaomi-tv-box-s-3rd-gen-review/>.

| Aspect | Reality | Consequence for us |
| --- | --- | --- |
| SoC | Amlogic S905X5M, 4×Cortex-A55 @2.5 GHz, Mali-G310 V2, 6 nm | Modest CPU; GPU fine for transforms/opacity, weak for blur/shadows |
| RAM/storage | **2 GB** / 32 GB eMMC (~1 GB free RAM typical) | WebView memory is the wall: cap image sizes, shed previews on `onTrimMemory` (wired) |
| OS | Google TV / Android 14, **32-bit userspace** | APK must be `armeabi-v7a` (already a split APK; verifier enforces) |
| Output | 4K60, Dolby Vision/HDR10+, AV1/VP9/HEVC decode in hardware | UI renders at 4K; CSS viewport ≈1920×1080 @ ~2× — design 10-foot at 1080p CSS, never ship `original`-size artwork (already prefers `medium` in TV perf mode) |
| Network | Wi-Fi 6, BT 5.2, no Ethernet | Assume variable Wi-Fi; aggressive preloading is opt-in |
| WebView | Android System WebView (updatable Chromium) | Modern CSS/JS OK; but D-pad arrives as Arrow/Enter keys, **Back arrives at the native Activity, not the page** |

### The remote — the whole input contract

Verified from product imagery + reviews. Buttons, top to bottom: Power,
Google Assistant, **D-pad ring + OK**, Xiaomi TV+, **Back**, Home,
Netflix / Prime Video / YouTube / Apps shortcuts, Volume +/−.

**There is no Menu, Search, Guide, Channel, Play/Pause, FF/RW, number or
colour button.** Power/Assistant/Home/Volume/shortcuts are consumed by the
system and never reach the app. That leaves exactly six app-usable inputs:

| Remote | Android `KeyEvent` | In WebView page | App meaning |
| --- | --- | --- | --- |
| ◀ ▲ ▼ ▶ | `DPAD_LEFT/UP/DOWN/RIGHT` (21/19/20/22) | `ArrowLeft/Up/Down/Right` (+`repeat`) | Move focus (cached row engine); in player: navigate controls / seek only when chrome hidden & native/API engine |
| OK | `DPAD_CENTER` (23) | `Enter` | Activate focused control; toggle play on stage |
| **hold OK ≥600 ms** | repeats (native-owned) | bridge call | **Servers** (added this session — there is no Menu key) |
| Back | `KEYCODE_BACK` (4) | **native only** → `handleZeroPlayTvBack()` | Layer contract in §5 |
| Assistant | system voice search | never reaches app | In-app search must be D-pad reachable; the Google TV on-screen keyboard provides voice input inside text fields |

Native shell facts (`MainActivity.java`, ~1000 lines, dependency-light):
`dispatchKeyEvent` intercepts Back (async JS evaluation + 900 ms watchdog),
media/channel keys (dead on this remote, alive for CEC/other remotes),
Menu/Guide/Search (likewise), and implements a **native D-pad cursor**
(`TvCursorView`, accelerating with repeat count) used only for experimental
embed control. Android TV navigation guidance:
<https://developer.android.com/training/tv/get-started/navigation> ·
controller key table:
<https://developer.android.com/training/tv/get-started/controllers>.

### Smoothness envelope (already applied + rules going forward)

- TV mode strips `backdrop-filter`, caps transitions/animations at 0.12 s,
  disables hover/scale effects and scroll-snap; poster focus = ring only.
- Nav uses a cached row registry (MutationObserver-invalidated) so a key
  press doesn't re-measure the document; desired-X memory keeps vertical
  moves in column; held-key scrolling is driven by one 16 ms tick
  (240 ms warm-up, 56 ms → 34 ms) — see debug batch below.
- TV perf clamps: priming 1 thread/128 KB/1 MB budget/12 s buffer,
  downloads 2 threads/1 MB chunks/1 concurrent.
- Rules: animate `transform`/`opacity` only; no blur, no large shadows on
  the focused path; images at TV widths (`w342/w500` posters, `w780/w1280`
  backdrops); keep DOM rails bounded (continuous shelves already lazy-load).

## 4. Navigation architecture (audited)

`remote → MainActivity.dispatchKeyEvent → (Back/Menu/long-OK/cursor handled
natively) → WebView DOM keydowns → document handler → handlePlayerKeyboard
(player) or moveTvFocusFromKey → tvNavMove (cached rows)`. Lost focus
recovers near the last point, never jumps to top. Browser simulation
(`?androidtv=1`, Escape=Back) mirrors the native contract.

## 5. Player: the TV shell (implemented this session)

FastStream's own UI is mouse/touch-driven and **not D-pad compatible**, so
the earlier "FastStream surface" model (cede focus to the FastStream
iframe, drive it with a cursor) has been replaced:

- **Our control bar is the only D-pad focus target in the player.** All
  frames are `tabindex="-1"` (outside the nav registry), pointer-blocked by
  CSS (`.player-overlay:not(.embed-focused) .player-frame`) and covered by
  the `mediaClick` layer. There is no focus path *into* FastStream.
- **FastStream = decoder only**, driven via EmbedAPI `postMessage`:
  `load`, `togglePlay`, `seek`, `setVolume/setMuted`, `setPlaybackRate`,
  `setPictureInPicture`, `getLevels/setVideoLevel`, `getState`, event
  subscription. No DOM clicking.
- **Routing by source kind on Android TV:**
  - Direct files → native `<video>` engine (full own controls incl. CC).
  - HLS → native if the WebView can play it (API probed in background);
    FastStream-API once known available (real quality levels).
  - DASH → FastStream-API only. If the API doesn't answer and native can't
    play it: honest error → Servers. The legacy mouse-UI FastStream iframe
    **never mounts on TV** (guard in `beginLegacy`).
  - Embed pages → **EXPERIMENTAL ON TV**: badged in Servers, open under our
    bar, and only take over via explicit **Site controls · experimental**
    (native cursor). Desktop behaviour for embeds/FastStream surface is
    unchanged.
- **Back contract (deliberate, in order):** close menu/modal/drawer →
  release experimental site control → exit fullscreen → **if controls
  hidden, show them** → exit player → close detail → blur input → go home →
  native finish/exit.
- Known limits (honest): FastStream's own on-screen chrome may still be
  *visible* inside its frame until its idle autohide — cross-origin CSS
  can't restyle it, but it receives no input. The FastStream API bridge
  currently exposes no subtitle/audio-track verbs, so CC exists only on the
  native engine; audio-track selection doesn't exist yet (§9).

## 6. Debug fixes applied (this session, all test-backed)

1. **Hold-to-scroll double-drive:** OS repeats and the smooth-scroll tick
   both moved focus; repeats now only sustain the hold, and the ramp ages
   from the initial press (BT remotes repeat late).
2. **Long-press OK → Servers** (native-owned; `ACTION_UP` swallowed so
   WebView doesn't synthesise a click; mirrored in browser simulation).
3. **Native Back race:** late JS answers after the 900 ms watchdog could
   finish one Back press twice — guarded.
4. **Spotlight trailer** now respects the explicit Data Saver setting.
5. All "press Menu" hints (a key this remote doesn't have) corrected.
6. Local `blob:` playback stays native (online FastStream can't open it) —
   preserved through the merge.

## 7. Cleanup rewire (applied)

- Removed the stale root-level `zeroplay-debrid/` duplicate (921-line first
  prototype; `tv/zeroplay-debrid/` is the deployed app). Recoverable from
  git history at `1fc5b40`.
- Regenerated `zeroplay-debrid-netlify.zip` from current sources (it had
  gone stale the moment `index.html` changed).

## 8. How to verify

- `cd tv/zeroplay-debrid && npm test` → 240 checks.
- Browser: open the app with `?androidtv=1`; Arrow keys = D-pad, Enter = OK
  (hold = Servers in player), Escape = Back.
- CI: pushing `arena/*` with `tv/` changes builds & verifies the ARMv7 APK
  (`.github/workflows/zeroplay-android-tv.yml`); `[release-tv]` publishes.
- On the Xiaomi box: sideload per `android-tv/README.md`; checklist — launch
  focus lands, hold-scroll accelerates smoothly, play a direct file (native
  controls), an HLS (seek/quality), a DASH title (API path or honest
  error), an embed (badge + explicit experimental entry), Back sequence,
  hold-OK Servers, voice search inside the TV keyboard.

## 9. The huge list — prioritised backlog

**P0 — ship integrity**
1. Redeploy Vercel production from a known commit of this branch and
   Git-connect the project to `tv` (or this session branch) so production
   can never drift from git again (§1).
2. Decide rebrand (§10) before the next APK release (versionName 1.0.2 →
   next is the natural rename point).

**P1 — TV smoothness & player truth**
3. Probe FastStream EmbedAPI for subtitle/audio track verbs; wire CC/audio
   into our bar when available; hide honestly when not.
4. Measure overscan on a real TV (current side padding ≈54 px ≈2.8 % at
   1080p CSS vs the 5 % guideline) and tune safe margins per measurement.
5. On-device tuning of hold-scroll (BT repeat cadence) and focus-ring
   legibility at 3 m; optional focus sound via native shell.
6. Cap home DOM growth from continuous shelves (windowing) for 2 GB RAM;
   audit backdrop decode sizes at DPR 2.
7. Shelf "FastStream controls" button now routes to our controls on TV —
   relabel in TV mode; consider hiding on TV.

**P2 — code health**
8. `index.html` is 7 700+ lines / ~585 KB single file: extract the TV nav
   engine + player into modules behind the current `cp` build (esbuild?),
   keeping tests extraction-based during transition.
9. De-duplicate `api/` vs `netlify/functions/` twins via a build step
   (drift between them is only test-checked today).
10. Replace deprecated `onBackPressed` with `OnBackInvokedCallback` (API 33+,
    target SDK 34) keeping the dispatch contract; consider
    `WebView.setRendererPriorityPolicy` tuning for 2 GB.
11. Stop tracking the Netlify zip; generate at release time.

**P2 — product**
12. Long-press OK outside the player (context actions on cards: Play,
    Details, Download) — needs press-and-hold vs click disambiguation in
    the page activation path.
13. Search: ensure TV keyboard opens with voice affordance
    (`inputmode="search"`, enterkeyhint) and add letter-row quick search.
14. Embed health-based demotion on TV (experimental ones that never
    progress should stop wasting the 18 s notice window).

## 10. Rebrand inventory (awaiting new name)

"ZeroPlay" appears: 119× in `index.html` (title, wordmark, toasts), app
label `ZeroPlay TV`, package `com.savage.zeroplay.tv` (**changing this makes
it a new app — decides update continuity**), UA token `ZeroPlayTV/`, JS
bridge `ZeroPlayAndroidTV` + `handleZeroPlay*`, events
`zeroplay-blocked-scam`/`zeroplay-android-tv-ready`, storage keys
(`zeroplay-downloads-v1`, `zeroplay-settings.json` — **renaming orphans user
data; migrate or keep**), Vercel project/domain `zeroplay-rd(.vercel.app)`,
dirs `tv/zeroplay-debrid/` (Vercel root), `tv/upstream-zeroplay/` (must stay
— it's upstream's name), workflow file, icon/banner. A rename is therefore:
display strings + store listing art (safe) · domain/project (redirects) ·
package/keys (breaking, needs migration plan). Do it in that order.
