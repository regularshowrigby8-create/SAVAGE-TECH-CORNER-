# Factory stage: forward-future-loopy

Source factory: Forward-Future/loopy (https://github.com/Forward-Future/loopy), licence MIT,
commit 75966cbd572a4185064971c9fe5e9c52e8f8456d.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- Record the current state and the acceptance check.
- Make one bounded change and run the same check again.
- Keep improvements and stop at success or no measurable progress.

## Workers this factory defines

- loop-library/site/agents/index.html
- loop-library/worker/.dev.vars.example
- loop-library/worker/bin/export-catalog.mjs
- loop-library/worker/bin/import-bootstrap.mjs
- loop-library/worker/bin/publish-loop.mjs
- loop-library/worker/bin/restore-catalog.mjs
- loop-library/worker/examples/loop.json
- loop-library/worker/package.json
- loop-library/worker/src/loop-schema.js
- loop-library/worker/src/vote-store.js
- loop-library/worker/wrangler.jsonc
- skills/loop-library/agents/openai.yaml
- skills/loopy/agents/openai.yaml

## Checks this factory defines

- is reproducible and, when overfitting or self-approval is a

## Prompt assets this factory ships

- skills/loop-library/SKILL.md
- skills/loop-library/agents/openai.yaml
- skills/loop-library/references/audit.md
- skills/loop-library/references/debrief.md
- skills/loop-library/references/discover.md
- skills/loop-library/references/publish.md
- skills/loop-library/references/run.md
- skills/loopy/SKILL.md
- skills/loopy/agents/openai.yaml
- skills/loopy/references/audit.md
- skills/loopy/references/debrief.md
- skills/loopy/references/discover.md
- skills/loopy/references/publish.md
- skills/loopy/references/run.md

## What its own definition files describe

- Loop Library (legacy alias)
- Route the request
- Discover loops from existing work
- Find a published loop
- Audit and repair a loop
- Run a loop
- Debrief completed runs
- Prepare or publish a loop
- Save and reuse project loops
- Keep every workflow grounded
- Craft a loop through an interview
- Design the feedback cycle
- Validate every crafted loop
- Deliver the loop
- [Loop name]
- Loop Doctor
- Inspect the loop
- Repair the loop
- Debrief a Loop Run
- Diagnose from evidence
- Return the debrief
- Loopy debrief
- Loop Discovery
- Inspect the evidence

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
