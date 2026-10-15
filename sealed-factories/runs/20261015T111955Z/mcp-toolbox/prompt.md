# Factory stage: mcp-toolbox

Source factory: googleapis/mcp-toolbox (https://github.com/googleapis/mcp-toolbox), licence Apache-2.0,
commit aaf88413e117856f80f646da7f1dbc63ebaedea3.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- A: Release Platform-Specific Packages**
- B: Release Main Package (@toolbox-sdk/server)**

## Workers this factory defines

- "user"
- "assistant"

## Checks this factory defines

- our community guidelines

## Prompt assets this factory ships

- docs/en/documentation/configuration/prompts/_index.md
- docs/en/documentation/configuration/prompts/custom/_index.md
- internal/prompts/arguments.go
- internal/prompts/arguments_test.go
- internal/prompts/custom/custom.go
- internal/prompts/custom/custom_test.go
- internal/prompts/messages.go
- internal/prompts/messages_test.go
- internal/prompts/prompts.go
- internal/prompts/prompts_test.go
- skills/maintainer/docsite-link-sweep/references/link-forms.md
- skills/maintainer/reproduce-bug/references/DEVELOPER.md
- skills/maintainer/review-prs/references/CONTRIBUTING.md
- skills/maintainer/review-prs/references/DEVELOPER.md
- skills/maintainer/triage-issues/references/maintainer-playbook.md

## What its own definition files describe

- Link forms, path mapping, and traps
- Path to URL mapping
- Canonical link form
- Why a site-absolute link leaks
- Shortcode-generated links
- Common traps
- DEVELOPER.md
- Prerequisites
- Developing Toolbox
- Running from Local Source
- Cross Compiling For Windows
- Compiling on Windows
- Tool Naming Conventions
- Tool Name
- Tool Type
- Tool Invocation & Error Handling
- Error Categorization
- Implementation Guidelines
- Implementation Guides
- Adding a New Database Source or Tool
- Adding a New Database Source
- Adding a New Tool
- Adding Integration Tests
- Adding Documentation

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
