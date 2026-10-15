# Factory stage: tt-a1i-archify

Source factory: tt-a1i/archify (https://github.com/tt-a1i/archify), licence MIT,
commit d0c8c7e9f4e06f303389f804208d387f27019ef5.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- .agents/skills/simplify-codebase/agents/openai.yaml
- .agents/skills/test-value/agents/openai.yaml
- run lifecycle
- tool-call workflow

## Prompt assets this factory ships

- .agents/skills/archify-review/SKILL.md
- .agents/skills/archify-tuning/references/experiment-design.md
- .agents/skills/archify-tuning/references/trace-export.md
- .agents/skills/archify-tuning/references/trace-signals.md
- .agents/skills/archify-tuning/scripts/lib.mjs
- .agents/skills/archify-tuning/scripts/replay.mjs
- .agents/skills/archify-tuning/scripts/round.mjs
- .agents/skills/archify-tuning/scripts/shots.mjs
- .agents/skills/simplify-codebase/LICENSE
- .agents/skills/simplify-codebase/SKILL.md
- .agents/skills/simplify-codebase/agents/openai.yaml
- .agents/skills/simplify-codebase/references/boundaries-and-lifecycle.md
- .agents/skills/simplify-codebase/references/decision-records.md
- .agents/skills/simplify-codebase/references/execution-and-recovery.md
- .agents/skills/simplify-codebase/references/integrating-findings.md
- .agents/skills/simplify-codebase/references/investigation.md
- .agents/skills/test-value/LICENSE
- .agents/skills/test-value/agents/openai.yaml
- .agents/skills/test-value/references/audit.md
- .agents/skills/test-value/references/decision-examples.md
- benchmarks/ordinary-model-floor/prompts/agent-run.lifecycle.md
- benchmarks/ordinary-model-floor/prompts/agent-tool-call.workflow.md
- benchmarks/ordinary-model-floor/prompts/cache-miss.sequence.md
- benchmarks/ordinary-model-floor/prompts/product-analytics.dataflow.md

## What its own definition files describe

- Archify Review
- Experiment design
- Author comparisons
- Whole-process timing
- Semantic and visual comparison
- Trace export
- Trace signals
- Root causes found so far
- Previously rejected experiments
- Simplify Codebase
- Select mode and scope
- Establish the contract
- Cover the relevant surface
- Build a proof record
- Decide and act
- Deliver the result
- Boundaries and Lifecycle
- Locate the real boundary
- Draw the lifecycle graph
- Prove quiescence
- Model races before deletion
- Decision Record Cleanup
- Find the current owner
- Consolidate without erasing lessons

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
