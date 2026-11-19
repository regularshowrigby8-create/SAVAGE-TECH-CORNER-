# Nova guards

Three guards that fail when Nova has dead buttons, made-up references, or
checks that pass without proving anything.

**No code is written here for the guards.** Each check is an imported tool or
upstream GitHub Action, wired up in `.github/workflows/nova-guards.yml`. The
only files in this directory are configuration for those tools:

| File | What it is |
|---|---|
| `detekt-dead-code.yml` | which of detekt's built-in rules run (no rules are written here) |
| `lychee.toml` | lychee settings: which files, which status codes count as alive |
| `reports/` | the tools' own report files, committed by CI |

## How they run

Each guard is one job in `nova-guards.yml`, so the workflow run shows one
pass/fail per guard and one step per tool:

- **Anti-dead-button**
- **Anti-hallucination**
- **Anti-false-green**

The rules for every step:

- No `continue-on-error` and no `|| true`.
- Steps use `if: !cancelled()`, so a failing tool does not hide the tools
  after it. The job still fails if any of them failed.
- The workflow's default shell is explicit `bash`, which GitHub runs with
  `-eo pipefail`. That makes `tool | tee file` fail when the tool fails.

On push, a fourth job commits the report files to `reports/` using
`EndBug/add-and-commit`.

## Imported tools

| Guard | Tool | Upstream | Licence | What it catches |
|---|---|---|---|---|
| dead button | detekt 1.23.8 (CLI release jar) | [detekt/detekt](https://github.com/detekt/detekt) | Apache-2.0 | empty bodies; `TODO()` bodies; unused parameters, members, classes and imports (an unused `onClick` is a dead button) |
| dead button | Nova's own button audit | `nova-tv/python/scripts/check_buttons.py` (already in this repo) | repo licence | settings and player state the UI writes and nothing reads; exits 2 when it finds any |
| hallucination | Kotlin compiler, via Gradle 8.13 ([gradle/actions](https://github.com/gradle/actions)) | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 | calls to classes or functions that do not exist |
| hallucination | actionlint 1.7.12, via [raven-actions/actionlint](https://github.com/raven-actions/actionlint) v2.2.0 | [rhysd/actionlint](https://github.com/rhysd/actionlint) | MIT | made-up workflow keys, action inputs, contexts and expression functions |
| hallucination | ruff 0.17.5, via [astral-sh/ruff-action](https://github.com/astral-sh/ruff-action) v4.1.0 | [astral-sh/ruff](https://github.com/astral-sh/ruff) | MIT | Python names that are used but never defined (F821, F822, F823, F811, F632, PLE) |
| hallucination | lychee 0.24.2, via [lycheeverse/lychee-action](https://github.com/lycheeverse/lychee-action) v2.9.0 | [lycheeverse/lychee](https://github.com/lycheeverse/lychee) | Apache-2.0 / MIT | dead URLs, missing files and missing `#anchors` cited in Nova's docs |
| false green | swallowed 0.1.0 (commit `4501bf3`) | [hamodywe/swallowed](https://github.com/hamodywe/swallowed) | MIT | CI steps that verify something and cannot fail: `continue-on-error`, `\|\| true`, pipes in a shell without `pipefail`, `set +e` |
| false green | Gradle unit-test tasks | as above | Apache-2.0 | the unit tests run and pass |
| false green | action-junit-report v6.7.1 | [mikepenz/action-junit-report](https://github.com/mikepenz/action-junit-report) | Apache-2.0 | no test results, no passing tests, or any failing test |
| false green | PIT 1.22.1, via [koral--/gradle-pitest-plugin](https://github.com/koral--/gradle-pitest-plugin) 0.3.0 | [hcoles/pitest](https://github.com/hcoles/pitest) | Apache-2.0 | tests that stay green when the code is broken on purpose. Thresholds are in `android/app/build.gradle.kts` (`pitest {}`) |

Supporting upstream actions: `actions/checkout`, `actions/setup-java`,
`actions/setup-node`, `actions/upload-artifact`, `actions/download-artifact`
and `EndBug/add-and-commit`.

## Where the results are

| Tool | Result |
|---|---|
| detekt | `reports/detekt.md` |
| Nova button audit | `reports/check_buttons.txt` |
| lychee | `reports/lychee.md`, plus the job summary |
| swallowed | `reports/swallowed.txt` |
| actionlint, ruff | annotations on the workflow run |
| unit tests | the "nova unit tests (guards)" check |
| PIT | the step log, plus the `nova-pitest-report` artifact (HTML and XML) |

## Scope limits

- swallowed reads every workflow in `.github/workflows`. Jobs from non-Nova
  workflows are left out with `--ignore <job-id>`.
- `android-build.yml` has a job called `build`, the same id as Nova's. It
  cannot be ignored without also ignoring Nova's. Its steps only produce
  `info` findings today, which do not fail the guard (swallowed fails on
  `error`).
- swallowed rates `nova-tv-python.yml`'s `github_collect.py … || true` as a
  data-collection step, not a verification step. That is an `info` finding and
  does not fail the guard.

## Removed in favour of imported tools only

Earlier versions had hand-written parts: `lib.sh`, the three `anti-*.sh`
runners, `install-tools.sh`, `tools/workflow_shellcheck.py` and custom semgrep
rules. They are gone. GitHub's own job and step results, annotations and
summaries replace their report table, and swallowed reports the same
pipe-without-pipefail steps the ShellCheck glue did.

The problems those custom rules found have since been fixed in the code:

| Custom rule | What it found | Status |
|---|---|---|
| setter that only records state | `ExoPlayerEngine.setResizeMode`, `setAudioDelayMs` | fixed: the PlayerView applies the resize mode, and there is a Picture size button; audio delay is removed (Nova has no renderer for it) |
| hard-coded `"ok": True` | four `nova-tv/python/scripts/*.py` tools | fixed: they report their real verdict, and `nova/cli.py` exits 2 on a failing one |
| `if-no-files-found: ignore` | the build-log upload in `nova-tv-android.yml` | fixed: correct log paths, `warn` |

No imported tool covers these, so they are not checked any more:

- an empty `onClick = {}` lambda. detekt `NotImplementedDeclaration` covers
  `TODO()`.
- `@Test` functions without an assertion, and `@Ignore`d tests. PIT covers
  tests without assertions indirectly.

**Not run, and why:** `nova-tv/python/scripts/verify_claims.py`, the repo's
claim checker. On the current docs, all three of its findings were false
positives.
