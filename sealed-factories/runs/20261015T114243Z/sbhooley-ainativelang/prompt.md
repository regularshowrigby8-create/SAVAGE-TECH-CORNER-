# Factory stage: sbhooley-ainativelang

Source factory: sbhooley/ainativelang (https://github.com/sbhooley/ainativelang), licence Apache-2.0,
commit bbdc784aa432865c6aee75be88180dd3b6de540e.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- docs/agents/INDEX.md
- guides — index
- docs/agents/openclaw-overview.md
- instructions

## Prompt assets this factory ships

- apollo-x-bot/prompts/README.md
- apollo-x-bot/prompts/classify_instruction.txt
- apollo-x-bot/prompts/classify_system.txt
- apollo-x-bot/prompts/daily_post_system.txt
- apollo-x-bot/prompts/daily_post_user_suffix.txt
- apollo-x-bot/prompts/discovery_user_instruction.txt
- apollo-x-bot/prompts/reply_system.txt
- prompts/meta-agent/README.md
- prompts/meta-agent/generate_safe_workflow.md
- prompts/meta-agent/mutate_with_ir_diff.md
- prompts/meta-agent/repair_from_diagnostics.md
- skills/ainl/install.sh
- skills/hermes/README.md
- skills/hermes/SKILL.md
- skills/hermes/a2a.example.json
- skills/hermes/ainl_hermes_bridge.py
- skills/hermes/armaraos-a2a-bridge/README.md
- skills/hermes/armaraos-a2a-bridge/hermes_chat_delegate.py
- skills/hermes/armaraos-a2a-bridge/smoke_test_a2a_bridge.sh
- skills/hermes/example_ainl_to_hermes_skill.ainl
- skills/hermes/install.sh
- skills/hermes/install_armaraos_a2a_bridge.sh
- skills/openclaw/install.sh

## What its own definition files describe

- Promoter LLM prompts (text files)
- Index
- Where to find install-time info
- Migration notes
- Meta-Agent Prompt Library
- Bootstrap AINL for restricted environments (PEP 668 safe) + MCP host setup.
- AINL + Hermes Agent Skills Pack
- Quick usage
- ArmaraOS A2A bridge (optional)
- What this pack installs
- Install
- Files
- Upstream
- AINL for Hermes Agent
- When to use this skill
- Install (pick one)
- After install — prompt suggestion
- MCP
- Bridge (optional)
- ArmaraOS-compatible A2A bridge (Hermes `~/.hermes`)
- Install into `~/.hermes`
- Run
- Verify (smoke test)
- ArmaraOS side

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
