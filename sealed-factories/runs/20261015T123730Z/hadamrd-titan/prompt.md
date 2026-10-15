# Factory stage: hadamrd-titan

Source factory: hadamrd/titan (https://github.com/hadamrd/titan), licence MIT,
commit ee8c783d751401f1c1dddbfaf6e81a4e5c98ee7d.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- e2e/pipelines/include-shared/shared/lint-test.yml
- stage: lint
- steps
- sh: ./scripts/lint.sh
- stage: test
- dependsOn
- sh: ./scripts/test.sh
- lint
- test
- e2e/pipelines/include-shared/titan-pipeline-cross-repo.yml
- stage: deploy
- sh: ./scripts/deploy.sh
- deploy
- e2e/pipelines/include-shared/titan-pipeline.yml
- e2e/pipelines/java-matrix/README.md
- e2e/pipelines/java-matrix/titan-pipeline.yml
- stage: build
- matrix
- fail_fast
- axes
- jdk
- profile
- sh: |
- build

## Workers this factory defines

- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/LogSink.java
- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/OutputSink.java
- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/ParamSpec.java
- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/StepApi.java
- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/StepHandlerContext.java
- titan-step-api/src/main/java/io/adaptiq/titan/worker/step/StepHandlerProvider.java
- titan-step-api/src/testFixtures/java/io/adaptiq/titan/worker/step/BrokenProvider.java
- titan-step-api/src/testFixtures/java/io/adaptiq/titan/worker/step/FixtureProvider.java
- titan-step-api/src/testFixtures/java/io/adaptiq/titan/worker/step/FixtureStepHandler.java
- titan-worker/src/main/java/io/adaptiq/titan/worker/DbLogSink.java
- titan-worker/src/main/java/io/adaptiq/titan/worker/step/builtin/ErrorStepHandler.java

## What its own definition files describe

- Shared lint+test prelude (issue #1120 fixture).
- Cross-repo include fixture (issue #1120).
- secret in the secret manager (design/40) — NEVER a plaintext token.
- Main pipeline that pulls in the shared lint+test prelude (issue #1120 fixture).
- titan-pipeline.yml I will not migrate."
- java-matrix fixture (issue #1127)
- Fixture pipeline for issue #1127 — matrix step expansion.
- # Shape:
- build (matrix on {jdk: [17, 21], profile: [unit, it]}) -> 4 parallel cells
- # Invariants the e2e spec asserts:
- * 4 cell stages render in the UI, one per cartesian point of jdk × profile.
- * Each cell's log contains the MATRIX_JDK / MATRIX_PROFILE env values for
- that cell — proving env injection threads through to the worker.
- * fail_fast: false is honoured — sibling cells run to completion even if
- one cell deliberately fails (the (jdk=21, profile=it) cell is rigged
- to exit non-zero), and the parent verdict rolls up as FAILED with the
- other 3 cells still reporting SUCCESS.
- # We use plain `sh` rather than a real JDK probe so the rig (which may not
- have multiple JDKs installed) can still run this fixture green. The matrix
- semantics — fan-out, env injection, verdict roll-up — are what's being
- validated, not the JDK toolchain itself.
- Fixture pipeline for issue #1264 — live build-log streaming.
- stream -> emit 6 lines, one per second, then exit 0 (SUCCESS).
- # Invariants the e2e spec (`e2e/specs/live-log-stream.spec.ts`) asserts:

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
