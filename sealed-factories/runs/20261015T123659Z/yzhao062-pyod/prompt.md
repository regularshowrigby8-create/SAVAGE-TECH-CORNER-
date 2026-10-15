# Factory stage: yzhao062-pyod

Source factory: yzhao062/pyod (https://github.com/yzhao062/pyod), licence BSD-2-Clause,
commit f0d0967d162ed46150cfa39ca61ffd1e7f78404b.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Preprocessing
- 2: Detection
- 3: Analysis
- 4: Reporting
- 5: Iteration

## Workers this factory defines

- flow

## Prompt assets this factory ships

- pyod/skills/__init__.py
- pyod/skills/od_expert/SKILL.md
- pyod/skills/od_expert/__init__.py
- pyod/skills/od_expert/references/graph.md
- pyod/skills/od_expert/references/pitfalls.md
- pyod/skills/od_expert/references/tabular.md
- pyod/skills/od_expert/references/text_image.md
- pyod/skills/od_expert/references/time_series.md
- pyod/skills/od_expert/references/workflow.md

## What its own definition files describe

- Python-package-name → Claude-Code-install-dirname mapping.
- Python package names cannot contain hyphens, but Claude Code's skill
- directory convention uses the hyphenated skill identifier from the
- SKILL.md frontmatter. When the two forms differ, record the mapping
- here so the installer writes to the canonical Claude Code directory.
- When to activate
- What you have access to
- or step-by-step:
- Master decision tree
- Top-10 critical pitfalls
- Adaptive escalation triggers
- References for depth
- Always cite your reasoning
- Graph anomaly detection reference
- Decision table by graph type (expert heuristics)
- Detectors available in PyOD (KB-derived)
- PyG installation check
- Graph data format expected by ADEngine
- Either a torch_geometric Data object:
- state.profile['data_type'] == 'graph'
- Worked example: small social-network anomaly
- Setup
- Load into a Data object (assume node_features and edges are already arrays)
- state.profile: {'data_type': 'graph', 'n_nodes': 10000,

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
