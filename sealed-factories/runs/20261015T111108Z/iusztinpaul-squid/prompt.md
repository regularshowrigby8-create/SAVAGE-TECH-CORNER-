# Factory stage: iusztinpaul-squid

Source factory: iusztinpaul/squid (https://github.com/iusztinpaul/squid), licence Apache-2.0,
commit f5bf6b3e001aa4745917d67636f2cdb775467bbb.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1 — Frame the scope
- 2 — Read prior decisions
- 3 — Map the current architecture
- 4 — Identify smells
- 5 — Score and prioritise
- 6 — Write the report
- 7 — Hand off
- 1 — Confirm scope
- 2 — Apply the keep/delete test
- 3 — Plan of attack (the output artifact)
- 4 — Execute
- 5 — Verify
- 6 — Hand-off
- 1 — Resolve the target set
- 2 — Audit the contract (frontmatter)
- 3 — Apply the keep/delete test
- 4 — Dangling artifacts
- 5 — Plan of attack (the output artifact)
- 6 — Execute
- 7 — Verify
- 8 — Hand-off
- 2 — Compress wording (caveman pass)
- 4 — Plan of attack (the output artifact)
- 5 — Execute

## Workers this factory defines

- agents/oncall-engineer.md
- agents/tester.md

## Checks this factory defines

- Criteria
- criteria**
- criteria:**
- CI — validate the pushed PR's pipeline
- — push, PA acceptance, PR review
- * `wc -l AGENTS.md` ≤ 250.
- * every stack bullet has a matched open/close comment pair with the same slug.

## Prompt assets this factory ships

- skills/squid-architecture-review/SKILL.md
- skills/squid-clean-docs/SKILL.md
- skills/squid-clean-harness/SKILL.md
- skills/squid-clean-memory/SKILL.md
- skills/squid-grilling/SKILL.md
- skills/squid-implement-night/SKILL.md
- skills/squid-implement-task/SKILL.md
- skills/squid-plan/SKILL.md
- skills/squid-refactor/SKILL.md
- skills/squid-review-ci/SKILL.md
- skills/squid-review/SKILL.md
- skills/squid-scaffold/AGENTS_TEMPLATE.md
- skills/squid-scaffold/SKILL.md
- skills/squid-scaffold/evaluate.md
- skills/squid-scaffold/rules.md
- skills/squid-testing-python/SKILL.md
- skills/squid-triage-issue/SKILL.md
- skills/squid-write-skill/SKILL.md

## What its own definition files describe

- On-Call Engineer Agent
- Trigger
- Workflow
- 1. Check the latest workflow runs
- 2. Pull the failure logs
- 3. Identify the responsible task
- 4. Reopen the task and log the failure
- [On-Call] YYYY-MM-DD HH:MM — CI Failure
- 5. Diagnose and hand a fix task to the SWE
- 6. Verify after the SWE's fix
- 7. Close the loop
- 8. Report to orchestrator
- Rules
- Tester Agent
- Input
- 1. Understand what was expected
- 2. Review the uncommitted code
- 3. Run the full local suite
- 3b. E2E adversarial QA pass — your headline duty
- 4. Verify each acceptance criterion with evidence
- QA Review for #{N}
- Documentation-update ACs (when `docs/adr/` or `docs/glossary.md` exists)
- 5. Update the acceptance-criteria checkboxes
- 6. Append your log entry

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
