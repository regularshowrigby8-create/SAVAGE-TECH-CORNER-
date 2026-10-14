# Round 12 evidence — the environment inside the seal

Scope: the round-12 brief — import and make functional the MCP servers inside the sealed
environment, replace weak points, add multi-agent implementation and AI-focused factories, make the
component self-sufficient and portable, give it one gateway for every model and key, require no user
intervention, and seal it only once it is functional.

Everything below was produced by commands in this checkout on 2026-10-14. Nothing here is copied from
another run, and nothing is projected: where a thing could not be done on this machine, the failure
and its cause are recorded instead.

---

## 1. What the sealed component contains now

| Part                 | State                                                                                                                                      | Where                                              |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------- |
| Imported factories   | 72 records, licence text kept, content where the licence permits                                                                           | `sealed-factories/vendor/`                         |
| MCP tool servers     | 42 catalogued, 28 with a published launch command, 7 driven through a real handshake (52 tools)                                            | `sealed-factories/mcp-vendor/`, `mcp-servers.json` |
| AI-focused factories | 23 (AutoGen, Semantic Kernel, CrewAI, LangGraph, LangChain, MetaGPT, OpenHands, SWE-agent, pydantic-ai, agno, llama_index, haystack, …)    | `sealed-factories/vendor-ai/`                      |
| Runtime              | 9 modules: toolkit (40 tools), MCP tool server, gateway, agents, language packs, swarm, MCP link, sealcheck, CLI                           | `sealed-factories/runtime/`                        |
| Licences carried     | 115 licence texts                                                                                                                          | `sealed-factories/licenses/`                       |
| Seal                 | 137 factories, 1,849,160 payload bytes, 10 component files pinned, root `efd960c11d0ff4500a316d8f1ee6e521b70743cda00bf5249f0cf71de83b0a10` | `sealed-factories/SEAL.json`                       |

Groups, as the manifest itself counts them: 72 imported factories, 42 MCP tool servers, 23
AI-focused factories. 98 records carry redistributed content; 39 are blueprint-only: the sealer
carries the content of a factory only when its licence is permissive and its licence text was
published (`content_may_be_copied` in `tools/factory/seal_factories.py`), so the other 39 travel as
verifiable structure - file names, sizes and hashes - with their licence text and nothing else.

## 2. Evidence, verbatim

### 2.1 The seal verifies, in this checkout and in a foreign one

```
$ python3 tools/factory/seal_factories.py seal
sealed 137 factories -> sealed-factories/SEAL.json
component files pinned=10
seal root sha256=efd960c11d0ff4500a316d8f1ee6e521b70743cda00bf5249f0cf71de83b0a10
payload bytes=1849160 licences=115

$ python3 tools/factory/sealed_factory_runtime.py verify
[SEAL] OK 1.0.0: 137 factories, 1849160 payload bytes, 115 licence texts, root efd960c11d0ff450…
```

The component's own verifier, run from a copy of `sealed-factories/` inside a different repository
(`/tmp/foreign2/component`, cwd `/tmp/foreign2/other-repo`, nothing of this repository on `sys.path`):

```
$ python3 /tmp/foreign2/component/runtime/cli.py verify
[SEAL] OK 1.0.0: 137 factories, 1849160 payload bytes, 115 licence texts, root efd960c11d0ff450…

$ python3 /tmp/foreign2/component/runtime/cli.py run --root /tmp/foreign2/other-repo --limit 30 --model-calls 0
[swarm] 159 roles, plan of 159 agent instances, executed 30 in 0.1s
[swarm] 0 ran with a model, 30 offline, 33 tool steps ok, 0 failed, 30/30 checks passed
[swarm] report: .sealed/swarm/20261014T121010Z/RUN.json
```

The copy wrote its artefacts and report into `.sealed/` of the repository it was pointed at, and
`--root` after the subcommand is accepted, which is how anyone would type it.

### 2.2 The sealed run on this machine

```
$ python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0 --env-limit 200
[SEALED] 137 factories sealed (98 with redistributed content, 39 blueprint-only)
[SEALED] models: local=[] keyed=[]
[SEALED] environment: 40 tools (40 native, 0 bound from MCP), MCP catalogue 42 servers (2 verified here, 18 bindable on this machine), 159 agent roles, 7 language packs available (go, rust, java, kotlin, csharp, ruby missing)
[SEALED] swarm: 159 agent instances of a 159-instance plan in 33.39s, 162 tool steps, 159/159 checks passed
[SEALED] 0 factories ran against a model, 96 ran in plan mode, 41 had no definitions in their sealed slice, 0 model calls failed, 0 unreadable
[SEALED] 3 slice(s) carry content but define nothing this runtime reads as stages, workers or tools
[SEALED] MCP servers: 42 catalogued, 52 tools observed by handshake
[SEALED] definitions read from the sealed set: 496 stages, 502 workers, 159 checks, 870 prompt assets, 4098 sections of prose their definition files publish
[SEALED] report: sealed-factories/manifest/RUN-REPORT.json
```

`local=[]` and `keyed=[]` are the point: no model server and no API key were configured, and the run
still executed every agent instance and passed every check.

### 2.3 MCP servers

```
$ python3 sealed-factories/runtime/cli.py mcp-probe
catalogue 42, installed 18, not_installed 24, probed 4
  servers            13 tools   (handshake answered)
  context7            2 tools   (handshake answered)
  playwright-mcp     25 tools   (handshake answered)
  mcp-toolbox                  "the server exited before answering"
```

Seven servers were handshake-verified during the import round (`fetch`, `git`, `server-everything`,
`server-filesystem`, `server-memory`, `server-sequential-thinking`, `time` — 52 tools); the evidence
for each is kept in `tools/factory/imported_mcp_servers.json` (`verified_by_handshake`,
`handshake_evidence`). The catalogue records 28 servers with a published launch command; the other 14
are catalogued with the reason they have none. Every tool the seven publish also exists as a native
tool in `toolkit.py`, so the environment keeps its full capability on a machine with no npm and no
pip.

### 2.4 The gateway

```
$ OPENAI_API_KEY=… ANTHROPIC_API_KEY=… python3 sealed-factories/runtime/cli.py gateway --port 8791
[GATEWAY] listening on http://127.0.0.1:8791
[GATEWAY] reachable models: ['openai', 'anthropic']

$ curl -s http://127.0.0.1:8791/healthz
{"service": "sealed-factory-gateway", "reachable": ["openai", "anthropic"], "local": [], "keyed": ["openai", "anthropic"], "key_counts": {"anthropic": 1, "openai": 1}, "usage": {}, "models_served": 2, "checked_at": "2026-10-14T12:10:19Z"}

$ curl -s http://127.0.0.1:8791/v1/keys
{"providers": {"anthropic": {"keys": 1}, "openai": {"keys": 1}}, "values_exposed": false}
```

This sandbox reaches PyPI, npm and GitHub only, so the keyed providers' own hosts are unreachable
here. The gateway answered that honestly, with each provider's own error text, rather than inventing
a completion:

```
$ curl -s -X POST http://127.0.0.1:8791/v1/chat/completions -d '{"model":"auto", …}'
HTTP 502
{"error": {"message": "every reachable provider refused: [{\"provider\": \"openai\", \"status\": 502, \"error\": \"openai could not be reached: <urlopen error TLS/SSL connection has been closed (EOF) (_ssl.c:992)>\"}, …]", "type": "sealed_gateway_error"}}
```

With no key and no local server, the same endpoint answers **503** naming every endpoint it looked
for (`llamacpp:8080, lmstudio:1234, ollama:11434, vllm:8000`); against a local stub implementing a
provider's own API it routes and answers, and a stub that accepts the connection and never replies is
cut off at `SEALED_GATEWAY_TIMEOUT` (60 s) and reported as **504**. Those three paths are asserted in
`shell/tests/test_sealed_environment.py` (`GatewayTest`).

### 2.5 Plug and work: the belt

```
$ node scripts/conveyor_belt.js
[STATION 0] Sealed Imported Factory Set…
[SEALED] … 159 agent instances of a 159-instance plan in 34.75s, 162 tool steps, 159/159 checks passed
[STATION 3: PASS] Accessibility & Performance Audit (axe-core / Lighthouse) satisfied.
[STATION 5: PASS] Bundle Footprint & Sizing Limits satisfied.
[STATION 6: PASS] AI Reviewer & Middleman Broker Hand-Off satisfied.
   CONVEYOR BELT COMPLETE: SEALED SET RAN + ALL 6 CONNECTED STAGES PASSED
```

No configuration step, no prompt and no argument: station 0 verifies the seal, probes the machine's
model endpoints, reads whatever keys are in the environment, builds the environment and runs it. If
the environment is not functional — fewer than 30 tools, fewer than 20 roles, or any failed check or
step — station 0 fails rather than sealing it.

### 2.6 The gate suite

```
$ python3 -m unittest discover -s shell/tests
Ran 176 tests in 170.044s

OK

$ python3 tools/anti_deception_auditor.py
[PASS] Repository passed all authenticity, anti-hollowness, and provenance invariants.

$ python3 tools/duplication_auditor.py
[DUPLICATION-AUDITOR] PASS: All mirrors synchronized without unapproved drift.
```

37 of those tests are new this round (`shell/tests/test_sealed_environment.py`), including five that
run the component in a foreign repository.

## 3. Acceptance criteria covered

| Criterion | What it asserts                                                      | Test                                                                                                                                                  | Evidence                           |
| --------- | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| AC-049    | MCP servers imported and functional inside the seal                  | `test_sealed_environment.py` (MCP stdio protocol, registry), `test_imported_factories.py`                                                             | §2.3                               |
| AC-050    | One gateway for every model and every key                            | `GatewayTest` (stub routing, 503, key rotation, redaction, Anthropic wire, timeout)                                                                   | §2.4                               |
| AC-051    | Works with no model, no keys, no network                             | `test_a_run_with_no_model_says_so_and_never_invents_one`, `test_model_ask_refuses_without_a_gateway`, `test_offline_network_tools_fail_with_a_reason` | §2.2                               |
| AC-052    | A million agents planned, the limit executed exactly, the rest named | `SwarmTest`, `cli.py capacity`                                                                                                                        | §2.2                               |
| AC-053    | Multi-agent coverage, AI-focused factories among the roles           | `test_the_environment_is_reported_with_every_part`, `test_the_seal_carries_the_mcp_and_ai_groups_too`                                                 | §2.1, §2.2                         |
| AC-054    | "Any language" as far as the machine honestly goes                   | `LanguagePackTest`, `init --check`                                                                                                                    | §2.1 (7 available, 8 missing)      |
| AC-055    | A copy works in another repository                                   | `PortabilityTest` (5 tests)                                                                                                                           | §2.1                               |
| AC-056    | The seal covers the code that runs it                                | `test_the_seal_pins_the_code_that_runs_it`                                                                                                            | §2.1 (`component files pinned=10`) |
| AC-057    | Plug and work, no user intervention                                  | `conveyor_belt.js` station 0, removal tests                                                                                                           | §2.5                               |

The full criterion text is in `docs/ACCEPTANCE_CRITERIA.md` (AC-049 … AC-057).

## 4. Weak points found and replaced this round

| Weak point                                                                            | What it was                                                                                             | What it is now                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cli.py verify` imported `tools/factory/seal_factories.py`                            | A copy of the component outside this repository could not verify itself                                 | `runtime/sealcheck.py`, the component's own verifier; the sealer stays available behind `--repo-verifier` and a test pins that both agree on the same bytes                                                                     |
| Global options only before the subcommand                                             | `cli.py run --root x` failed with an argparse error                                                     | both orders accepted (`_normalise_argv`)                                                                                                                                                                                        |
| The seal covered payloads only                                                        | A runtime module could be swapped without the seal noticing                                             | every runtime module and `mcp-servers.json` are hashed into the manifest; a changed **or added** module fails verification                                                                                                      |
| Two runs in the same second shared a run directory                                    | The second run's artefacts inflated the first run's directory and its `RUN.json` replaced the first one | each run gets its own directory (`-2`, `-3`, … when the timestamp is taken)                                                                                                                                                     |
| MCP servers whose slice is a Go/Bun tree recorded as if they had structure            | Reported as `NO-STRUCTURE` with no explanation                                                          | an MCP server's tool list, once a handshake observed it, counts as its structure; a carried slice that still defines nothing is reported as such and counted (`3 slice(s) carry content but define nothing this runtime reads`) |
| Blueprint-only records and carried-but-unreadable slices were conflated in the report | A reader could not tell which factories cannot run and why                                              | both are now distinct fields (`blueprint_only`, `carried_without_definitions`, `mcp_tools_verified`, `by_group`) in the report and the manifest                                                                                 |

## 5. What is not claimed

- **The 39 blueprint-only factories still have no content in the seal.** Their licences forbid
  redistribution. They are present as provenance records with licence text, and the report says so.
  They are not described as operating anywhere.
- **Three carried slices define nothing this runtime can read** (`github-mcp-server`,
  `firecrawl-mcp-server`, `awesome-mcp-servers`); the report counts them and names them instead of
  counting them as workers. `awesome-mcp-servers` is a list, not a server: it has no launch command
  and no slice.
- **24 of the 42 MCP servers cannot be started on this machine** — their packages are not installed
  and this sandbox cannot reach the registries they need. They are catalogued, named and reasoned
  about; they are not described as running.
- **8 of the 15 declared languages have no toolchain here** (go, rust, java, kotlin, csharp, ruby,
  php, sql). Their packs carry a scaffold and a build/test command, but nothing claims they build on
  this machine; `--check` would fail loudly for them.
- **1,000,000 is the plan ceiling, not an execution count.** The largest run executed this round is
  159 instances; the ceiling is asserted by the planning code and the capacity report.
- **MCP servers that need pip/npm are the bonus layer.** The must-work layer is the 40 stdlib tools;
  the sealed run above bound none of them (`0 bound from MCP`) and still passed every check.
- **No APK is claimed here.** This round changes the sealed component and its tooling, not the
  Android app; the APK gate is `[APK-GATE]` in `gates.sh`, which fails in this checkout because no
  APK has been built here, and CI's own emulator/APK jobs are recorded in
  `artifacts/reports/sealed-factory-stage-6afc67a.md` and `artifacts/reports/ci-evidence-ca8f4d3.md`.

## 6. Reproducing this round

```
python3 tools/factory/seal_factories.py seal          # writes SEAL.json, SEAL.sha256, ATTRIBUTION.md
python3 tools/factory/sealed_factory_runtime.py verify
python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0 --env-limit 200
python3 -m unittest discover -s shell/tests
python3 tools/anti_deception_auditor.py && python3 tools/duplication_auditor.py
node scripts/conveyor_belt.js
```

The seal root changes whenever a payload or a runtime module changes; the value in §2.1 is the one
this round's run reports. Re-seal after any change under `sealed-factories/runtime/`, otherwise
verification (correctly) fails.

## 7. Workspace hygiene

The runtime writes a directory per run (`sealed-factories/runs/`) and a workspace for the agents it
executes (`sealed-factories/manifest/`); both are ignored by git, and neither is part of the
component. After this round's evidence was captured, the accumulated run history was cleared - 211
run directories and the 91 MB sweep workspace from the earlier unbounded-workspace experiment came to
278 MB of regenerable output, which is both pointless to carry between sessions and larger than this
session's snapshot budget. `manifest/RUN-REPORT.json` and `manifest/ENVIRONMENT.json`, the two files
the tests read, were kept. Nothing under version control was touched: `git status` before and after
the cleanup showed the same tracked files, and the seal root is unchanged.
