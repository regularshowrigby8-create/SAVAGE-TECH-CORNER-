# Factory stage: disler-inkwell-agent-sandboxes-and-software-factory

Source factory: disler/inkwell-agent-sandboxes-and-software-factory (https://github.com/disler/inkwell-agent-sandboxes-and-software-factory), licence MIT,
commit 92f1701810993b8303562265ba04c727468fe070.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- .claude/skills/sandbox-exe-dev/exedev_cli/pyproject.toml
- .claude/skills/sssf/apps/visualizer/.oxlintrc.json
- .claude/skills/sssf/apps/visualizer/index.html
- .claude/skills/sssf/apps/visualizer/src/lib/types.ts
- .claude/skills/sssf/apps/visualizer/src/main.ts
- .claude/skills/sssf/apps/visualizer/vite.config.ts
- .claude/skills/sssf/templates/adws/adw_modules/agent_cc.py
- .claude/skills/sssf/templates/adws/adw_modules/prompts.py
- .claude/skills/sssf/templates/prompt_engineering/scout/user.md
- prompts/01-fts5-search.md
- prompts/02-revision-history.md
- prompts/03-scheduled-publishing.md
- prompts/04-export-import.md
- prompts/05-public-permalink.md
- prompts/06-redesign-quiet-room.md
- prompts/07-redesign-editorial.md
- prompts/08-redesign-progressive-focus.md
- prompts/README.md
- prompts/app-breakdown-scout.md
- prompts/demo/01-fan-out-five-rosters.md
- prompts/demo/02-shell-access.md
- prompts/demo/03-agent-access.md
- prompts/light-mode-theming.md
- prompts/scout-app-breakdown.md

## What its own definition files describe

- Scout Task
- Variables
- prompt
- previous_envelope
- context_handoff_dir
- Task
- Report
- prompts
- Fire one

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
