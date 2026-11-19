# Nova guards

Three scripts that fail when Nova has dead buttons, made-up references, or
checks that pass without proving anything.

Each script runs existing open-source tools and adds as little of its own
logic as possible. The detection itself comes from the tools listed below. The
only Nova-specific parts are some rule files and one small piece of glue, all
listed under [What is written here](#what-is-written-here-and-why).

```bash
nova-tv/guards/install-tools.sh      # pinned tools into nova-tv/guards/.tools
nova-tv/guards/anti-dead-button.sh
nova-tv/guards/anti-hallucination.sh
nova-tv/guards/anti-false-green.sh
```

- A script exits 0 only if every one of its checks passed.
- A tool that is not installed is reported as **NOT RUN**, and that also fails
  the guard. A skipped check would be a false green.
- Each script writes `reports/<guard>.md`.
- CI runs all three in `.github/workflows/nova-guards.yml` and commits the
  reports back to the branch.

## Imported tools

| Guard | Tool | Upstream | Licence | What it catches |
|---|---|---|---|---|
| dead button | semgrep 1.186.0 | [semgrep/semgrep](https://github.com/semgrep/semgrep) | LGPL-2.1 | empty or `TODO()` click handlers; setters that only record a value |
| dead button | detekt 1.23.8 | [detekt/detekt](https://github.com/detekt/detekt) | Apache-2.0 | empty bodies; unused parameters, members, classes and imports |
| dead button | Nova's own button audit | `nova-tv/python/nova/buttons.py` (already in this repo) | repo licence | settings and state the UI writes and nothing reads |
| hallucination | Kotlin compiler, via Gradle | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 | calls to classes or functions that do not exist |
| hallucination | actionlint 1.7.12 | [rhysd/actionlint](https://github.com/rhysd/actionlint) | MIT | made-up workflow keys, action inputs, contexts and expression functions |
| hallucination | lychee 0.24.2 | [lycheeverse/lychee](https://github.com/lycheeverse/lychee) | Apache-2.0 / MIT | dead URLs, missing files and missing `#anchors` cited in docs |
| hallucination | ruff 0.17.5 | [astral-sh/ruff](https://github.com/astral-sh/ruff) | MIT | Python names that are used but never defined |
| false green | semgrep 1.186.0 | as above | LGPL-2.1 | see the semgrep list below |
| false green | ShellCheck 0.11.0 | [koalaman/shellcheck](https://github.com/koalaman/shellcheck) | GPL-3.0 (run as a tool, not linked) | workflow steps whose failure is thrown away by a pipe (SC2312) |
| false green | PIT 1.22.1 | [hcoles/pitest](https://github.com/hcoles/pitest), via [koral--/gradle-pitest-plugin](https://github.com/koral--/gradle-pitest-plugin) 0.3.0 | Apache-2.0 | tests that stay green when the code is broken on purpose |
| false green (CI) | action-junit-report v6 | [mikepenz/action-junit-report](https://github.com/mikepenz/action-junit-report) | Apache-2.0 | no test results, or no passing tests |

**What the semgrep false-green rules look for:**
- `@Test` functions with no assertion
- `@Ignore`d tests
- tools that return `"ok": True` whatever they found
- `|| true` in CI steps
- `continue-on-error: true`
- `if-no-files-found: ignore` on uploads

## What is written here, and why

Only the parts no existing tool provides:

- **`semgrep/*/*.yml`**: rules for semgrep's engine.
  - Each rule comes with a fixture file, and `semgrep --test` proves the rule
    fires on the bad shape and stays quiet on the good one.
  - Each guard runs that self-test first, so a rule that has stopped matching
    fails loudly instead of passing quietly.
- **`detekt-dead-code.yml`**: chooses which detekt rules run. No rules are
  written here.
- **`tools/workflow_shellcheck.py`**: about 60 lines of glue that hand
  workflow `run:` blocks to ShellCheck.
  - The usual wrapper, actionlint, adds `set -eo pipefail` to every bash step.
  - GitHub only does that when a step says `shell: bash`. A step with no
    `shell:` runs as `bash -e`, so `./gradlew test | tee log` is green even
    when the tests fail.
  - The glue gives ShellCheck the shell GitHub really uses. The finding itself
    is ShellCheck's.
- **`lib.sh`**: runs each tool, keeps its exit status, and writes the report.

**Considered and left out:**
- `nova-tv/python/scripts/verify_claims.py`, the repo's existing claim
  checker. On the current docs, all three of its findings were false
  positives: a shortened path, and two folder paths read as GitHub repo
  names. A gate that is wrong every time teaches people to ignore it.
- compose-rules for detekt. Its rules are about Compose API style, not dead
  controls.
