# Factory stage: mcp-server-neon

Source factory: neondatabase-labs/mcp-server-neon (https://github.com/neondatabase/mcp-server-neon), licence MIT,
commit c79875ec44eb0817eb98e86cd44cac3fa45315ae.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- Workflow

## Checks this factory defines

- status
- formatting (runs in CI)
- for unused code and dependencies
- Process

## Prompt assets this factory ships

- .agents/skills/claimable-postgres/SKILL.md
- .agents/skills/logging-best-practices/SKILL.md
- .agents/skills/logging-best-practices/rules/context.md
- .agents/skills/logging-best-practices/rules/pitfalls.md
- .agents/skills/logging-best-practices/rules/structure.md
- .agents/skills/logging-best-practices/rules/wide-events.md

## What its own definition files describe

- Claimable Postgres
- Quick Start
- Which Method?
- Auto-provisioning
- API path
- CLI path
- Output Checklist
- Safety and UX Notes
- REST API
- Create a database
- Error responses
- CLI
- Pre-run Check
- Options
- Output
- SDK
- Vite Plugin
- Claiming
- Neon Infrastructure as Code (`neon.ts`)
- Defaults and Limits
- Logging Best Practices Skill
- Purpose
- When to Apply
- Core Principles

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
