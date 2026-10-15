# Factory stage: addyosmani-agent-skills

Source factory: addyosmani/agent-skills (https://github.com/addyosmani/agent-skills), licence MIT,
commit aeac41d4d005999432bb7312f7ff06383a91d59d.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Reproduce
- 2: Localize
- 3: Reduce
- 4: Fix the Root Cause
- 5: Guard Against Recurrence
- 6: Verify End-to-End
- 1: Understand & Expand (Divergent)
- 2: Evaluate & Converge
- 3: Sharpen & Ship
- 1: Enter Plan Mode
- 2: Identify the Dependency Graph
- 3: Slice Vertically
- 4: Write Tasks
- 5: Order and Checkpoint
- 1: Foundation
- 2: Core Features
- 3: Polish
- 1: Detect Stack and Versions
- 2: Fetch Official Documentation
- 3: Implement Following Documented Patterns
- 4: Cite Your Sources

## Workers this factory defines

- skills
- agents/code-reviewer.md
- agents/security-auditor.md
- agents/test-engineer.md

## Checks this factory defines

- → `code-review-and-quality`
- Framework
- Output Template
- Summary
- Story
- the tests first — they reveal intent and coverage
- Scope
- the OWASP Top 10 (and the LLM Top 10 for AI features) as a minimum baseline
- existing tests for patterns and conventions
- After setting up or modifying CI:
- After fixing a bug:
- After documenting:
- After completing an ideation session:
- After completing all increments for a task:
- criteria:**
- **
- checkpoints occur after every 2-3 tasks
- Before starting implementation, confirm:
- health endpoint returns 200
- error monitoring dashboard (no new error types)
- latency dashboard (no regression)
- Before deploying:
- After implementing with source-driven development:

## Prompt assets this factory ships

- skills/ci-cd-and-automation/SKILL.md
- skills/constraint-driven-development/references/floor-guard.md
- skills/debugging-and-error-recovery/SKILL.md
- skills/documentation-and-adrs/SKILL.md
- skills/idea-refine/SKILL.md
- skills/idea-refine/frameworks.md
- skills/idea-refine/refinement-criteria.md
- skills/idea-refine/scripts/idea-refine.sh
- skills/incremental-implementation/SKILL.md
- skills/performance-optimization/references/optimization-patterns.md
- skills/planning-and-task-breakdown/SKILL.md
- skills/shipping-and-launch/SKILL.md
- skills/source-driven-development/SKILL.md
- skills/using-agent-skills/SKILL.md

## What its own definition files describe

- Adding or changing a skill
- AGENTS.md
- Repository Overview
- OpenCode Integration
- Core Rules
- Intent → Skill Mapping
- Lifecycle Mapping (Implicit Commands)
- Execution Model
- Anti-Rationalization
- Orchestration: Personas, Skills, and Commands
- Creating a New Skill
- Senior Code Reviewer
- 1. Correctness
- 2. Readability
- 3. Architecture
- 4. Security
- 5. Performance
- Output Format
- Critical Issues
- Required Changes
- Optional
- Nits
- What's Done Well
- Rules

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
