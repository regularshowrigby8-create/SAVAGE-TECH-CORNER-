# Nova — Python tooling

56 scripts over one tested core. Standard library only, Python 3.11+.

## Why it is built this way

- **One core, thin scripts.** Logic lives in `nova/` where it is unit-tested;
  each script in `scripts/` is a short shell over it. 56 separate programs would
  drift; 56 thin entry points over 8 tested modules do not.
- **No dependencies.** It runs in a bare CI image with no install step and no
  supply chain to trust.
- **Secrets never in the repo.** `nova.toml` is git-ignored; tokens come from the
  environment; `ops_env` prints the resolved config with secrets masked.

## Quick start

```bash
cp nova.toml.example nova.toml     # then fill in your tokens
export GITHUB_TOKEN=ghp_...        # or put it in nova.toml
export AI_API_KEY=sk-...           # optional

python3 scripts/ops_doctor.py      # is the environment ready?
python3 scripts/ops_inventory.py   # what can this do?
make test                          # 34 unit tests
make scripts                       # execute all 56
```

## The interesting ones

| Script | What it does |
|---|---|
| `github_collect.py` | One resumable GitHub search pass; updates the knowledge base |
| `github_watch.py` | Runs the collector forever on an interval |
| `improve_plan.py` | Dry-run self-improvement; changes nothing |
| `improve_apply.py` | Applies only test-green proposals; needs `--apply` |
| `improve_rollback.py` | Restores the last snapshot |
| `catalogue_health.py` | Probes each catalogue feed and reports reachability |
| `qa_secrets.py` | Fails if a credential is ever committed |
| `ai_models.py` | Shows what each model alias resolves to |

## The safety model

The self-improvement loop is a proposal pipeline, not an auto-committer:

1. It may only write files with an allowed suffix, and **never** `nova.toml`,
   `.env`, `.git/`, `.github/`, `models.json`, or `nova/selfimprove.py` itself.
2. Paths are normalised properly — `../x`, `/etc/passwd` and `.github/ci.yml` are
   all rejected. (An earlier version used `lstrip("./")`, which silently stripped
   the leading dot and let `.github/` through. The tests caught it.)
3. Every touched file is snapshotted first and can be restored.
4. Nothing is written unless the tests are green.
5. Nothing is written unless the tree is clean and `--apply` was passed.

## Verified, and not

Run against the sandbox this was built in:

- **46 of 56 scripts produced a real result.**
- 3 AI scripts stop with a clear message because no `AI_API_KEY` is set — that is
  the intended behaviour, not a failure to hide.
- 6 catalogue scripts cannot reach Stremio from that sandbox (TLS is blocked for
  non-allowlisted hosts). Their logic is verified against a captured real
  response; they need a normal network.
- `github_watch.py` loops forever by design, so it is not part of the sweep.
