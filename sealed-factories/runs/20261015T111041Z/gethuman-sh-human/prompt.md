# Factory stage: gethuman-sh-human

Source factory: gethuman-sh/human (https://github.com/gethuman-sh/human), licence MIT,
commit 11be4294a815776fc5db14289b527f87d5ca0942.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- internal/pipeline/README.md
- Runtime
- internal/pipeline/pipeline.go
- internal/pipeline/pipeline_test.go

## Workers this factory defines

- internal/agent/README.md
- internal/agent/agent.go
- internal/agent/agent_test.go
- internal/agent/agentlog_regression_test.go
- internal/agent/agentlog_sc4820_test.go
- internal/agent/agentlog_stream_test.go
- internal/agent/botenv_test.go
- internal/agent/diagnose.go
- internal/agent/diagnose_test.go
- internal/agent/docker_error_test.go
- internal/agent/manager_release_branch_test.go
- internal/agent/prune.go
- internal/agent/prune_test.go
- internal/agent/stoppedboard.go
- internal/agent/stoppedboard_test.go
- internal/agent/ticket.go
- internal/agent/transcript.go
- internal/agent/transcript_test.go
- internal/agent/worktree.go

## What its own definition files describe

- AI Developer Agents
- Run Capabilities
- Why it runs locally
- Conventional use

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
