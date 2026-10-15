# Factory stage: openhands

Source factory: All-Hands-AI/OpenHands (https://github.com/OpenHands/OpenHands), licence MIT,
commit bf483c12efbfae5c0ab586293d8bbc92f712bbed.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- .agents/skills/desktop-electron/SKILL.md
- .agents/skills/e2e-testing/SKILL.md
- .agents/skills/frontend-api-contracts/SKILL.md
- .agents/skills/frontend-development/SKILL.md
- .agents/skills/local-stack-runtime/SKILL.md
- .agents/skills/telemetry-analytics/SKILL.md
- .agents/skills/verify-openhands/scripts/control-openhands
- .agents/skills/verify-openhands/scripts/lib/agent-profile-repoint.mjs
- .agents/skills/verify-openhands/scripts/lib/baseline.mjs
- .agents/skills/verify-openhands/scripts/lib/build-id.mjs
- .agents/skills/verify-openhands/scripts/lib/build-inputs.mjs
- .agents/skills/verify-openhands/scripts/lib/call-limit.mjs
- .agents/skills/verify-openhands/scripts/lib/events-paging.mjs
- .agents/skills/verify-openhands/scripts/lib/redact-storage.mjs
- .agents/skills/verify-openhands/scripts/lib/route-pattern.mjs
- .agents/skills/verify-openhands/scripts/lib/tmux-path.mjs
- src/components/features/skills/get-skill-card-description.ts
- src/components/features/skills/get-skill-chat-launch-message.ts
- src/components/features/skills/is-copyable-skill-source.ts
- src/components/features/skills/skill-facet-rail.tsx
- src/components/features/skills/skill-facet-row.tsx
- src/components/features/skills/skill-filters-modal.tsx
- src/components/features/skills/skill-icon-badge.tsx
- src/components/features/skills/skill-type-badge.tsx

## What its own definition files describe

- Electron Desktop
- Workflow
- Reference
- End-to-End Testing
- Frontend API and Backend Contracts
- Frontend Development
- Local Stack and Runtime Services
- Telemetry and Analytics
- Thin wrapper so recipes can call `control-openhands ...` once this directory
- is on PATH. All logic lives in control-openhands.mjs.

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
