# Factory stage: phyagentos-phyagentos-core

Source factory: PhyAgentOS/PhyAgentOS-core (https://github.com/PhyAgentOS/PhyAgentOS-core), licence MIT,
commit 74546aba45733cf102211e2f13dbf2c80ff6df8b.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- PhyAgentOS/agent/__init__.py
- PhyAgentOS/agent/experience/__init__.py
- PhyAgentOS/agent/experience/redaction.py
- PhyAgentOS/agent/interaction/__init__.py
- PhyAgentOS/agent/interaction/model.py
- PhyAgentOS/agent/tools/__init__.py
- PhyAgentOS/agent/tools/agent.py
- PhyAgentOS/agent/tools/message.py
- PhyAgentOS/agent/tools/registry.py
- PhyAgentOS/agent/tools/scene_graph.py
- PhyAgentOS/agent/tools/skill_activation.py
- PhyAgentOS/agent/tools/spawn.py
- Mode Manager

## Checks this factory defines

- for shell prompt (`❯` or `$`) to detect completion

## Prompt assets this factory ships

- PhyAgentOS/skills/README.md
- PhyAgentOS/skills/agent-mode/SKILL.md
- PhyAgentOS/skills/clawhub/SKILL.md
- PhyAgentOS/skills/cron/SKILL.md
- PhyAgentOS/skills/github/SKILL.md
- PhyAgentOS/skills/image/SKILL.md
- PhyAgentOS/skills/memory/SKILL.md
- PhyAgentOS/skills/skill-creator/scripts/package_skill.py
- PhyAgentOS/skills/summarize/SKILL.md
- PhyAgentOS/skills/tmux/SKILL.md
- PhyAgentOS/skills/tmux/scripts/find-sessions.sh
- PhyAgentOS/skills/tmux/scripts/wait-for-text.sh
- PhyAgentOS/skills/weather/SKILL.md

## What its own definition files describe

- PhyAgentOS Skills
- Skill Format
- Activation and learned workflows
- Attribution
- Available Skills
- Features
- Tools
- `agent_mode`
- Usage
- ClawHub
- When to use
- Search
- Install
- Update
- List installed
- Notes
- Cron
- Three Modes
- Examples
- Time Expressions
- Timezone
- GitHub Skill
- Pull Requests
- API for Advanced Queries

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
