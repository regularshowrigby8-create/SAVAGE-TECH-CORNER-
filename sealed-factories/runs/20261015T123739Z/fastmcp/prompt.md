# Factory stage: fastmcp

Source factory: jlowin/fastmcp (https://github.com/PrefectHQ/fastmcp), licence Apache-2.0,
commit e2fcc412001ff7414095c5dc282f94c70299a612.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- a FastMCP security report
- the proposed fix independently
- Checklist
- mutual exclusion

## Prompt assets this factory ships

- .agents/skills/docs/SKILL.md
- .agents/skills/fix-issue/SKILL.md
- .agents/skills/python-tests/SKILL.md
- .agents/skills/release/scripts/changelog_entry.py
- .agents/skills/review-security-report/SKILL.md
- .agents/skills/triage/SKILL.md
- examples/filesystem-provider/components/prompts/assistant.py
- examples/skills/README.md
- examples/skills/client.py
- examples/skills/download_skills.py
- examples/skills/sample_skills/code-review/SKILL.md
- examples/skills/sample_skills/pdf-processing/SKILL.md
- examples/skills/sample_skills/pdf-processing/reference.md
- examples/skills/server.py
- fastmcp_slim/fastmcp/prompts/__init__.py
- fastmcp_slim/fastmcp/prompts/base.py
- fastmcp_slim/fastmcp/prompts/function_prompt.py
- fastmcp_slim/fastmcp/server/providers/skills/__init__.py
- fastmcp_slim/fastmcp/server/providers/skills/_common.py
- fastmcp_slim/fastmcp/server/providers/skills/claude_provider.py
- fastmcp_slim/fastmcp/server/providers/skills/directory_provider.py
- fastmcp_slim/fastmcp/server/providers/skills/vendor_providers.py
- skills/fastmcp-client-cli/SKILL.md

## What its own definition files describe

- FastMCP docs
- Procedure
- Template
- Basic usage
- Configuration
- How it works
- Voice
- Accessibility
- Verify
- Gotchas
- Fix a selected issue
- Establish the contract
- Red, green, review
- Publish and follow through
- FastMCP tests
- Preserve the evidence
- Reproduce the claim
- Identify the responsible layer
- Establish FastMCP's contract
- Separate OAuth proxy boundaries
- Classification checkpoint
- Find worthwhile work
- Skills Provider Example
- Structure

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
