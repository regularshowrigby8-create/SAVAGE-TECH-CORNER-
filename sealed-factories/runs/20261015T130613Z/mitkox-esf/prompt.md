# Factory stage: mitkox-esf

Source factory: mitkox/esf (https://github.com/mitkox/esf), licence MIT,
commit 0ef8e23484b2295fe035bdd98e11f180daf95322.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- examples/workflows/gvisor-pr/worker.py

## Workers this factory defines

- agents/opencode/package.json
- agents/pi/LICENSE.pi
- agents/pi/build.mjs
- agents/pi/export-catalog.mjs
- agents/pi/inventory.json
- agents/pi/models/deterministic.json
- agents/pi/notices.mjs
- agents/pi/package.json
- agents/pi/src/engine.mjs
- agents/pi/src/protocol.mjs
- agents/pi/src/runner.mjs
- agents/pi/src/supervisor.mjs

## Prompt assets this factory ships

- examples/prompts/audit.md
- examples/prompts/merge.md
- examples/prompts/task-to-pr.md
- skills/machinist/SKILL.md

## What its own definition files describe

- /// script
- requires-python = ">=3.10"
- dependencies = ["openai-codex==0.147.0"]
- ///
- Machinist
- Core model
- Create a task
- Assign a task
- Report status

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
