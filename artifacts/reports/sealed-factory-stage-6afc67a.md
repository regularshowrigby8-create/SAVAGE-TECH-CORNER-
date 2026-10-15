# Evidence — the sealed factory stage (AC-048)

Commits `6afc67a`, `226b71c` and `d7e87bd` on on `arena/5223ef6f-savage-tech-corner`. Every line below is copied from a
command that was actually run; nothing here is reconstructed.

## 1. The seal verifies, and a tampered payload does not pass

```
$ python3 tools/factory/sealed_factory_runtime.py verify
[SEAL] OK 1.0.0: 72 factories, 938767 payload bytes, 50 licence texts, root adeae72966d5a9bb…
```

`shell/tests/test_sealed_factory_runtime.py#test_every_payload_hash_in_the_seal_matches_the_file`
recomputes the SHA-256 of all 72 payloads against `SEAL.json`, and
`#test_a_tampered_payload_stops_the_run` appends one byte to a copied payload and asserts the verifier
exits 1 - a sealed bag that has been opened is refused rather than trusted.

## 2. Every factory is accounted for

```
$ python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0
[SEALED] 72 factories sealed (43 with redistributed content, 29 blueprint-only)
[SEALED] models: local=[] keyed=[]
[SEALED] 0 factories ran against a model, 43 ran in plan mode, 29 had no definitions in their sealed slice, 0 model calls failed, 0 unreadable
[SEALED] definitions read from the sealed set: 372 stages, 252 workers, 93 checks, 450 prompt assets, 2429 sections of prose their definition files publish
```

The report (`sealed-factories/manifest/RUN-REPORT.json`, written at run time and not committed)
accounts for all 72: 43 `OPERATIONAL-PLAN`, 29
`NO-STRUCTURE`, 0 `MODEL-FAILED`, 0 unreadable. The
`NO-STRUCTURE` set is exactly the blueprint-only set - the factories whose content the licence does
not permit carrying - which
`#test_the_factories_that_cannot_run_are_exactly_the_ones_whose_content_is_not_carried` pins.

No model is reachable in this sandbox (no local server on 11434/1234/8000, no provider key in the
environment), so every factory ran from its own definitions. The online path is not claimed here on
faith: `#test_a_reachable_local_model_makes_a_factory_run_against_it` starts a local server speaking
the providers' own API on one of those ports, runs the set with `--max-model-calls 1`, and asserts
exactly one factory reports `OPERATIONAL-MODEL` with its artefact byte-identical to the answer, while
every other factory's status is unchanged; `#test_a_refusing_provider_is_recorded_as_a_failure_not_a_success`
serves a 429 and asserts the report carries `MODEL-FAILED` with the provider's own words and zero
factories reported as run.

## 3. Station 0 of the conveyor belt, with the component present

```
$ node scripts/conveyor_belt.js
[STATION 0] Sealed Imported Factory Set...
[SEAL] OK 1.0.0: 72 factories, 938767 payload bytes, 50 licence texts, root adeae72966d5a9bb…
[STATION 0: PASS] Sealed Imported Factory Set satisfied.
   CONVEYOR BELT COMPLETE: SEALED SET RAN + ALL 6 CONNECTED STAGES PASSED
```

## 4. Station 0 with the component deleted

A copy of the tree without `sealed-factories/` (everything else identical):

```
$ node scripts/conveyor_belt.js
[STATION 0] Sealed Imported Factory Set...
[STATION 0: PASS] Sealed Imported Factory Set satisfied.
sealed component ABSENT - the belt continues with this repository's own factories
CONVEYOR BELT COMPLETE: SEALED SET ABSENT + ALL 6 CONNECTED STAGES PASSED
```

Exit code 0, six remaining stations pass, no `sealed-factories/` directory is created. Two tests pin
this: `#test_deleting_the_component_leaves_a_working_repository` runs the runtime in an empty tree and
asserts it reports ABSENT, exits zero and creates nothing, and
`#test_the_component_owns_no_file_outside_its_directory` reads every source file under `shell/`,
`factories/`, `factory-2/` and `factory-3/` and asserts none of them references the component.

## 5. The pipeline around it, on CI

Run `38537245267` for commit `6afc67a` (`Android Build, Test and Release`), all jobs:

| Job                                                | Conclusion                                      |
| -------------------------------------------------- | ----------------------------------------------- |
| Build, unit tests and lint                         | success                                         |
| Emulator instrumented tests and install smoke test | success                                         |
| Publish signed release APK                         | skipped (the keystore secret is not configured) |

Artifacts from that run:

| Artifact                               | Size            | Digest                                                                    |
| -------------------------------------- | --------------- | ------------------------------------------------------------------------- |
| `savage-browser-apk` (run 38537245267) | 5,793,251 bytes | `sha256:d4e8190ac3aad1273820789649fd29e8172a764cdce5d14a71e448fb8bacb14c` |
| `emulator-evidence` (run 38537245267)  | 543,852 bytes   | `sha256:f6efd71092fa286d7f2a82d08a39d5d916b485065c7041eb250163da2c98517a` |
| `savage-browser-apk` (run 38538386756) | 5,793,182 bytes | `sha256:cc61ad17f5f0b980e3e4bef1bae90e339ce1ada62d0c75da89a2257ecb824569` |
| `emulator-evidence` (run 38538386756)  | 466,670 bytes   | `sha256:60f30a8ed2c309fc47faa36ffec2cb19f35407d7880d133b48d82c9311bb43b5` |

`Comprehensive Quality Conveyor Belt` and `AI PR Code Reviewers (Dual Fallback)` both concluded
success on the same commit, and `Factory 2 — Single Debug Job` succeeded on the successor commits.
The belt workflow runs station 0 on a clean checkout, which is the reproducibility claim AC-047 makes.

## 6. Runs after this one, including one that failed

| Commit                                                                  | Workflow                                                      | Conclusion                                                    |
| ----------------------------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------- |
| `226b71c` (AC-048 in `docs/ACCEPTANCE_CRITERIA.md`, documentation only) | Comprehensive Quality Conveyor Belt                           | success                                                       |
| `226b71c`                                                               | Build, unit tests and lint                                    | success                                                       |
| `226b71c`                                                               | Emulator instrumented tests and install smoke test            | **failure** at "Run instrumented tests on an API 35 emulator" |
| `226b71c`                                                               | Factory 2 — Single Debug Job                                  | success                                                       |
| `d7e87bd` (this evidence file)                                          | Build / Conveyor Belt / Emulator / Dual Reviewers / Factory 2 | success (all five jobs)                                       |

The single failure is recorded rather than smoothed over. It was not root-caused: the run's logs are
not reachable from this environment (`gh run view --log-failed` fails at the log download), and GitHub
refuses to re-run that run ("cannot be rerun"). What is known: it is the emulator leg of the
instrumented tests; the identical workflow passed on `6afc67a` before it and on `d7e87bd` after it;
`226b71c` changed one documentation file; and the failure is not in the sealed stage, whose own
workflow passed on that same commit. The APK evidence quoted above is from `d7e87bd`, where every job
is green.

## 7. What is not claimed

- No signed **release** APK exists for this branch: `Publish signed release APK` is skipped because
  the repository deliberately does not commit a keystore (A11). The APK above is the debug-signed
  artifact the CI build produced for this commit.
- No model actually ran here, because none is reachable in this environment; the online path is
  proven against a local server speaking the same API, as described above.
- The sealed set is imported third-party material and stays inert data under `sealed-factories/`; per
  factory attribution and the licence basis is in `sealed-factories/ATTRIBUTION.md`, and the
  blueprint-only factories' content is not in this repository at all.
