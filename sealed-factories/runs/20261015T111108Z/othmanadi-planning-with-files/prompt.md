# Factory stage: othmanadi-planning-with-files

Source factory: OthmanAdi/planning-with-files (https://github.com/OthmanAdi/planning-with-files), licence MIT,
commit ff349ebe322b4f86c3416a6fbcde515f1de32e8b.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- status values

## Checks this factory defines

- whether the project root already has task_plan.md / findings.md / progress.md

## Prompt assets this factory ships

- .agents/skills/planning-with-files/templates/findings.md
- .codebuddy/skills/planning-with-files/templates/findings.md
- .codex/skills/planning-with-files/templates/findings.md
- .continue/prompts/planning-with-files.prompt
- .cursor/skills/planning-with-files/templates/findings.md
- .factory/skills/planning-with-files/templates/findings.md
- .gemini/skills/planning-with-files/templates/findings.md
- .hermes/skills/planning-with-files/scripts/check-continue.sh
- .hermes/skills/planning-with-files/templates/findings.md
- .kiro/skills/planning-with-files/assets/templates/findings.md
- .kiro/skills/planning-with-files/assets/templates/planning-context.md
- .kiro/skills/planning-with-files/references/planning-rules.md
- .mastracode/skills/planning-with-files/templates/findings.md
- .opencode/skills/planning-with-files/templates/findings.md
- .pi/skills/planning-with-files/extensions/planning-with-files/README.md
- .pi/skills/planning-with-files/extensions/planning-with-files/attestation.ts
- .pi/skills/planning-with-files/extensions/planning-with-files/index.ts
- .pi/skills/planning-with-files/extensions/planning-with-files/package.json
- .pi/skills/planning-with-files/scripts/verify-shell-line-endings.mjs
- .pi/skills/planning-with-files/templates/findings.md
- skills/i18n/planning-with-files-zh/templates/findings.md
- skills/i18n/planning-with-files-zh/templates/progress.md
- skills/i18n/planning-with-files-zht/templates/findings.md
- skills/i18n/planning-with-files-zht/templates/progress.md

## What its own definition files describe

- Findings & Decisions
- Requirements
- Research Findings
- Technical Decisions
- Issues Encountered
- Resources
- Visual/Browser Findings
- Current Task Plan
- Recent Progress
- Planning rules (full reference)
- The 3-Strike Error Protocol
- Read vs Write Decision Matrix
- The 5-Question Reboot Test
- Error log (in `task_plan.md`)
- Errors Encountered
- planning-with-files Pi Extension
- Events mapped
- Modes
- 发现与决策
- 研究发现
- 技术决策
- 遇到的问题
- 视觉/浏览器发现
- 进度日志

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
