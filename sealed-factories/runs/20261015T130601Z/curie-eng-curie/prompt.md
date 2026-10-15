# Factory stage: curie-eng-curie

Source factory: curie-eng/curie (https://github.com/curie-eng/curie), licence Apache-2.0,
commit e6c501fda5326ead0226f1a0dab3c2c284f9efd8.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- apps/worker/Dockerfile.dockerignore
- apps/worker/pyproject.toml
- apps/worker/schema/eval-cases.example.json
- apps/worker/schema/trajectory.schema.json
- apps/worker/src/curie_connector_proxy/__init__.py
- apps/worker/src/curie_connector_proxy/__main__.py
- apps/worker/src/curie_worker/__init__.py
- apps/worker/src/curie_worker/__main__.py
- apps/worker/src/curie_worker/action_wording.py
- apps/worker/src/curie_worker/api_retry.py
- apps/worker/src/curie_worker/caller_token.py
- apps/worker/src/curie_worker/connector_grant.py
- apps/worker/src/curie_worker/dead_letter_alert.py
- apps/worker/src/curie_worker/eval/__init__.py
- apps/worker/src/curie_worker/eval/__main__.py
- apps/worker/src/curie_worker/eval/trajectory.py
- apps/worker/src/curie_worker/heartbeat.py
- apps/worker/src/curie_worker/mrkdwn.py
- apps/worker/src/curie_worker/sandbox/__init__.py
- apps/worker/src/curie_worker/schema_startup.py
- apps/worker/src/curie_worker/slack_tokens.py

## Prompt assets this factory ships

- docs/adr/evidence/0143-custom-connector-spike/bundle/skills/stubfin/SKILL.md
- examples/github-issues/skills/github-issues/SKILL.md
- examples/text-stats-engine/skills/text-stats/SKILL.md
- examples/weather/skills/weather/SKILL.md

## What its own definition files describe

- Per-Dockerfile ignore (BuildKit honors <dockerfile>.dockerignore over the
- context-root .dockerignore). These app images build from the REPO ROOT to
- reach the uv workspace, so this trims the root context to what the Python
- build needs -- the uv.lock, member pyproject.tomls, and Python sources stay;
- the heavy non-Python trees are dropped.
- A filename or path containing an identifier is content, not a tool reference.
- A period followed by whitespace, closing punctuation or end is sentence prose.
- ADR 0178 decision 1: lowercase hyphenated UUID text, one spelling.
- Fenced code blocks (```...```) are copied verbatim. Captured so re.split keeps
- the fences in the output stream.
- Inline code spans (`...`) within a non-fenced segment, likewise protected.
- **bold** -> *bold* (Slack bold is a single asterisk).
- [text](url) -> <url|text>.
- ATX headings (# .. ######) -> a bold line; Slack has no heading syntax.
- List bullets (- item / * item) -> the bullet character; Slack mrkdwn has no
- list syntax. Leading whitespace is preserved so nested bullets stay indented.
- The trailing space is required, which excludes *italic* (no space after *).
- Invoices
- How to answer
- GitHub issue triage
- When to run
- Notes
- Text statistics
- Weather

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
