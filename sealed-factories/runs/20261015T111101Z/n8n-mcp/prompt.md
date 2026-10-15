# Factory stage: n8n-mcp

Source factory: czlonkowski/n8n-mcp (https://github.com/czlonkowski/n8n-mcp), licence MIT,
commit 64c8093ad6a12c596eb24a44c1ddf84bddd9c209.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- .claude/agents/code-reviewer.md
- .claude/agents/context-manager.md
- .claude/agents/debugger.md
- .claude/agents/mcp-backend-engineer.md
- .claude/agents/test-automator.md

## Checks this factory defines

- the current conversation and all agent outputs
- recent code changes using git history or file modifications
- message formatting and protocol compliance
- compatibility with the existing n8n-mcp infrastructure

## Prompt assets this factory ships

- data/skills/n8n-binary-and-data/CDN_REQUIREMENT.md
- data/skills/n8n-expression-syntax/README.md
- data/skills/n8n-mcp-tools-expert/README.md
- data/skills/n8n-self-hosting/README.md
- data/skills/n8n-self-hosting/SINGLE_MODE.md
- data/skills/n8n-self-hosting/assets/.env.queue.example
- data/skills/n8n-self-hosting/assets/.env.single.example
- data/skills/n8n-self-hosting/assets/Caddyfile
- data/skills/n8n-self-hosting/assets/docker-compose.single.yml
- data/skills/n8n-self-hosting/assets/init-data.sh
- data/skills/using-n8n-mcp-skills/README.md
- src/mcp/skills/index.ts
- src/mcp/skills/registry.ts
- src/mcp/skills/types.ts

## What its own definition files describe

- Primary Functions
- Context Capture
- Context Distribution
- Memory Management
- Workflow Integration
- Context Formats
- Quick Context (< 500 tokens)
- Full Context (< 2000 tokens)
- Archived Context (stored in memory)
- Best Practices
- Output Format
- Core Responsibilities
- Testing Philosophy
- Implementation Guidelines
- Unit Testing
- Integration Testing
- E2E Testing
- Test Data Management
- CI/CD Integration
- Output Requirements
- Framework Selection
- Quality Checks
- Special Considerations
- The CDN / URL Requirement for Chat Surfaces

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
