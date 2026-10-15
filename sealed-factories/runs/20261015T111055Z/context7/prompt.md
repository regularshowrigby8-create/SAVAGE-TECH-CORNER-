# Factory stage: context7

Source factory: upstash/context7 (https://github.com/upstash/context7), licence MIT,
commit 3131245817e9e40626d8f43ce85dabab09d4ff35.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- by-Step
- 1: Resolve the Library ID
- 2: Select the Best Match
- 3: Fetch the Documentation
- 4: Use the Documentation

## Workers this factory defines

- docs/agentic-tools/ai-sdk/agents/context7-agent.mdx
- Workflow
- packages/tools-ai-sdk/src/agents/context7.ts
- packages/tools-ai-sdk/src/agents/index.ts
- plugins/copilot/context7/agents/docs-researcher.agent.md
- plugins/cursor/context7/agents/docs-researcher.md

## Checks this factory defines

- ALL the search results returned

## Prompt assets this factory ships

- packages/opencode/skills/context7-mcp/SKILL.md
- packages/pi/prompts/c7-docs.md
- packages/pi/skills/context7-docs/SKILL.md
- packages/tools-ai-sdk/src/prompts/index.ts
- packages/tools-ai-sdk/src/prompts/system.ts
- plugins/agent-plugins/context7/skills/context7-mcp/SKILL.md
- plugins/antigravity/context7/skills/context7-mcp/SKILL.md
- plugins/codex/context7/skills/context7-mcp/SKILL.md
- plugins/copilot/context7/skills/context7-mcp/SKILL.md
- plugins/cursor/context7/skills/context7-mcp/SKILL.md
- skills/context7-cli/SKILL.md
- skills/context7-cli/references/setup.md
- skills/context7-mcp/SKILL.md

## What its own definition files describe

- Usage
- Configuration
- Parameters
- Returns
- Examples
- Basic Usage
- With OpenAI
- Streaming Responses
- Custom Configuration
- Custom System Prompt
- Comparison: Agent vs Tools
- When to Use the Agent
- When to Use Individual Tools
- Related
- When to Use This Skill
- How to Fetch Documentation
- Guidelines
- Context7 Documentation Lookup
- When to use
- Workflow
- Constraints
- Your Task
- Process
- ctx7 CLI

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
