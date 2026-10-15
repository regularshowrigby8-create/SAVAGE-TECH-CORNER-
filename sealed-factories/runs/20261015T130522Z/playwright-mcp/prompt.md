# Factory stage: playwright-mcp

Source factory: microsoft/playwright-mcp (https://github.com/microsoft/playwright-mcp), licence Apache-2.0,
commit b8b4183e099f136cbec0388a6088d4aa2f6b9685.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- .claude/skills/release.md

## What its own definition files describe

- Preparing a Release
- 1. Roll Playwright
- 2. Bump the version
- 3. Find the exact commit window
- Resolve each alpha to the exact upstream commit it was built from
- 4. Collect changes
- This repo
- 5. Write `release-notes.md`
- ✨ Highlights
- 🐛 Fixes
- 📦 Upgrading
- 6. Commit, push, open the PR with the notes as its body
- Pitfalls

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
