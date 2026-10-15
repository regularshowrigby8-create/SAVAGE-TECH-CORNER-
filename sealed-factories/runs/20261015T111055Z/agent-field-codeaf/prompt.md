# Factory stage: agent-field-codeaf

Source factory: Agent-Field/CodeAF (https://github.com/Agent-Field/CodeAF), licence Apache-2.0,
commit 8ebf000e2a95f31a8a23a214389e29cd39683066.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- internal/praf/prompts/export.go
- internal/praf/prompts/testdata/coverage_gate_A.txt
- internal/praf/prompts/testdata/coverage_gate_B.txt
- internal/praf/prompts/testdata/coverage_gate_system.txt
- internal/praf/prompts/testdata/gap_dimension_A.txt
- internal/praf/prompts/testdata/gap_dimension_B.txt
- internal/praf/prompts/testdata/intake_ai_A.txt
- internal/praf/prompts/testdata/intake_ai_B.txt
- internal/praf/prompts/testdata/intake_fallback_A.txt
- internal/praf/prompts/testdata/intake_fallback_B.txt
- internal/praf/prompts/testdata/intake_gate_system.txt
- internal/praf/prompts/testdata/merge_gate_user_A.txt
- internal/praf/prompts/testdata/merge_gate_user_B.txt
- internal/praf/prompts/testdata/polish_system.txt
- internal/praf/prompts/testdata/polish_user_A.txt
- internal/praf/prompts/testdata/polish_user_B.txt
- internal/secaf/prompts/files/hunt/scan_locations.txt
- internal/secaf/prompts/files/prove/dep_reachability.txt
- internal/secaf/prompts/files/prove/exploit.txt
- internal/secaf/prompts/files/prove/sanitization.txt
- internal/secaf/prompts/files/prove/tracer.txt
- internal/secaf/prompts/files/prove/verdict.txt
- internal/secaf/prompts/files/recon/data_flow.txt
- internal/session/prompts/landing-answer.md

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
