# Factory stage: aipoch-open-science

Source factory: aipoch/open-science (https://github.com/aipoch/open-science), licence Apache-2.0,
commit ff3876892b2d7379cc2fcfd683631b452151d243.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- src/main/agents/catalog-contract.ts

## Prompt assets this factory ships

- packages/skills/.gitignore
- packages/skills/src/frontmatter.ts
- packages/skills/tsconfig.dev.json
- packages/skills/tsconfig.test.json
- packages/skills/vitest.config.ts
- resources/skills/alphafold2/.catalog_stamp
- resources/skills/boltz/.catalog_stamp
- resources/skills/borzoi/.catalog_stamp
- resources/skills/chai1/.catalog_stamp
- resources/skills/diffdock/.catalog_stamp
- resources/skills/esmfold2/.catalog_stamp
- resources/skills/evo2/.catalog_stamp
- resources/skills/fair-esm2/.catalog_stamp
- resources/skills/indication-dossier/.catalog_stamp
- resources/skills/ligandmpnn/.catalog_stamp
- resources/skills/literature-review/.catalog_stamp
- resources/skills/openfold3/.catalog_stamp
- resources/skills/paper-narrative/open-science.json
- resources/skills/proteinmpnn/.catalog_stamp
- resources/skills/scgpt/.catalog_stamp
- resources/skills/scvi-tools/.catalog_stamp
- resources/skills/skill-creator/package.json
- resources/skills/skill-creator/scripts/index.js
- resources/skills/solublempnn/.catalog_stamp

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
