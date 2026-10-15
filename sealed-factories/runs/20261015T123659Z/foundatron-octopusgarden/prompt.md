# Factory stage: foundatron-octopusgarden

Source factory: foundatron/octopusgarden (https://github.com/foundatron/octopusgarden), licence MIT,
commit 1414be0be9b9dfd1af80cd1a80f056255b91695c.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1 -- Gather context
- 2 -- Read current docs
- 3 -- Determine what needs updating
- 4 -- Make changes
- 5 -- Verify
- 6 -- Stage

## Workers this factory defines

- .claude/agents/go-architect.md

## Checks this factory defines

- Checklist

## Prompt assets this factory ships

- .claude/skills/generate-examples.md
- .claude/skills/update-docs/SKILL.md

## What its own definition files describe

- Output Format
- Findings
- Summary
- After Review
- Generate Examples
- Instructions
- Notes
- OctopusGarden
- What Is This?
- Prior Art
- How It Works
- Quick Start
- Homebrew (macOS/Linux)
- Or build from source
- Interactive setup (recommended)
- Or set an env var
- Conversational spec-drafting (outputs spec.md by default)
- Also generate holdout scenario YAML files alongside the spec
- Improve an existing spec through targeted questions
- Items REST API
- Todo app with auth
- TUI timer board (terminal UI with PTY interaction)
- TUI runbook runner (markdown execution, themes, overlays)
- Validate a running service independently

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
