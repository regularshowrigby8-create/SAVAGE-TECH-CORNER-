# Sealed factory audit — `SAVAGE_FACTORY_v4_DEBUG_FINAL`

Date: 2026-10-20
Artifact: `SAVAGE_FACTORY_v4_DEBUG_FINAL.zip` (standalone `SAVAGE_FACTORY.py`, 3,531 lines) and its
materialised `source/` tree. `SAVAGE_FACTORY_v4.zip` was checked for comparison.
Method: unpacked into throwaway directories under `/tmp`; Python 3.11.2; git 2.39.5; no real API keys
used for the model-behaviour tests (a local OpenAI-compatible stub on `127.0.0.1:11434` was used).
All results below were reproduced locally; each finding names the code path and the command that
shows it.

## Verdict

The package contains **no hallucinated model output baked into the engine** and **no bundled model
weights** — every endpoint is remote, and the offline path is an honest JSON stub. The embedded
base64 bundle is also **byte-identical to the shipped `source/` tree** (41 files, 103,849 bytes),
so the readable sources are trustworthy.

What *is* wrong is the certification layer:

1. **It calls real models, including remote ones, without opt-in.** On this host (and on GitHub
   Actions, where `GITHUB_TOKEN` is always set) a plain `build` POSTs prompts to
   `https://models.github.ai/inference/chat/completions` using the ambient token. With no token it
   still opens TCP connections and HTTP POSTs to `127.0.0.1:11434` (Ollama) on every run.
2. **`SHIP` can be earned by a comment-only edit.** A stub model that returns a valid spec plus a
   patch that appends ` IMPROVED` to a docstring produced `VERDICT: SHIP`, all eight "sealed"
   checks green, on the unchanged generic scaffold. The spec's own acceptance tests are never
   executed (only `spec.py`/`decode.py` reference `acceptance_tests`).
3. **The "sealed holdout" is neither sealed nor independent.** `holdout.seal(spec, scaffold)` hashes
   the public spec and re-runs the same structural checks as the public gates, from source that
   ships in the repository.
4. **`doctor` can report `ALL GREEN` / `SHIP` while nothing real happened**, and the delivered
   package **fails its own `gate`**.

Recommended position: keep the architecture, but make network access explicit and make release
verdicts depend on executable, spec-derived acceptance evidence rather than a byte diff.

---

## A. Real model / network calls (verified)

### A1. Remote inference with the ambient `GITHUB_TOKEN` — no opt-in

`source/savage/llm/client.py`:

```python
ENDPOINTS = [
    ("github-models", "https://models.github.ai/inference", "GITHUB_TOKEN", ...),
    ...
]
key = os.environ.get(env, "")
usable = bool(key) or eid in ("ollama", "lmstudio", "vllm", "llamacpp")
...
with urllib.request.urlopen(req, timeout=self.timeout) as resp:
```

Reproduction (fake token so no real credentials left the box; HTTPS routed to a closed port):

```bash
GITHUB_TOKEN=ghp_FAKE... HTTPS_PROXY=http://127.0.0.1:9 \
  python3 SAVAGE_FACTORY.py --root . build "a csv to json cli" -n 2 --rounds 1
```

Audit-hook log of URLs selected:

```
3  https://models.github.ai/inference/chat/completions
2  http://localhost:11434/v1/chat/completions
```

The prompt, the golden-retrieval context, and the generated spec are all sent. There is no
`--allow-network`, no consent prompt, no per-endpoint log, and nothing in `verdict.json` records
which endpoint or model was used. `GITHUB_TOKEN` is **set in this sandbox** and always set in
GitHub Actions, so `build` / `doctor` / `all` are remote-model calls by default.

### A2. Local endpoints are "usable" even when nothing is listening

`available_endpoints()` marks `ollama`, `lmstudio`, `vllm` and `llamacpp` usable unconditionally.
With no keys, `build -n 2` still made **4 HTTP POSTs to `127.0.0.1:11434`** (spec, two candidates,
verifier tie-break) and **8 TCP connects** (IPv4 + IPv6). Each failure is swallowed and replaced by
the stub.

### A3. Failure is indistinguishable from success

```python
except Exception:
    return self._offline(messages)
```

`_offline()` returns `{"offline": true, ...}`. `spec.py` spots that flag and falls back, but
`sampler.py`, `repair.py` and `verifier.py` do not; no caller records provenance. A run that used
zero models and a run that used a premium model produce the same verdict shape, and
`verdict.json` contains no `endpoint`, `model`, `stub` or `tokens` field.

### A4. Two more unguarded network paths

- `source/savage/mcp/gateway.py::github_models()` — same ambient `GITHUB_TOKEN`, same remote URL,
  called through `gateway.github_models`.
- `gateway.self_test()` is `json.dumps({"ok": True})`. It **cannot fail**, yet `doctor` reports
  `[4] mcp gateway: OK` from it and the docs cite it as a self-test.

### A5. The MCP "deterministic gate server" fabricates passes

`source/savage/mcp/servers/gate.py::_run_gates()`:

```python
checks.append({"name": "build", "ok": True, "note": "delegated"})
checks.append({"name": "tests", "ok": True, "note": "delegated"})
checks.append({"name": "anti_deception", "ok": True})
return {"passed": all(c["ok"] for c in checks), ...}
```

`gate.run` is a gate that always passes, regardless of the path passed to it.

---

## B. Hallucinated / unsupported results (verified)

### B1. `SHIP` for a docstring-only change, with a spec that the artifact does not implement

A local OpenAI-compatible stub returned:

- spec: `Build a todo CLI with add/list/done commands` (+ 3 acceptance tests);
- candidate patch: replace `Pure function core (easy to test).` with
  `Pure function core (easy to test). IMPROVED`.

Result:

```
VERDICT: SHIP
sealed: passed=true (8/8 checks)
artifact/main.py: core(text) -> {"length", "upper", "words"}   # unchanged behaviour
```

Root cause: `build.py` decides `unchanged` by hashing the scaffold files against the candidate, so
any byte change (comment, whitespace, docstring) counts as a product implementation, and
`acceptance_tests` are never executed. `TEMPLATE-ONLY` is therefore only a *zero-diff* detector, not
an implementation detector.

### B2. "Sealed holdout the generator never saw" is not sealed

`holdout.py::seal(spec, scaffold)` derives `sealed_id = sha256(goal + sorted(scaffold.files))` and
the check list is the constant `entrypoint_present, no_hollow, no_secrets, compiles, tests_pass,
tests_deterministic, has_tests, size_bounds`. The generator sees the spec and the scaffold; the
check source ships in the repo. It is a second run of the same public checks, not an independent
behavioural holdout. `TEMPLATE-ONLY` handling is the only thing preventing a SHIP on a pristine
scaffold.

### B3. "Verifier uses a DIFFERENT model" is not enforced

`verifier.rank()` calls `client.chat(..., cls="premium")`, but `LLMClient._resolve()` falls back to
*any* usable endpoint. With only a local endpoint reachable, all four requests in the B1 run went to
the same `qwen2.5-coder:7b` endpoint — including the adversarial verifier. The verifier also sees
`candidate.diff`, which is only `candidate N content_sha256=… variant=True`, not the code.

### B4. `doctor` and the shipped claims

| Claim in package | Observed |
|---|---|
| `doctor` → `VERDICT: ALL GREEN` | On a clean init with no model: `ATTENTION — smoke build gates failed` (rc=1). With a local model present: `ALL GREEN` although smoke built the generic scaffold (B1). |
| `[5] smoke build: OK` | Printed whenever `res.verdict` is truthy — the string of a non-release verdict counts as OK. |
| `TEST_RESULTS.md`: `… gate pass` | `gate` passes only in a temp dir containing factory files. At the package root, `gate` → `VERDICT: FAIL`: `hollow:SAVAGE_FACTORY.py`, `secret:tests/test_hardening.py`, `hollow:tests/test_hardening.py`. |
| `README` §6 "gate → VERDICT: PASS", "all → ALL GREEN" | `all` at the package root exits 1 (doctor ATTENTION, gate FAIL). |

The `gate` false positives come from substring scans over *all* text: prose in `SAVAGE_FACTORY.py`
mentions `raise NotImplementedError`, and `tests/test_hardening.py` deliberately contains a
`github_pat_…` fixture. The scanner does not distinguish docs, fixtures and production code, and
does not exclude the detector's own tests.

### B5. "75 verified tools" is a static label

`TIER` for the 382 tools is hard-coded in `TOOL_CATALOG`. `factory/improver` "promotes"
`provisional → verified` when `shutil.which(binary)` finds a name on `PATH` — no run, no version, no
known-answer test. The 32 MCP servers report binary presence only. `catalog`'s `🟢 verified 75` is a
registry label, not evidence.

### B6. Isolation and artifact contamination (also a correctness issue)

`source/savage/premium/build.py` ends with:

```python
finally:
    for candidate in cands:
        try:
            _worktree.release(candidate.path)
        except Exception:
            pass
```

`_worktree` is **never imported** in `build.py`, so this raises `NameError`, which the bare
`except` swallows. Verified with `git worktree list` after a build: both candidate worktrees are
still registered. Additional confirmed defects:

- candidates nest as `.worktrees/.worktrees/candidate_NN` (double nesting);
- in a git repo each candidate is a **full checkout of the repository**, and the "artifact" for
  `build "hello cli"` was a copy of that checkout (README, `.github/`, `factory/`, `docs/`, …);
- public gates and the sealed scan therefore run over the whole repo, not the scaffold, and
  `size_bounds` (≤ 500 files) will start failing as the repo grows;
- `n=8` (default) with no model runs the same scaffold 8 times; only the `variant=` bool changes.

### B7. Smaller inaccuracies

- `SAVAGE_FACTORY.py` header: "41 files, 96950 bytes of source" — the bundle is 103,849 bytes.
- `README`/`BUILD.md` say `init` is "idempotent, only writes missing or changed files"; in practice
  `init` returns 1 on *any* conflicting file (including the factory's own docs on a re-run after an
  edit), which is safe but not what the docs describe.
- `_HOLLOW` differs between `gates.py` (`return True  # gate`) and `verifier.py`
  (plain `return True`), so ranking and gating disagree about what "hollow" means.
- `repair._auto_fix()` now has no effect (every branch is `continue`), so a failed candidate uses
  all `--rounds` without a deterministic repair.

---

## C. What is actually sound

- Offline path: no bundled weights, no fabricated model text; stub output is labelled `offline`.
- `init` conflict handling is non-destructive (test `test_init_conflict_is_non_destructive` passes).
- `adopt` protects tracked files (`test_adopt_preserves_tracked_delete_match` passes) and is dry-run
  first; `deletes` are root-confined.
- Candidate subprocess env is reduced to `PATH/HOME/LANG/TMPDIR/…` (no API keys), and repair paths are
  root-confined.
- The 5 shipped tests pass (`Ran 5 tests in 1.326s OK`), and the bundle matches `source/`.
- Python compile gate, git `diff --check`, and hollow-CI scan are real checks.

---

## D. Proposed fixes (not yet applied)

Priority order, smallest-first:

1. **Network is opt-in.** Add `--allow-network` (and `SAVAGE_ALLOW_NETWORK=1`) to `build`, `doctor`,
   `all`; default to local-only or stub-only. Never read `GITHUB_TOKEN` implicitly — require
   `SAVAGE_MODEL_ENDPOINT` or `--endpoint`. Remove `gateway.github_models` ambient-token path or put
   it behind the same flag.
2. **Record provenance.** Add `model_provenance` (`endpoint`, `model`, `stub`, `requests`,
   `network_allowed`) to `BuildResult`, `verdict.json`, and the console block. Emit
   `MODEL: none (offline stub)` when no model was used, and make `TEMPLATE-ONLY` the only possible
   verdict without a model.
3. **Verdict requires executable evidence.** Compile `spec.acceptance_tests` into runnable checks
   (with a declared, bounded command form), run them in the candidate workspace, and make `SHIP`
   require them to pass *plus* a functional (non-comment) diff. Otherwise `TEMPLATE-ONLY` /
   `FIX-FIRST`.
4. **Make the holdout honest.** Either derive hidden, behaviour-level tests from the spec with a
   different model and keep them outside the candidate, or rename it to what it is
   (`secondary structural checks`) and stop calling it sealed.
5. **Fix isolation.** Import `worktree` in `build.py`, fix the double nesting, release in `finally`,
   and scaffold only the project files into the candidate (no full-repo checkout).
6. **Fix the gate's self-scan.** Scan only code tokens (strip comments/strings via `tokenize`), skip
   documented fixtures, and make `gate` exit clean at the delivery root.
7. **Make `gateway.self_test()` a real JSON-RPC round trip**, and make the MCP gate server call the
   real `run_gates` instead of returning `True`.
8. **Tests to add** (all offline):
   - no network syscalls when network is not enabled (audit hook);
   - `doctor` never prints `ALL GREEN` when the smoke build is template-only;
   - comment-only patch ⇒ not `SHIP`;
   - `verdict.json` contains provenance and `stub=true` offline;
   - worktree released and nesting correct after build;
   - `gate` passes on the package root and fails on a genuinely hollow file.
