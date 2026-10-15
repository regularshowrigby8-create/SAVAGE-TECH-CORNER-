# Factory stage: alirezarezvani-claude-skills

Source factory: alirezarezvani/claude-skills (https://github.com/alirezarezvani/claude-skills), licence MIT,
commit 19392f7a08264ed00486a251f5b2098321771f94.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Scan
- 2: Fix
- 3: Contrast Check
- 4: Report
- 1: Audit
- 2: Optimize (if audit < industry threshold)
- 3: Publish (manual step — review the optimized variant, then deploy)
- 4: Track (over 4-12 weeks)
- 5: Report (monthly)
- .gemini/skills/pipeline/SKILL.md
- 1 — Forcing Questions (before any drafting)
- 2 — Draft (required-sections checklist)
- 3 — Prioritization hook (optional)
- 1: Discovery & Baseline
- 2: Meta Tag Audit
- 3: Content Quality & Readability
- 4: Keyword Optimization
- 5: Link Audit
- 6: Sitemap & Build
- 7: Report
- 1: gather inputs (service, sli-type, target, window, owner)
- 2: render SLO definition
- 3: compute error budget + burn-rate alerts
- 4: render the markdown SLO for peer review

## Workers this factory defines

- [`cs-aeo`](agents/marketing/cs-aeo.md)
- [`cs-webinar-marketer`](../agents/marketing/cs-webinar-marketer.md)
- .gemini/skills/persona/SKILL.md

## Checks this factory defines

- for broken relative links (`../`, `./`)
- cadence
- `test_cases`: warn if any are `pending`, `fail`, or `blocked`.

## Prompt assets this factory ships

- .gemini/skills/changelog/SKILL.md
- .gemini/skills/cmd-a11y-audit/SKILL.md
- .gemini/skills/cmd-cs-aeo/SKILL.md
- .gemini/skills/cs-webinar/SKILL.md
- .gemini/skills/okr/SKILL.md
- .gemini/skills/persona/SKILL.md
- .gemini/skills/pipeline/SKILL.md
- .gemini/skills/prd/SKILL.md
- .gemini/skills/retro/SKILL.md
- .gemini/skills/rice/SKILL.md
- .gemini/skills/seo-auditor/SKILL.md
- .gemini/skills/slo-design/SKILL.md
- .gemini/skills/sprint-plan/SKILL.md
- .gemini/skills/tc/SKILL.md
- .gemini/skills/tdd/SKILL.md
- .gemini/skills/tech-debt/SKILL.md
- .gemini/skills/user-story/SKILL.md
- .gemini/skills/wiki-ingest/SKILL.md
- .gemini/skills/wiki-init/SKILL.md
- .gemini/skills/wiki-lint/SKILL.md
- .gemini/skills/wiki-log/SKILL.md
- .gemini/skills/wiki-query/SKILL.md
- engineering/book-to-skill/skills/book-to-skill/scripts/book_to_skill/parsers/__init__.py
- marketing-skill/skills/x-twitter-growth/.gitignore

## What its own definition files describe

- /changelog
- Usage
- Examples
- Scripts
- Skill Reference
- /a11y-audit
- What It Does
- /cs:aeo — Answer Engine Optimization
- Distinct From `/cs:seo-audit`
- When To Run
- When NOT To Run
- Actions
- `audit` — Score content for AEO readiness
- `optimize` — Generate AEO-improved variant
- `track` — Log a citation you observed in an LLM response
- `report` — Aggregate citation report for a URL
- `export` — Emit citation ledger as CSV
- Minimal Intake (3 Questions)
- Workflow
- → composite score 0-100 + top fixes
- → optimized variant + changelog
- → ledger updated
- → per-URL citation report
- Industry-Specific Thresholds

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
