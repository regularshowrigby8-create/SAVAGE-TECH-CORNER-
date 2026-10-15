# Factory stage: mobile-dev-inc-maestro

Source factory: mobile-dev-inc/Maestro (https://github.com/mobile-dev-inc/Maestro), licence Apache-2.0,
commit df40dea5dee0b8639aade6e80b558950e982e8a2.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 3: push draft PR + dispatch
- 4: watch the test-android job
- 5: diagnose (delegated to subagent)
- 0: say what will happen, then check the ground
- 1: propose the version, draft the changelog (Checkpoint 1)
- 2: draft everything, land nothing
- 3: merge and tag (unattended)
- 4: publish the CLI (unattended)
- 5: verify
- 6: hand-off

## Workers this factory defines

- .claude/agents/diagnose-maestro-failure.md

## Checks this factory defines

- we're on a clean main and confirm the commit.

## Prompt assets this factory ships

- .claude/skills/bump-android-version/SKILL.md
- .claude/skills/release/SKILL.md

## What its own definition files describe

- Diagnose Maestro Failure (subagent)
- Input
- Where to Look
- Per-Flow Investigation
- Classification
- Cascade Detection (critical)
- Required Output Format
- Summary
- Root causes (caller acts on each)
- 1. <flow-or-group-label> — <classification>
- 2. ...
- Out of scope
- Notes
- Constraints
- When to Stop and Hand Back
- Bump Android Version
- Overview
- When to Use
- Pre-flight
- Commit 1: bump compile/target SDK
- Commit 2: rebuild driver APKs
- capture the run id
- Termination
- Anti-patterns

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
