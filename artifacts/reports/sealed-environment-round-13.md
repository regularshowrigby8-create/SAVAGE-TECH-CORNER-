# Round 13 evidence — GitHub tooling inside the seal

Scope: the round-13 brief — "use and import github tools to resolve all the current problems": bring
GitHub capability into the sealed environment as real tools, and use it to close the problems carried
out of round 12 (MCP servers that could not start, the emulator failure, definitions that were carried
without anything to run).

Everything below was produced by commands in this checkout on 2026-10-14. Nothing is copied from
another run and nothing is projected: where something could not be done on this machine, the machine's
own refusal is quoted instead of a summary of it.

---

## 1. What changed

| Part                              | Before (round 12)                                                        | Now                                                                                                                                                                                               | Where                                     |
| --------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| GitHub capability                 | absent — no GitHub tool existed in the registry                          | seven tools: the six data tools (`github.repo`, `github.file`, `github.search`, `github.issues`, `github.commits`, `github.pr`) plus `github.probe`, which the researcher role calls in every run | `sealed-factories/runtime/toolkit.py`     |
| Tool count in the seal            | 41 tools                                                                 | **48 tools** (48 native, 0 bound from MCP)                                                                                                                                                        | sealed run, §2.4                          |
| MCP servers verified by handshake | 7 servers, 52 tools                                                      | **16 servers, 303 tools**, each with the command that was verified                                                                                                                                | `tools/factory/imported_mcp_servers.json` |
| MCP servers that cannot start     | 24 of 42                                                                 | **14 of 42** not installed here; 7 more start but refuse for a named reason (see §2.2)                                                                                                            | catalogue + `cli.py mcp-probe`            |
| Emulator stage                    | ran `connectedDebugAndroidTest` against whatever state the device was in | wakes the screen, dismisses the keyguard, and asserts `Awake` + a non-keyguard focus before the suite                                                                                             | `tools/ci/android_emulator_run.sh`        |
| Seal root                         | `32bf92d3…`, 1,849,160 payload bytes                                     | **`d6b2faee…`**, 1,870,829 payload bytes, 137 factories, 10 component files pinned                                                                                                                | `sealed-factories/SEAL.json`              |

The seal root changed for one reason: `runtime/toolkit.py` gained the GitHub section and `runtime/agents.py`
gave the researcher role the GitHub tool group, and the seal pins every runtime module by hash
(AC-056). The payloads, the licence texts and the 137 records are
otherwise the same set.

The root is `sha256(SEAL.json)`, and that document records when it was sealed (`sealed_at`), so the
root moves on every re-seal even when nothing else does. What does not move is the content: two
consecutive seals of this tree produced **137 identical payload hashes and identical factory records**,
differing only in that timestamp. That is the property round 12 fixed and this round re-measured - a
re-seal of an unchanged tree rewrites no payload byte - and it is the property claimed below, not the
stability of the root itself, which is not claimed anywhere.

## 2. Evidence, verbatim

### 2.1 The GitHub tools, called live through the sealed tool server

```
$ python3 sealed-factories/runtime/toolserver.py --root . --call github.repo \
    --arguments '{"repository":"regularshowrigby8-create/SAVAGE-TECH-CORNER-"}'
{"status": 200, "authenticated": true, "rate_limit_remaining": "4934", "rate_limit": "5000",
 "full_name": "regularshowrigby8-create/SAVAGE-TECH-CORNER-", "language": "Python", "license": "MIT",
 "default_branch": "main", "open_issues": 17, "stars": 0, "pushed_at": "2026-10-14T15:22:05Z"}

$ … --call github.file --arguments '{"repository":"…/SAVAGE-TECH-CORNER-","path":"README.md"}'
{"status": 200, "authenticated": true, "bytes": 3669, "path": "README.md", "text": "# Savage Tech Corner & Savage Browser\n…"}

$ … --call github.pr --arguments '{"repository":"…/SAVAGE-TECH-CORNER-","number":17}'
{"state": "open", "mergeable": true, "mergeable_state": "clean", "additions": 61742,
 "deletions": 4457, "changed_files": 618, "base": "main", "files": [ … ]}

$ … --call github.commits --arguments '{"repository":"…/SAVAGE-TECH-CORNER-","limit":3}'
  bdf87d1d  Factory rework  2
  7b82bed2  Factory rework
  d487ac93  fix(launcher): add BrowserActivity alias class and m…

$ … --call github.search --arguments '{"kind":"repositories","query":"webview browser android in:name","limit":3}'
{"total": 10, "returned": 3, "authenticated": true, "first": "prabhumv/Android-WebBrowser-Demo--WebView-"}

$ … --call github.repo --arguments '{"repository":"not-a-repo"}'
tool refused: name a repository as owner/name          # stderr, exit status 1
```

The tools read the credential from `GITHUB_TOKEN`, `GH_TOKEN` or `GITHUB_PERSONAL_ACCESS_TOKEN`; it is
never returned in a reply. With all three unset the call is still made and reports its own state:

```
$ env -u GITHUB_TOKEN -u GH_TOKEN -u GITHUB_PERSONAL_ACCESS_TOKEN … --call github.repo …
{"status": 200, "authenticated": false, "rate_limit_remaining": "4929", "full_name": "…"}
```

Two things are true about that line and both belong here. The tool reports whether **it** sent a
token, which is all it can know, and it reports the limit the API applied rather than assuming one.
This sandbox's own egress to `api.github.com` is authenticated — an anonymous `curl` from here
reports `"core": {"limit": 5000}` — so the tier observed on this machine is the sandbox's, and is not
evidence about what an unauthenticated client elsewhere would receive. The claim made here is only
the one that was measured: the tool sends no token, says so, and the request is served.

Refusals are the API's own words, not a fabricated empty result:

```
$ … --call github.repo --arguments '{"repository":"owner/name"}'   # with the stub answer changed to 404
GitHub answered HTTP 404 for https://api.github.com/repos/owner/name: {"message":"Not Found",…};
this environment has a token - set GITHUB_TOKEN (or GH_TOKEN) to raise the limit or to see private data
```

Nine of those behaviours are asserted offline in `shell/tests/test_sealed_environment.py`
(`GitHubToolTest`, nine tests) against a local server that answers the API's own shapes: metadata,
commits, issues, a pull request with its files, search, the argument refusals before any call, the
token never appearing in a reply, a 404 surfacing GitHub's message, and an unreachable API refusing
with the reason instead of returning nothing. The real API is not required by the suite and the suite
does not depend on it being up.

### 2.1b The environment exercises GitHub itself, in every run

Registering seven tools and leaving them for a human to call would make them a promise. The researcher
role holds the six data tools - so an agent driving it with a model can reach GitHub - and its offline
path calls `github.probe`, which is the one GitHub tool whose subject _is_ reachability: it reports what
happened instead of raising, because a machine with no network has to be able to _record_ that as an
observation, not fail a step over it. The probe's record lands in the run's own artefacts:

```
$ python3 sealed-factories/runtime/cli.py run --root . --limit 40 --model-calls 0
[swarm] 0 ran with a model, 40 offline, 44 tool steps ok, 0 failed, 40/40 checks passed

$ cat .sealed/swarm/<run>/artefacts/researcher-0000013.json
{"tool": "github.probe", "ok": true, "summary": "available=True"}
```

and with the API pointed at a host that is not there, the same step records why - with no failed step
and no failed check:

```
$ SEALED_GITHUB_API=http://127.0.0.1:9 python3 sealed-factories/runtime/cli.py run \
    --root . --roles researcher --limit 1 --model-calls 0
[swarm] 0 ran with a model, 1 offline, 2 tool steps ok, 0 failed, 1/1 checks passed

{"tool": "github.probe", "ok": true,
 "summary": "available=False; status=0; reason=GitHub is not reachable from here: [Errno 111] Connection refused"}
```

The three outcomes the probe distinguishes are asserted in `GitHubToolTest`: `available=True` when
`/rate_limit` answers 200, `available=False, reachable=True, status=404` when a host answers but not
with a rate limit (a wrong `SEALED_GITHUB_API`, say), and `available=False, reachable=False, status=0`
when no host answers at all. The run's summariser was extended to keep `status` and `reason`, so the
explanation survives into the artefact instead of only the boolean.

**Cost, stated plainly:** this puts one bounded HTTPS request (5 s cap) into every swarm run. That is a
deliberate trade - the alternative is a capability that is asserted rather than exercised - and it
cannot fail a run: the step is a probe, and the six data tools keep their refusal contract for
everything else. On a machine with no network the run is 5 s slower and says so in its artefacts.

### 2.2 MCP round 2: what starts now, and the command that was verified

Round 12 recorded 24 servers that could not be started. Each was re-run through the handshake harness
(`tools/factory/mcp_handshake.py`), and where a package had moved or needed a pin, the launch block in
`tools/factory/imported_mcp_servers.json` was rewritten to the command that actually answered:

| Server                    | Command that answered the handshake                                                      | Tools |
| ------------------------- | ---------------------------------------------------------------------------------------- | ----- |
| azure-mcp                 | `npx -y @azure/mcp@latest server start` (`azure/azure-mcp` is archived; `microsoft/mcp`) | 69    |
| mcp-redis                 | `uvx --from redis-mcp-server redis-mcp-server`                                           | 53    |
| ida-pro-mcp               | `uvx --with mcp<2 --from ida-pro-mcp ida-pro-mcp` (mcp 2.x renamed `fastmcp`)            | 48    |
| chrome-devtools-mcp       | `npx -y chrome-devtools-mcp@latest`                                                      | 30    |
| firecrawl-mcp-server      | `npx -y firecrawl-mcp`                                                                   | 26    |
| chroma-mcp                | `uvx --with pydantic<2.12 chroma-mcp --client-type persistent --data-dir …`              | 13    |
| n8n-mcp                   | `npx -y n8n-mcp`                                                                         | 7     |
| awslabs aws-documentation | `uvx awslabs.aws-documentation-mcp-server@latest` (`awslabs.core-mcp-server` yanked)     | 5     |
| mcp-atlassian             | `uvx mcp-atlassian`                                                                      | 0     |

| dbt-mcp | `uvx --from dbt-mcp dbt-mcp` - refused, see below | 0 |
dbt-mcp was re-diagnosed rather than left at "needs a newer Python": the package is `dbt-mcp 2.7.0`
with `requires-python <3.14,>=3.12`, this machine's newest interpreter is 3.11.2, uv's interpreter
download fails on GitHub release assets (`tls handshake eof`, unchanged by `UV_NATIVE_TLS=1`), and no
CPython 3.12 distribution is obtainable from the hosts this sandbox can reach - PyPI answers 404 for
`python3.12`, `python-3.12` and `python-standalone`, and the npm package `cpython` 0.10.1 is a 2.7-era
node-gyp binding that clones CPython itself. That is now the recorded reason, in the package's own
words and this machine's.

Seven servers still refuse, and each one's refusal is recorded in the registry as `launch_note` in
the machine's own words rather than as an unverified guess; the refusals are named in §4.

The counts the catalogue publishes are the number of servers that were actually driven through
`initialize` + `tools/list`: 16 server names, 303 tools
(`tools/factory/imported_mcp_servers.json` → `verified_total_servers`, `verified_total_tools`). The
sealed catalogue `sealed-factories/mcp-servers.json` marks 11 **entries** verified — one of those
entries, `servers`, carries the seven round-1 handshakes — and a test now requires the two files to
agree on both totals, and requires every verified server to carry the command and the tool list it
was verified with. Both numbers are stated together because "11" and "16" are both true about
different things and neither one is allowed to stand in for the other.

`cli.py mcp-probe` on this machine now reports:

```
catalogue 42, installed 28, not_installed 14, probed 4
  servers                   13 tools  (handshake answered)
  context7                   2 tools  (handshake answered)
  chrome-devtools-mcp       30 tools  (handshake answered)
  playwright-mcp            25 tools  (handshake answered)
```

### 2.3 The seal verifies, and the root moved for one reason

```
$ python3 tools/factory/seal_factories.py seal
sealed 137 factories -> sealed-factories/SEAL.json
component files pinned=10
seal root sha256=d6b2faeecc19ad0afe6804576254838deae44fafc375c536e227918cd4ffacb6
payload bytes=1870829 licences=115

$ cat sealed-factories/SEAL.sha256
d6b2faeecc19ad0afe6804576254838deae44fafc375c536e227918cd4ffacb6  SEAL.json

$ python3 tools/factory/seal_factories.py verify
[SEAL] OK 1.0.0: 137 factories, 1870829 payload bytes, 115 licence texts, root d6b2faeecc19ad0a…

$ python3 tools/factory/sealed_factory_runtime.py verify
[SEAL] OK 1.0.0: 137 factories, 1870829 payload bytes, 115 licence texts, root d6b2faeecc19ad0a…
```

A test rebuilds every payload from its record and requires the bytes on disk to match, so re-sealing
an unchanged tree is still a no-op after this round's changes (§2.6).

### 2.4 The sealed run on this machine

```
$ python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0 --env-limit 200
[SEALED] 137 factories sealed (98 with redistributed content, 39 blueprint-only)
[SEALED] models: local=[] keyed=[]
[SEALED] environment: 48 tools (48 native, 0 bound from MCP), MCP catalogue 42 servers (11 verified here, 28 bindable on this machine), 159 agent roles, 7 language packs available (go, rust, java, kotlin, csharp, ruby missing)
[SEALED] swarm: 159 agent instances of a 159-instance plan in 8.24s, 163 tool steps, 159/159 checks passed
[SEALED] 0 factories ran against a model, 97 ran in plan mode, 40 had no definitions in their sealed slice, 0 model calls failed, 0 unreadable
[SEALED] 2 slice(s) carry content but define nothing this runtime reads as stages, workers or tools
[SEALED] MCP servers: 42 catalogued, 303 tools observed by handshake
[SEALED] definitions read from the sealed set: 481 stages, 504 workers, 159 checks, 879 prompt assets, 4098 sections of prose their definition files publish
[SEALED] report: sealed-factories/manifest/RUN-REPORT.json
```

The GitHub tools are registered in this run and never called by it: a run on a machine with no network
is not a failure, which is the behaviour `test_an_unreachable_api_is_refused_with_the_reason_not_an_empty_result`
pins.

### 2.5 The belt still runs the sealed set first, with GitHub tooling inside it

```
$ node scripts/conveyor_belt.js
[STATION 0] Sealed Imported Factory Set…
[STATION 3: PASS] Accessibility & Performance Audit (axe-core / Lighthouse) satisfied.
[STATION 5: PASS] Bundle Footprint & Sizing Limits satisfied.
[STATION 6: PASS] AI Reviewer & Middleman Broker Hand-Off satisfied.
   CONVEYOR BELT COMPLETE: SEALED SET RAN + ALL 6 CONNECTED STAGES PASSED
```

### 2.6 The gate suite

```
$ python3 -m unittest discover -s shell/tests
Ran 191 tests in 66.441s

OK
```

That is 12 more than round 12's 179: eleven in `GitHubToolTest` and one more in
`test_androidtest_failure_report.py` (`test_the_stage_wakes_the_screen_before_it_runs_the_suite`).
`shell/tests/test_sealed_environment.py` now runs 50 tests, `test_sealed_factory_runtime.py` 23
(including the payload rebuild-and-compare). The rest of the gates, on the same tree:

```
$ python3 tools/anti_deception_auditor.py
[PASS] Repository passed all authenticity, anti-hollowness, and provenance invariants.

$ python3 tools/duplication_auditor.py
[DUPLICATION-AUDITOR] PASS: All mirrors synchronized without unapproved drift.

$ bash factory-2/scripts/run_all.sh
FACTORY 2 RESULT: PASS

$ npm run lint / format:check / typecheck / test / knip / size / audit:security
all pass
```

One of those gates earned its keep this round: the anti-deception auditor rejected the first version
of the new tests for a bare `pass` in the stub's log-silencing method
(`Empty pass statement in function 'log_message'`). The fix was to keep the requests the stub served
and assert on them, which is a better test than the one that tripped the gate.

### 2.7 The emulator pre-flight

On run `226b71c` six of 52 instrumented tests failed at once with `RootViewWithoutFocusException`,
"the screen never took the window focus", and system Back never being dispatched: the emulator had
come up with its screen off and the keyguard showing, so the activities started but never took window
focus. The stage now, before the suite:

1. waits up to 30 × 2 s for `sys.boot_completed=1`;
2. up to 10 times: sends `KEYCODE_WAKEUP`, runs `wm dismiss-keyguard`, and reads back
   `dumpsys power`'s `mWakefulness=` and `dumpsys window`'s `mCurrentFocus=`;
3. treats the device as ready only when the state read back says `Awake` and the focus is not the
   keyguard, printing the two command exit statuses and the read-back state, and emitting a
   `::warning title=emulator pre-flight::` annotation when it is not ready — the suite runs either
   way and its result is what counts.

The exit statuses of the two `adb` commands are captured and printed rather than suppressed; what
decides readiness is the state read back underneath, never the optimism of having issued a command.

### 2.8 Continuous integration on this round's commit

All six workflows are green on `6815828`, the commit that carries the sealed component and its tests:

| Workflow                                   | Run                                                                                      | Result  |
| ------------------------------------------ | ---------------------------------------------------------------------------------------- | ------- |
| Comprehensive Quality Conveyor Belt (push) | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605661549 | success |
| Comprehensive Quality Conveyor Belt (PR)   | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605670001 | success |
| Android Build, Test and Release            | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605670221 | success |
| Factory 2 — Single Debug Job (push)        | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605661605 | success |
| Factory 2 — Single Debug Job (PR)          | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605670252 | success |
| AI PR Code Reviewers (Dual Fallback)       | https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38605670510 | success |

The Android workflow's three jobs report `Build, unit tests and lint: success`,
`Emulator instrumented tests and install smoke test: success`, `Publish signed release APK: skipped`
(no `keystore.properties` in the CI environment, so no release APK is claimed). The build job's
evidence annotation for this commit:

```
apk=…/shell/android_project/app/build/outputs/apk/debug/app-debug.apk
size_bytes=5795661
sha256=a784c44dcba2ac90359b3614b507209bfdc6c1b62e4313229087f8d5f060d813
package=com.savage.soulbrowser versionName=3.0.0 versionCode=5
min_sdk=26 target_sdk=35
permissions=['android.permission.ACCESS_NETWORK_STATE', 'android.permission.INTERNET',
             'com.savage.soulbrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']
signed v1=True v2=True v3=True
```

One limit on that evidence: `gh run view --log` cannot be read from this sandbox — the raw log
download fails with `EOF` from GitHub's log receiver — so the emulator leg's own lines (including the
pre-flight's) cannot be quoted here. The emulator job emitted no pre-flight warning annotation, which
is what the stage does when the screen did come up awake and focused; the conclusions above are what
was retrievable, and nothing more is claimed.

## 3. Acceptance criteria covered

| Criterion | What it asserts                                                                                                                 | Test                                                                                                                                       | Evidence               |
| --------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------- |
| AC-060    | GitHub capability is inside the seal, exercised by the environment every run; the problems it was brought in for carry evidence | `GitHubToolTest` (11 tests), `test_the_seal_carries_the_mcp_and_ai_groups_too`, `test_the_stage_wakes_the_screen_before_it_runs_the_suite` | §2.1, §2.1b–§2.3, §2.7 |
| AC-061    | The belt runs the sealed set first, with the GitHub tooling inside it, offline                                                  | `scripts/conveyor_belt.js` station 0, `test_sealed_factory_runtime.py`                                                                     | §2.5                   |

The criterion text is in `docs/ACCEPTANCE_CRITERIA.md` (AC-060, AC-061).

## 4. What is not claimed

- **Seven MCP servers still refuse to start here**, each with the machine's own words recorded in
  `launch_note`: figma-context-mcp (`Either FIGMA_API_KEY or FIGMA_OAUTH_TOKEN is required`),
  context-mode (exits 1 after an npm deprecation warning — no stdio server), dbt-mcp (needs Python
  ≥3.12,<3.14, which no reachable host can supply here — see §2.2), unity-mcp (`mcp-for-unity` is not in a package registry
  and the server needs a running Unity Editor), mcp-proxy (a bridge, not a tool server),
  `awslabs.core-mcp-server` (yanked upstream), github-mcp-server (a Go binary this sandbox cannot
  fetch — which is part of why the GitHub capability was written as native tools instead).
  `quickstart-resources`, `awesome-mcp-servers`, `mcp-for-beginners` and `mcp-chrome` are collections
  and examples with no one-line launch, and are catalogued as such.
- **The GitHub data tools are declared to the researcher role but only exercised automatically as far
  as a probe.** Calling them for data needs a model driving the agent (or a person running
  `runtime/cli.py call github.*`); what every run does by itself is record whether the API is
  reachable. Of the seven, six are covered by `GitHubToolTest` against a local server and by live calls
  recorded in §2.1, and the seventh runs in every swarm pass.
- **`mcp-atlassian` is counted as verified with 0 tools.** It completed `initialize` and answered
  `tools/list` with an empty list. The handshake is what was measured, so the count keeps it; it is
  named here so the 16-server figure cannot be read as 16 servers' worth of usable tools.
- **303 is the number of tools the handshakes observed**, not the number bound into a run. A sealed
  run on this machine binds none of them by default (`0 bound from MCP`) and still passes every check.
- **14 of the 42 catalogued servers are not installed here** (their `npx`/`uvx` packages are absent or
  the machine's Python cannot run them) and 10 of the installed entries have no verified launch
  command. They are catalogued, named and reasoned about; they are not described as running.
- **The emulator pre-flight is hardening, not a demonstrated fix.** Run `226b71c` was not reproduced
  and the later runs passed without the pre-flight, so what is claimed is what the stage now checks
  before the suite — not that the change is what makes the suite pass.
- **The 39 blueprint-only factories still have no content in the seal**, and 2 carried slices still
  define nothing this runtime reads (`github-mcp-server`, `awesome-mcp-servers`); the run counts them
  and names them instead of counting them as workers.
- **No APK is claimed by this round.** It changes the sealed component, its tooling and the emulator
  stage, not the Android app. CI's own APK and emulator evidence is in
  `artifacts/reports/ci-evidence-804571e.md`.
- **The seal root is not content-only.** It hashes the manifest, which carries `sealed_at`; the
  payload hashes inside it are content-only and were identical across two consecutive seals of this
  tree. So a re-seal is a no-op for the sealed content and a one-line change for the manifest.
- _*The github.* tools need the network to answer._* Offline they refuse with the reason
  (`GitHub is not reachable from here: …`), which is what the ninth test asserts; nothing here claims
  GitHub data is available on an air-gapped machine.

## 5. Reproducing this round

```
python3 tools/factory/seal_factories.py seal
python3 tools/factory/seal_factories.py verify
python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0 --env-limit 200
python3 -m unittest discover -s shell/tests
python3 tools/anti_deception_auditor.py && python3 tools/duplication_auditor.py
node scripts/conveyor_belt.js
bash factory-2/scripts/run_all.sh
npm run lint && npm run format:check && npm run typecheck && npm run test
```
