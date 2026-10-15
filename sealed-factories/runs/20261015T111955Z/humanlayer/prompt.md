# Factory stage: humanlayer

Source factory: humanlayer/humanlayer (https://github.com/humanlayer/humanlayer), licence Apache-2.0,
commit 99abe673498cf8bdcd5f989aebe9406a27185b3b.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Read Entry Points
- 2: Follow the Code Path
- 3: Document Key Logic
- 1: Identify Pattern Types
- 2: Search!
- 3: Read and Extract
- 1: Read with Purpose
- 2: Extract Strategically
- 3: Filter Ruthlessly

## Workers this factory defines

- .claude/agents/codebase-analyzer.md
- .claude/agents/codebase-locator.md
- .claude/agents/codebase-pattern-finder.md
- .claude/agents/thoughts-analyzer.md
- .claude/agents/thoughts-locator.md
- .claude/agents/web-search-researcher.md
- name from YAML frontmatter

## Checks this factory defines

- common locations (src/, lib/, pkg/, etc.)
- thoughts/shared/ for team documents
- thoughts/allison/ (or other user dirs) for personal notes
- thoughts/global/ for cross-repo thoughts
- standard subdirectories
- `go.mod` for Go version (varies between 1.21 and 1.24)
- if directory has a `Makefile` for available commands
- if a given object implements the Agent interface.

## What its own definition files describe

- Core Responsibilities
- Analysis Strategy
- Output Format
- Analysis: [Feature/Component Name]
- Overview
- Entry Points
- Core Implementation
- 1. Request Validation (`handlers/webhook.js:15-32`)
- 2. Data Processing (`services/webhook-processor.js:8-45`)
- 3. State Management (`stores/webhook-store.js:55-89`)
- Data Flow
- Key Patterns
- Configuration
- Error Handling
- Important Guidelines
- What NOT to Do
- REMEMBER: You are a documentarian, not a critic or consultant
- Search Strategy
- Initial Broad Search
- Refine by Language/Framework
- Common Patterns to Find
- File Locations for [Feature/Topic]
- Implementation Files
- Test Files

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
