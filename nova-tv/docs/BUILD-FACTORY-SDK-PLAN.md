# nova-tv build factory — component plan

**Constraint this document obeys: nothing here is built from scratch.** Every
part is a component that already exists in a public repository, pinned to a
version verified against the GitHub API on 2026-11-17. Where a part has no
public equivalent, this document says so rather than inventing one.

Status: **plan only.** No workflow in `nova-tv/` compiles an APK. The repository
already owns a working Android build pipeline (see section 1), and the standing
law is that nova-tv does not grow a second one.

---

## 1. What already exists, and will not be rebuilt

`.github/workflows/android-build.yml` in this repository is a functional APK
factory. It already does the following — verified by reading the file, not
assumed:

| Stage | Implementation in place |
|---|---|
| Checkout | `actions/checkout@v4` |
| JDK | `actions/setup-java@v4`, temurin 17, `cache: gradle` |
| Python | `actions/setup-python@v5`, 3.11, for VM diagnostics |
| Assembly | `./gradlew clean :app:assembleDebug --stacktrace --no-daemon` |
| Output naming | renames the single debug APK to `SavageBrowser-v2.0.4.apk` |
| Verification | `tools/vm_crash_and_install_debugger.py` with `androguard<4.0`, against an API 34 / 256 MB heap profile |
| Artefact | `actions/upload-artifact@v4`, named `SavageBrowser-apk` |
| Release | `softprops/action-gh-release@v2` on `v*` tags, notes generated |

That is a complete, working pipeline. nova-tv must **not** copy it. The correct
move is to point it at nova-tv's sources later, or to add a job that does nothing
this pipeline already does.

## 2. What is genuinely missing

The existing pipeline is **not** fully functional for a new app. Four gaps, each
with an existing component that fills it.

### Gap 1 — signing

The pipeline builds `assembleDebug` and releases the debug APK. A debug APK is
signed with a public, throwaway key; it is not distributable.

- **Component:** `patrickfav/uber-apk-signer` **v1.3.0**, Apache-2.0.
  Wraps `apksigner` and `zipalign` in one call, so neither is driven by hand.
  Its signing engine is `apksig`, which ships inside the Android SDK
  build-tools and is deliberately *not* imported separately: two candidate
  standalone repos were checked and neither exists.

### Gap 2 — inspection

The pipeline prints `unzip -l | head -n 35`. That shows file names, not whether
the manifest or bytecode is valid.

- **Component:** `iBotPeaches/Apktool` **v3.0.3**, Apache-2.0.
  Decodes the manifest and resources so the pipeline can assert on
  `applicationId`, `minSdkVersion` and exported components instead of eyeballing
  a listing.

### Gap 3 — what changed

Nothing checks that a build only altered what it was meant to.

- **Component:** `JakeWharton/diffuse` **0.3.0**, Apache-2.0.
  Diffs two APKs down to class and resource level, so a release job can fail on
  an unexpected content change rather than shipping it.

### Gap 4 — size regression

No signal when a change quietly bloats the app.

- **Component:** `KeepSafe/dexcount-gradle-plugin` **4.0.0**, Apache-2.0.
  Emits per-method-count and APK size; a build can fail when it crosses a budget.

### Optional, only if a device matrix is ever wanted

- `ReactiveCircus/android-emulator-runner` **v2.38.0** (Apache-2.0) or
  `android-actions/setup-android` **v4.0.4** (MIT) to install SDK components in
  CI. Neither is needed while `vm_crash_and_install_debugger.py` covers the
  smoke test.
- `google/bundletool` **1.18.3** (Apache-2.0) — **not planned.** It exists for
  Play Store `.aab` delivery; nova-tv does not target Play, so importing it
  would be dead weight.

## 3. Verified inventory

Every row resolved via `gh api repos/<name>`; licence and archive status are as
GitHub reported them, not assumed.

| Repository | Version | Licence | Archived | Role |
|---|---|---|---|---|
| `iBotPeaches/Apktool` | v3.0.3 | Apache-2.0 | no | manifest/resource inspection |
| `patrickfav/uber-apk-signer` | v1.3.0 | Apache-2.0 | no | signing + zipalign |
| `JakeWharton/diffuse` | 0.3.0 | Apache-2.0 | no | APK-to-APK diffing |
| `KeepSafe/dexcount-gradle-plugin` | 4.0.0 | Apache-2.0 | no | size/method-count budget |
| `ReactiveCircus/android-emulator-runner` | v2.38.0 | Apache-2.0 | no | optional device matrix |
| `android-actions/setup-android` | v4.0.4 | MIT | no | optional SDK install |
| `google/bundletool` | 1.18.3 | Apache-2.0 | no | rejected — Play-only |
| `gradle/actions` | v6.4.1 | NOASSERTION | no | rejected — licence unclear |

Two entries are **rejected deliberately**: `gradle/actions` reports
`NOASSERTION`, which is not a licence this project can accept without reading its
terms, and `bundletool` solves a distribution model nova-tv does not target.

Three candidates were checked and **do not exist**, so they are absent rather
than provisionally listed: `apksig/apksig`, `google/android-emulator`,
`mattg-preis/apkdiff`.

## 4. Hosting sources checked

The request was to source parts from GitHub and any of Stack Overflow, Supabase,
GitLab, Bitbucket, SourceForge or Hugging Face. What each can actually provide
for an *Android build factory*:

- **GitHub** — every component above. This is the only host that matters here.
- **Stack Overflow** — not a code host; no repository to import from. Useful only
  as documentation, so it supplies no component.
- **GitLab / Bitbucket / SourceForge** — mirror or self-hosted code; the parts
  above are already canonical on GitHub, so importing a mirror would add a second
  source of truth for no benefit.
- **Supabase** — a Postgres/BaaS platform, not an Android build tool. It could
  later hold release metadata, but it contributes nothing to compiling an APK.
- **Hugging Face** — model and dataset hosting. Relevant only if the
  self-improvement loop ever runs a local model; irrelevant to the build factory.

Recorded because the absence is a finding, not an omission.

## 5. Order of work

1. Land this plan (this commit). Nothing executes.
2. Add `dexcount` and `diffuse` as build-time *reporting*, not release gates, so
   a failure is visible before it is blocking.
3. Wire signing and Apktool inspection into the **existing** pipeline behind
   `workflow_dispatch`, so the normal path is unchanged.
4. Only then decide whether nova-tv gets its own job or reuses the factory.

## 6. The honest gap in this plan

**The Kotlin in `nova-tv/android` has never been compiled.** There is no JDK, no
Gradle and no Android SDK in the environment this was written in. The linter in
`ops/kotlin_lint.py` proves structure — packages match directories, braces
balance, no duplicate classes — and proves nothing about whether the code
type-checks or the app runs.

A plan for a build factory is therefore ahead of the thing it would build. The
first real milestone is not signing: it is one successful `assembleDebug` of
nova-tv's own sources, which needs either a machine with the Android SDK or a CI
run. Until that happens, this document describes components that are real,
pinned and correct, applied to code whose compilability is unknown.
