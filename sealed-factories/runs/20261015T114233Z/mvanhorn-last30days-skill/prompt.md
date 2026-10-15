# Factory stage: mvanhorn-last30days-skill

Source factory: mvanhorn/last30days-skill (https://github.com/mvanhorn/last30days-skill), licence MIT,
commit f4dff26b3b14d3c55c5a9dedfc4c7c8c5df2f84a.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Start device flow
- 2: Poll for token
- 3: Fetch profile

## Workers this factory defines

- skills/last30days/agents/openai.yaml
- mode
- Mode (--agent flag)

## Checks this factory defines

- for explicit error states

## Prompt assets this factory ships

- skills/last30days/.skillignore
- skills/last30days/agents/openai.yaml
- skills/last30days/references/agent-mode.md
- skills/last30days/references/category-peers.md
- skills/last30days/references/competitors.md
- skills/last30days/references/hiring-signals.md
- skills/last30days/references/library-queue.md
- skills/last30days/scripts/build-skill.sh
- skills/last30days/scripts/compare.sh
- skills/last30days/scripts/lib/__init__.py
- skills/last30days/scripts/lib/cookie_paths.py
- skills/last30days/scripts/lib/dedupe.py
- skills/last30days/scripts/lib/fanout.py
- skills/last30days/scripts/lib/feed.py
- skills/last30days/scripts/lib/html_publish.py
- skills/last30days/scripts/lib/log.py
- skills/last30days/scripts/lib/private_files.py
- skills/last30days/scripts/lib/registers.py
- skills/last30days/scripts/lib/skill_meta.py
- skills/last30days/scripts/lib/snippet.py
- skills/last30days/scripts/lib/web_fetch_keyless.py
- skills/last30days/scripts/setup-keychain.sh
- skills/last30days/scripts/setup-pass.sh
- skills/last30days/scripts/test_device_auth.py

## What its own definition files describe

- Hermes scans from this skill directory, not the repository root.
- Research Report: {TOPIC}
- Key Findings
- What I learned
- Stats
- Category-peer expansion
- Competitor research
- Competitor mode (`--competitors`)
- Hiring signals
- Hiring Signals mode (`--hiring-signals`)
- Library, feed, and topic queue
- build-skill.sh - package this repo as a claude.ai-upload-ready .skill file
- Usage: bash skills/last30days/scripts/build-skill.sh  (run from repo root)
- # Produces dist/last30days.skill, a zip with a single top-level `last30days/`
- directory containing SKILL.md and the scripts/ runtime from skills/last30days.
- See
- docs/plans/2026-04-14-001-fix-skill-upload-200-file-limit-plan.md.
- A/B test runner: public release vs private beta
- Usage: bash skills/last30days/scripts/compare.sh "Kanye West"
- # Runs /last30days (public release) and /last30days-beta (private beta)
- sequentially with a 30s gap, saves raw results with distinct suffixes,
- prints file paths for comparison.
- Run 1: public release
- Run 2: private beta

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
