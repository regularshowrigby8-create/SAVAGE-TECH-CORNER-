# Factory stage: disler-super-simple-software-factory

Source factory: disler/super-simple-software-factory (https://github.com/disler/super-simple-software-factory), licence MIT,
commit de31374882e7a4e3e5b7bb9bd09e69dc2f779356.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- Task

## Prompt assets this factory ships

- .claude/skills/sssf/apps/visualizer/.oxlintrc.json
- .claude/skills/sssf/apps/visualizer/index.html
- .claude/skills/sssf/apps/visualizer/package.json
- .claude/skills/sssf/apps/visualizer/src/components/DetailSection.vue
- .claude/skills/sssf/apps/visualizer/src/components/PhaseDots.vue
- .claude/skills/sssf/apps/visualizer/src/components/StatusChip.vue
- .claude/skills/sssf/apps/visualizer/src/lib/models.ts
- .claude/skills/sssf/apps/visualizer/src/lib/router.ts
- .claude/skills/sssf/apps/visualizer/src/lib/types.ts
- .claude/skills/sssf/apps/visualizer/src/main.ts
- .claude/skills/sssf/apps/visualizer/tsconfig.json
- .claude/skills/sssf/apps/visualizer/vite.config.ts
- .claude/skills/sssf/scripts/make_config.py
- .claude/skills/sssf/templates/adws/adw_build.py
- .claude/skills/sssf/templates/adws/adw_modules/agent_cc.py
- .claude/skills/sssf/templates/adws/adw_modules/prompts.py
- .claude/skills/sssf/templates/env.sample
- .claude/skills/sssf/templates/prompt_engineering/builder/system.md
- .claude/skills/sssf/templates/prompt_engineering/builder/user.md
- .claude/skills/sssf/templates/prompt_engineering/documenter/system.md
- .claude/skills/sssf/templates/prompt_engineering/planner/system.md
- .claude/skills/sssf/templates/prompt_engineering/reviewer/system.md
- .claude/skills/sssf/templates/prompt_engineering/reviewer/user.md
- .claude/skills/sssf/templates/prompt_engineering/scout/system.md

## What its own definition files describe

- /// script
- dependencies = []
- ///
- dependencies = ["pydantic", "python-dotenv", "pyyaml", "rich"]
- SSSF environment. Copy to .env at the target repo root and fill in.
- # WHICH KEYS YOU NEED DEPENDS ON YOUR ROSTER.
- Every `model:` in adws/adw_sssf_config/sssf.config.yaml is written as
- provider/model-id. The provider half decides which key must be set, and which
- key pi reads for that provider comes from ~/.pi/agent/models.json.
- # The starter roster names three providers, so it needs three keys:
- # Point every agent at one provider and you only need that provider's key.
- Setting `defaults.model` and deleting the per-agent `model:` overrides is the
- fastest way to run the whole roster on a single key.
- # Nothing validates this for you. `agents.validate()` checks that a model is
- written as provider/id, not that the provider is reachable or that its key is
- set, so a missing key shows up when that agent runs, not at startup.
- Optional overrides
- PI_PATH=pi                       # pi binary if not on PATH
- PI_MODELS_PATH=~/.pi/agent/models.json
- Builder Agent
- Purpose
- Instructions
- Build Task
- Variables

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
