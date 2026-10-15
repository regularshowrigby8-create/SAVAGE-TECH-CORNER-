# Factory stage: hkuds-nanobot

Source factory: HKUDS/nanobot (https://github.com/HKUDS/nanobot), licence MIT,
commit 3a28b9e6ce5e514d4dee9d964056d876a69a6cdc.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- nanobot/agent/hooks/__init__.py
- nanobot/agent/skill_metadata.py
- nanobot/agent/tools/cli_apps_config.py
- nanobot/agent/tools/filesystem_config.py
- nanobot/agent/tools/image_generation_config.py
- nanobot/agent/tools/path_utils.py
- nanobot/agent/tools/self_config.py
- nanobot/agent/tools/shell_config.py
- nanobot/agent/tools/web_config.py
- nanobot/templates/agent/_snippets/untrusted_content.md
- nanobot/templates/agent/automation_creation.md
- nanobot/templates/agent/cron_reminder.md
- nanobot/templates/agent/evaluator.md
- response
- nanobot/templates/agent/max_iterations_message.md
- nanobot/templates/agent/platform_policy.md
- nanobot/templates/agent/skills_section.md
- nanobot/templates/agent/subagent_announce.md
- nanobot/templates/agent/subagent_system.md
- nanobot/templates/agent/tool_contract.md

## Prompt assets this factory ships

- nanobot/skills/clawhub/SKILL.md
- nanobot/skills/github/SKILL.md
- nanobot/skills/memory/SKILL.md
- nanobot/skills/nanobot-document/SKILL.md
- nanobot/templates/prompts/README.md

## What its own definition files describe

- Reusing verification evidence
- ClawHub
- When to use
- Search
- Install
- Update
- List installed
- Notes
- GitHub Skill
- Pull Requests
- API for Advanced Queries
- JSON Output
- Memory
- Search Past Events
- nanobot Documentation
- Original task
- Platform Policy (Windows)
- Platform Policy (POSIX)
- Skills
- Subagent
- Workspace
- Tool Usage Notes
- Scheduling
- Prompt Overrides

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
