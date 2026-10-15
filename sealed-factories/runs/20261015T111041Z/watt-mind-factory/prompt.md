# Factory stage: watt-mind-factory

Source factory: watt-mind/factory (https://github.com/watt-mind/factory), licence Apache-2.0,
commit d57be4bfbfc4931f6948da969dd9ac541ba63b66.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- event-runtime/agents/agy-smoke.md
- event-runtime/agents/ci-doctor.view.json
- event-runtime/agents/ci-notify.md
- event-runtime/agents/ci-rerun.md
- event-runtime/agents/cursor-smoke.md
- event-runtime/agents/janitor-apply.md
- event-runtime/agents/janitor-scan.md
- event-runtime/agents/merge-apply.view.json
- event-runtime/agents/merge-fix.view.json
- event-runtime/agents/reconcile.md
- event-runtime/agents/ship-apply.view.json
- event-runtime/agents/sweep-apply.view.json
- event-runtime/agents/triage-apply.view.json
- event-runtime/agents/unblock-apply.view.json
- event-runtime/agents/unblock-digest.md
- event-runtime/agents/views/factory.command-result.v1.view.json
- event-runtime/agents/warm.md
- event-runtime/test-support/extensions/sample/pack/agents/echo.json
- event-runtime/test-support/extensions/sample/pack/agents/echo.md
- event-runtime/test-support/packs/sample/agents/echo.json
- event-runtime/test-support/packs/sample/agents/echo.md
- packs/example-hello/agents/hello.json
- packs/example-hello/agents/hello.md
- packs/ops-disk/agents/disk-report.json

## What its own definition files describe

- agy-smoke — smoke test for the agy (Antigravity) adapter
- Output
- ci-notify — closed-registry action
- ci-rerun — closed-registry action
- cursor-smoke — smoke test for the Cursor Agent CLI adapter
- janitor-apply — closed-registry action
- janitor-scan — closed-registry action
- reconcile — closed-registry action
- unblock-digest — closed-registry action
- warm — closed-registry action

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
