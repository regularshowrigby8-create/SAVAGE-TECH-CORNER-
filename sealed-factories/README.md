# Sealed factory set

One component, sealed. 171 imported software factories - 126
of them showing AI-worker evidence in the slice sealed here, the other 19 design-stage factories -
vendored as bounded slices, hash-sealed, and run as station 0 of this repository's pipeline before
anything else.

## What is in here

| Path | What it holds |
| --- | --- |
| `SEAL.json` | Every factory: repository, commit, licence, payload hash, redistribution basis |
| `SEAL.sha256` | The hash of `SEAL.json`, so the manifest cannot be edited quietly |
| `payloads/<id>.tar.gz` | One deterministic archive per factory (fixed mtime, mode, order) |
| `vendor/<id>.json` | The vendoring record: files, sizes, hashes. No content for blueprint-only factories |
| `licenses/` | 149 licence texts, one per factory that publishes one |
| `ATTRIBUTION.md` | Who each factory is, and the basis it is included on |
| `manifest/RUN-REPORT.json` | What the last run did (written at run time, not committed) |
| `runs/<timestamp>/<id>/` | The artefacts each factory produced: plan, prompt, model answer |

## What it does when the pipeline runs

```
python3 tools/factory/sealed_factory_runtime.py verify   # recompute every payload hash
python3 tools/factory/sealed_factory_runtime.py status    # seal + which models are reachable
python3 tools/factory/sealed_factory_runtime.py run       # run every factory as a stage
```

`verify` is the gate: one mismatched payload stops the pipeline. `run` reads each factory's own
stages, workers, checks, prompts and published sections from its slice, sends the composed brief to
the best reachable model (Ollama / LM Studio / vLLM on their default ports, or a provider key that is
already in the environment - nothing is configured, installed, or written to disk), and records what
happened. No model reachable means the factories still run from their own definitions and the report
says so; nothing is invented for a model that did not answer.

## Removing it

Deleting this directory removes the whole component. Nothing in `shell/`, `factories/`, `factory-2/`
or `factory-3/` reads it; station 0 of `scripts/conveyor_belt.js` reports

    sealed component ABSENT - the belt continues with this repository's own factories

and the belt passes exactly as before. The full removal list - this directory, 15
tooling files, 8 test files, 2 workflows and one document - is recorded in
`SEAL.json` under `removal`, and `shell/tests/test_sealed_factory_runtime.py` proves that list matches
the files that exist, including a test that runs the runtime in an empty tree and checks that absence
is reported, exits zero, and creates nothing.

The component's design, its order in the pipeline, and what each part does is written up in
`docs/factory/MULTISTAGE_FACTORY.md`. Attribution, per factory, is in `ATTRIBUTION.md`.
