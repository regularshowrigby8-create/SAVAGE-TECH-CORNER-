# Factory stage: exa-mcp-server

Source factory: exa-labs/exa-mcp-server (https://github.com/exa-labs/exa-mcp-server), licence MIT,
commit e9c3b0126a3373eb1aeaca162e84d0791ff4c7f7.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Assess the Query
- 2: Dispatch Subagents
- 3: Compile Results

## Workers this factory defines

- Plugin
- Skills

## Checks this factory defines

- row count against target.
- segment coverage.
- each row against the criterion
- publication/event dates against the boundary
- Searches

## Prompt assets this factory ships

- skills/exa-agent/SKILL.md
- skills/search/SKILL.md
- skills/search/references/extraction.md
- skills/search/references/filtering.md
- skills/search/references/patterns-code.md
- skills/search/references/patterns-companies.md
- skills/search/references/patterns-news.md
- skills/search/references/patterns-papers.md
- skills/search/references/patterns-people.md
- skills/search/references/patterns-relationships.md
- skills/search/references/searching.md
- skills/search/references/source-quality.md
- skills/search/references/synthesis.md

## What its own definition files describe

- Installation
- Claude
- Codex / ChatGPT
- Other MCP Clients
- Available Tools
- Default Tools
- Optional Tools (enable via the `tools` parameter)
- Authentication
- Exa Agent Research
- Required tools
- Exa Connect providers
- Decision tree
- Before creating a run
- Schema rules
- Exa Agent workflow
- Continuation patterns
- Exhaustiveness and coverage language
- Batch Script Mode
- Failure handling
- Exa Research Orchestrator
- Prerequisites: Auth
- Date Calculation (Do This First)
- What subagents do
- How to dispatch

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
