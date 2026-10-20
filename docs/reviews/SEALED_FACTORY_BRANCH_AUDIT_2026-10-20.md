# Sealed factory (branch `sealed-factory`) — audit, refinement and tests

Date: 2026-10-20
Branch audited: `sealed-factory` at `ff51fbde41d86b5ef317d6b8215f3d359104e14f`
Subject: the 171-record sealed component (`sealed-factories/`), its runtime, its growth/publish tooling and
its tests.
Method: branch tarball unpacked into `/tmp`; Python 3.11.2; no real provider key was used. Provider
calls were exercised with fake keys and a closed proxy; GitHub calls were counted at the socket layer
with a `sys.addaudithook` recorder.

The hardened tree is delivered as
`docs/reviews/sealed-factory/0001-fix-sealed-factory-one-network-policy-a-publish-gate.patch`
(applies cleanly to `ff51fbde`; 113,564 bytes, 23 files, +1422/−86, sha256
`71efd5c4…`), with the reproducer `docs/reviews/sealed-factory/verify.sh`.

## Verdict

The seal itself is sound: 171 payload hashes recompute, the licence wall holds, self-check is computed
rather than asserted, and the runtime does not invent model output. The **network policy and the
publish claims were not sound**:

1. **`SEALED_NO_NETWORK=1` did not stop model calls.** With a provider key in the environment, the
   sealed runtime POSTed to `https://api.openai.com/v1/chat/completions` while the air-gap switch was
   on. Three calls were made in the test run.
2. **"Plan mode, no keys" was not offline.** The researcher role's offline step calls `github.probe`,
   which issues a real request to `https://api.github.com/rate_limit`. A plain `cli.py run --model-calls 0`
   made one.
3. **"Passed its own tests" was not true of the published tree.** The publish gate ran 3 of the 10
   test files. Running the full suite on the published branch gave **14 failures/errors**: the seal
   omits `tools/factory/discover_factories.py`, `plan_import.json` and `repositories.json` while
   keeping the tests that read them, and four host-repository tests assume files the component-only
   branch does not carry.
4. **The model-call cap could be ignored.** The environment hard-coded `model_calls=4 if gateway_url
   else 0`, so `--max-model-calls 0` did not mean zero when a gateway was configured; the CLI default
   was 4.
5. **A run made with `--no-env` poisoned the environment tests.** The runtime records
   `environment: null` on purpose for that mode, but the tests keyed off the file existing, so the
   next suite run errored with `TypeError: 'NoneType' object is not subscriptable` and failed with
   "the sealed run must report its environment" — in a tree where nothing was wrong.
6. **Three documentation claims were false or impossible**: `cli.py run --max-model-calls 0` is an
   unrecognised argument (the flag is `--model-calls`); the README's "171 factories … 126 … the other 19"
   does not add up (171 − 126 = 45); and the publish judge printed `checks_passed/checks_passed` as
   its denominator and omitted skipped steps from the evidence line it puts in the commit message.

After the patch: the suite passes with the policy off and with `SEALED_NO_NETWORK=1` (211 tests, 6
skips on a fresh tree; 214 tests, 5 skips once a run report exists); the air-gap run makes no socket
call at all; a remote provider is contacted only with `--allow-network remote`; the
snapshot run and the publish judge pass (193/193 checks, 1 step skipped by policy); self-check is
`FUNCTIONAL`; the seal verifies.

---

## 1. Real models and network calls

### 1.1 The air-gap switch stopped two of four exits

`SEALED_NO_NETWORK` was read in exactly one place, `sealed-factories/runtime/toolkit.py` (`network_disabled()`,
used by `net.*`, `github.*` and the self-check repairs). It was **not** read by:

- `tools/factory/sealed_factory_runtime.py` — `detect_capabilities()` and `call_model()`;
- `sealed-factories/runtime/gateway.py` — `available()`, `route()`, `forward()`;
- `sealed-factories/runtime/swarm.py` — `_gateway_ready()`.

Reproduced on the published tree, with fake keys and a closed proxy:

```bash
SEALED_NO_NETWORK=1 OPENAI_API_KEY=sk-… ANTHROPIC_API_KEY=sk-ant-… \
  python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 3 --no-env
```

Audit-hook result:

```
3  POST https://api.openai.com/v1/chat/completions
```

The report said `0 factories ran against a model … 3 model calls failed`; it did not say that the
switch had been ignored.

### 1.2 The "offline" swarm called GitHub

`agents.py` declares the researcher's offline step as `github.probe`, and the toolkit's probe performs
`GET {SEALED_GITHUB_API}/rate_limit` when it is not disabled. Reproduced:

```bash
GITHUB_TOKEN=ghp_FAKE… python3 sealed-factories/runtime/cli.py run --limit 30 --model-calls 0
```

```
1  GET https://api.github.com/rate_limit
```

That is the documented "plan mode, no keys" path. It used the ambient `GITHUB_TOKEN`; with a dead
proxy the step failed and the run exited 1 (`29/30 checks`).

### 1.3 Model calls were on by default, and the cap could be bypassed

- `tools/factory/sealed_factory_runtime.py --max-model-calls` defaulted to **4**;
  `sealed-factories/runtime/swarm.py DEFAULT_MODEL_CALLS` was **8**.
- `environment()` ignored the caller's cap and passed `model_calls=4 if gateway_url else 0`.
- `gateway.collect_keys()` reads nine provider variables and forwards with them; there was no opt-in
  and no record of the policy in `/healthz`.

### 1.4 What is genuinely offline

- No model weights are vendored. `models/free-models.json` in the host repository is metadata.
- `call_model()` writes `model-output.md` only when a provider answered; `_extract_text({})` returns
  `""`; a refused provider is recorded as `MODEL-FAILED` with the provider's own words. This is
  correct, and the existing test for it passes.
- The seal is integrity, not fitness, and `selfcheck.py` says so; its findings and repairs are
  computed, re-surveyed after repair, and it refuses network repairs when `SEALED_NO_NETWORK=1`.

---

## 2. Claims that were not true

| # | Claim | Evidence |
|---|---|---|
| 2.1 | Commit: "…passed its own tests…" | `publish_sealed_factory.sh` gate 4 ran only `test_sealed_factory_runtime.py`, `test_sealed_selfcheck.py`, `test_license_manager.py`. On the published tree the full suite: **191 tests, 1 failure, 5 errors** (without the air-gap variable) and **188 tests, 4 failures, 10 errors** (with it). |
| 2.2 | The component is self-contained | `shell/tests/test_factory_discovery.py` imports `discover_factories`, which is not in `SEAL.json`'s removal list and not in the snapshot; `plan_import.json` and `repositories.json` are missing too. |
| 2.3 | "171 factories — 126 … the other 19 design-stage factories" (`sealed-factories/README.md`) | 171 − 126 = 45. The text is generated by `seal_factories.write_readme()`. |
| 2.4 | `cli.py run --max-model-calls 0` (`SEALED-COMPONENT.md`, README "Running it") | `cli.py: error: unrecognized arguments: --max-model-calls 0`. The CLI flag is `--model-calls`; `--max-model-calls` belongs to the runtime script. |
| 2.5 | Publish judge: "`{passed}/{passed}` checks passed" | `published_environment_judge.py` formatted the denominator as `checks_passed`, so it was always "N/N"; it also did not report `steps_skipped`. |
| 2.6 | `--max-model-calls 0` means no model calls | `environment()` hard-coded 4 when a gateway URL was set. |
| 2.7 | Runtime docstring: "The sealed component holds 72 imported factories" | The seal holds 171. |
| 2.8 | A stale `--no-env` report is a report the tests can judge | `test_sealed_environment.py` read `RUN-REPORT.json` whenever the file existed; a `--no-env` run writes `environment: null`, so `SealedEnvironmentReportTest` failed one test and errored another with `TypeError` instead of saying what happened. |
| 2.9 | Host tests are part of the component | `test_imported_factories` reads `docs/factory/IMPORTED_FACTORY_CATALOG.md`; `test_factory_growth` reads `commitlint.config.js`; `test_sealed_factory_runtime` reads `scripts/conveyor_belt.js` and asserts `factories/` survives removal. None exist on the component-only branch. |

### Commit history (`sealed-factory`)

Nine commits, all `chore(sealed-factory): publish the sealed component`, all produced by the publish
script. The branch is an **orphan** (no common ancestor with `main`), so GitHub cannot open a PR from
it to `main`, and the script explicitly refuses `main`/`master`/`production`. There is no review
commit on the branch: every change arrives already published.

---

## 3. What the patch changes

`0001-fix-sealed-factory-one-network-policy-a-publish-gate.patch` (113,564 bytes, 23 files,
+1422/−86, sha256 `71efd5c4aa7f5374175b626efc6a22ebd51f0feb6f2f747f3ed097d59c165e99`)

### Network

- **`sealed-factories/runtime/netpolicy.py`** (new): one policy, read at call time.
  `SEALED_NETWORK=off` (default) / `local` / `remote`; `SEALED_NO_NETWORK=1` always forces `off`;
  `kind_for_url()` and `kind_for_tool()`; `describe()` for reports.
- **Toolkit**: `network_disabled()` delegates to the policy; `net.fetch`, `net.http`, the seven
  `github.*` tools and `model.ask` refuse with the policy's reason (local gateway vs remote).
- **Gateway**: `available()` requires permission as well as reachability; `forward()` refuses a
  provider the policy forbids; `/healthz` carries the policy; startup prints it.
- **Swarm**: `_gateway_ready()` obeys the policy; a network step or check the policy forbids is
  recorded as **skipped** with the reason, not as a failure and not as a success; `steps_skipped`
  and `checks_skipped` are counted and reported.
- **Sealed runtime**: `detect_capabilities()` neither probes local ports nor advertises keyed
  providers when the policy forbids it; `call_model()` refuses before dialling; the run prints the
  policy; the environment uses the caller's `model_calls` cap (no hard-coded 4).
- **Defaults**: `--max-model-calls` = 0 and `DEFAULT_MODEL_CALLS` = 0. Model use is opt-in.
- **CLIs**: `--allow-network off|local|remote` on both `cli.py` and `sealed_factory_runtime.py`
  (global on the former, so it works before or after the subcommand).

### Publish integrity

- `publish_sealed_factory.sh` gate 4 now runs **the whole suite** (`unittest discover -s shell/tests`)
  under `SEALED_NETWORK=off`; gate 6b runs the snapshot under the same policy.
- The three growth files the tests need are now part of the component and of `SEAL.json`'s removal
  record: `discover_factories.py`, `plan_import.json`, `repositories.json`. `test_network_policy.py`
  is listed for removal too.
- `SealedEnvironmentReportTest` skips with a named reason when the last report has no `environment`
  (a `--no-env` run), instead of failing on a `None`.
- Host-repository assertions skip with an explicit reason on the component-only tree (catalog,
  commitlint, belt, `factories/` survival). They still run where those files exist.
- `published_environment_judge.py`: real denominator, and the evidence line reports skipped steps.
- README arithmetic fixed at the generator (`seal_factories.write_readme()`): `171 − 126 = 45`.
- Docs use the right flag (`--model-calls` for `cli.py`) and describe the policy; the runtime docstring
  no longer says 72 factories.
- Seal refreshed with the component's own sealer; payload hashes are unchanged.

---

## 4. Verification after the patch

All commands were run on a fresh copy of `ff51fbde` with the patch applied
(`docs/reviews/sealed-factory/verify.sh`, `--baseline` also reproduces the defect).

| Check | Result |
|---|---|
| `git apply --check` on the branch | clean |
| `cli.py verify` | `[SEAL] OK … 171 factories, 2555593 payload bytes, 149 licence texts` |
| `cli.py selfcheck --require-functional` | `FUNCTIONAL - 0 blocking finding(s), 38 named state(s)` |
| Full suite, `SEALED_NETWORK=off`, no run report present | **OK** — 211 tests, 6 skips (5 host-repo + the environment report) |
| Full suite, `SEALED_NO_NETWORK=1`, after the previous run wrote a report | **OK** — 214 tests, 5 skips |
| Snapshot run + `published_environment_judge.py` | `PUBLISHED ENVIRONMENT PASS` — 193/193 checks, 196 steps ok, 1 step skipped by policy, 0 model calls |
| Seal after the run | unchanged; `verify` OK |

### Network matrix (audit hook, fake keys, closed proxy)

The audit hook records the URL a request object is built for, so these are the destinations the code
selected, not successes: every one of them is sent through a closed proxy.

| Scenario | Before | After |
|---|---|---|
| `SEALED_NO_NETWORK=1`, OpenAI + Anthropic keys, `--max-model-calls 3` | 3 requests to `api.openai.com/v1/chat/completions` | **no connection**, 0 model calls |
| default policy, OpenAI key present | 3 POSTs (from the default cap of 4) | **no connection**, 0 model calls |
| `cli.py run` (plan mode) with `GITHUB_TOKEN` | 1 GET `api.github.com/rate_limit` | **no connection**, 1 step skipped by policy |
| `--allow-network remote --max-model-calls 2` | n/a (always dialled) | exactly 2 requests to `api.openai.com` (recorded as `MODEL-FAILED` behind the closed proxy) |
| `--allow-network local` | n/a | localhost only |

---

## 5. How to land it

The session that produced this work is pinned to `arena/56818369-savage-tech-corner` and cannot push
to another branch, so the change is delivered as a patch rather than a commit on `sealed-factory`.

```bash
git worktree add /tmp/sealed sealed-factory
cd /tmp/sealed
git apply --check /path/to/0001-fix-sealed-factory-one-network-policy-a-publish-gate.patch
git apply /path/to/0001-fix-sealed-factory-one-network-policy-a-publish-gate.patch
python3 sealed-factories/runtime/cli.py verify
SEALED_NETWORK=off python3 -m unittest discover -s shell/tests
# then publish through the component's own gate, which now runs all of it:
bash tools/ci/publish_sealed_factory.sh
```

`verify.sh` performs the download, the apply, the seal checks, both suite runs, the snapshot run and
the network audit without touching this repository.

## 6. Limitations

- No real provider key was used. Provider behaviour is exercised against local stubs and audit hooks.
  The remote opt-in was proved by observing the URL selected and the attempt, not by receiving a
  completion.
- The 5 host-repository skips mean those assertions are not proved on the component-only branch; they
  run where `factories/`, `scripts/conveyor_belt.js`, `commitlint.config.js` and the catalog exist.
- The seal remains integrity, not fitness. `selfcheck` is a real fitness survey, but the "sealed
  holdout" idea in the separate v4 zip is still not an independent behavioural holdout.
- Three `SealedEnvironmentReportTest` tests unskip only when a `RUN-REPORT.json` with an
  `environment` block exists; on a fresh tree they skip, and a `--no-env` report now skips with a
  reason instead of erroring. The suite is therefore 211 tests on a fresh tree and 214 after a run.
