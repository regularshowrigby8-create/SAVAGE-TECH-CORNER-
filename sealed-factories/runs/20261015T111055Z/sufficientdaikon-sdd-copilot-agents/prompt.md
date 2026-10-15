# Factory stage: sufficientdaikon-sdd-copilot-agents

Source factory: SufficientDaikon/sdd-copilot-agents (https://github.com/SufficientDaikon/sdd-copilot-agents), licence MIT,
commit 9c5fa7a7a37d29aa8666f4dcbe71f718bd02b90c.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 0: Analyze the Specification
- 1: Project Setup
- 2: Foundation (Blocking Prerequisites)
- 3: User Story Implementation (per story, in priority order)
- 4: Polish & Cross-Cutting Concerns
- 5: Final Verification
- 0: Discover & Inventory
- 1: Name & Structure
- 2: Generate README.md
- 3: Generate HTML Documentation Website
- by-step installation (numbered, with code blocks)
- 4: Generate Install Script
- 5: Generate Prompts Guide
- 6: Initialize Git & Push to GitHub
- 7: Final Report
- 1: Load Context
- 2: Systematic Review
- 3: Generate HTML Report
- 4: Output
- 0: Understand the Plan
- 1: Create the Specification
- 2: Validate the Specification
- 3: Output
- 1: Spec Writer

## Workers this factory defines

- agents/implementer.agent.md
- agents/packager.agent.md
- profiles (.agent.md files)
- agents/reviewer.agent.md
- agents/spec-writer.agent.md

## Checks this factory defines

- EVERY requirement, not just a sample
- Criteria for the SPEC ITSELF (meta-level)
- for any `[NEEDS CLARIFICATION]` markers — resolve with user first
- Copilot directory
- Process
- if a test covers it
- EVERY requirement — no sampling
- Scenarios**:

## Prompt assets this factory ships

- prompts/bootstrap-stress-test.md
- prompts/sdd-full-pipeline-prompt.md
- prompts/sdd-stress-test-prompt.md
- skills/implementer/SKILL.md
- skills/implementer/templates/tasks-template.md
- skills/packager/SKILL.md
- skills/packager/templates/install-template.ps1
- skills/packager/templates/install-template.sh
- skills/packager/templates/readme-template.md
- skills/reviewer/SKILL.md
- skills/reviewer/templates/report-template.html
- skills/spec-writer/SKILL.md
- skills/spec-writer/examples/sample-spec.md
- skills/spec-writer/templates/constitution-template.md
- skills/spec-writer/templates/spec-template.md

## What its own definition files describe

- Implementation Agent
- Global Storage Rule
- Core Identity
- SDD Philosophy
- Workflow
- Task Format
- Rules
- DO:
- DON'T:
- Deviation Protocol
- Completion Report Format
- Implementation Completion Report
- Summary
- User Stories Implemented
- Functional Requirements Coverage
- Deviations from Spec
- Files Created/Modified
- Next Step
- Handoff
- Packager Agent
- 🟢 Getting Started
- Path Sanitization
- HTML Website Quality Bar
- GitHub Integration

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
