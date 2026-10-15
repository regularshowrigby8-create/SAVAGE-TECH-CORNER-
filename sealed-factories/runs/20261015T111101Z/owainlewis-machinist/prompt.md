# Factory stage: owainlewis-machinist

Source factory: owainlewis/machinist (https://github.com/owainlewis/machinist), licence MIT,
commit 39435164faf1ff7fad49e41c38a7eb1a00538f21.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- examples/workflows/gvisor-pr/worker.py
- examples/workflows/risk_delivery/gate.py

## Prompt assets this factory ships

- examples/prompts/audit.md
- examples/prompts/merge.md
- examples/prompts/task-to-pr.md
- skills/machinist/SKILL.md

## What its own definition files describe

- Why Machinist
- Quick start
- ~/.machinist/config.toml
- How execution works
- Go deeper
- Contributing
- /// script
- requires-python = ">=3.10"
- dependencies = ["openai-codex==0.147.0"]
- ///
- Machine-local paths only. Commands and script workflows belong in config.toml.
- name is optional and defaults to the machine hostname.
- Register each repository that managed workers may run in.
- [repositories.my-project]
- path = "/absolute/path/to/my-project"
- Machinist
- Core model
- Create a task
- Assign a task
- Report status

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
