# Factory stage: thruwire-foreman

Source factory: thruwire/foreman (https://github.com/thruwire/foreman), licence MIT,
commit 381ca2de90af9efc2781a58055cc3028c7cabbb9.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- backends
- src/foreman/workers/__init__.py
- src/foreman/workers/base.py
- src/foreman/workers/codex.py
- src/foreman/workers/codex_app_server.py
- src/foreman/workers/deepagents.py
- src/foreman/workers/hermes.py
- src/foreman/workers/opencode.py
- src/foreman/workers/simulation.py

## Checks this factory defines

- results and recent persisted events;

## What its own definition files describe

- Documentation
- Contributing
- What is Foreman?
- Why build this?
- The factory floor
- What Foreman watches
- Responsibilities and checks
- What Foreman can do
- Why Jev?
- Requirements
- Installation
- Running Foreman
- Deterministic demo
- Attached coding-assistant workers
- Persistence and inspection
- Runtime configuration
- Tests
- Process safety and security
- Limitations
- Roadmap
- Built-in backends
- Pi and Pi Durable
- Backend behavior
- Adding a backend

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
