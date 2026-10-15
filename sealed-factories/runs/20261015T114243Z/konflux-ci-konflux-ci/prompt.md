# Factory stage: konflux-ci-konflux-ci

Source factory: konflux-ci/konflux-ci (https://github.com/konflux-ci/konflux-ci), licence Apache-2.0,
commit 98dd1f09be416770de9bc0bf3b793b95be960a70.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- .tekton/pipelines/operator-e2e/README.md
- .tekton/pipelines/operator-e2e/pipeline.yaml
- pipelines/notify-and-trigger-github-release/README.md
- pipelines/notify-and-trigger-github-release/notify-and-trigger-github-release.yaml
- 1: Identify the CI System and Failed Run
- 2: Download Artifacts
- 3: Analyze Test Output
- 4: Analyze Cluster Logs
- 5: Inspect the Tested Commit (Optional)
- 6: Determine Root Cause and Suggest Fix
- 1: Set OPERATOR_INSTALL_METHOD=none
- 2: Deploy Cluster and Dependencies
- 3: Install CRDs and Run the Operator
- 4: Apply the Konflux CR and Wait
- 1: Set OPERATOR_INSTALL_METHOD
- 2: Run deploy-local.sh

## Checks this factory defines

- companion workflow state before commenting
- agents only** (no deterministic automation sets this):
- if user can push to upstream
- `system-resources.log` and `cluster-resources.log`
- if the failure is non-deterministic: one arch passes while the other
- whether other PRs or workflow runs were active at the same time and
- each link

## Prompt assets this factory ships

- skills/companion-pr-review/SKILL.md
- skills/create-pr/SKILL.md
- skills/debug-e2e-tests/SKILL.md
- skills/debug-e2e-tests/pac-webhook-debugging.md
- skills/debug-e2e-tests/prow-reference.md
- skills/debug-e2e-tests/reference.md
- skills/debug-e2e-tests/scripts/download-logs.sh
- skills/debug-e2e-tests/scripts/download-prow-logs.sh
- skills/dev-verify-loop/SKILL.md
- skills/dev-verify-loop/scripts/stop-operator.sh
- skills/ginkgo-testing/SKILL.md
- skills/go-toolchain-upgrade/SKILL.md
- skills/go-toolchain-upgrade/reference.md
- skills/local-dev-setup/SKILL.md
- skills/local-dev-setup/scripts/apply-konflux-cr.sh
- skills/local-dev-setup/scripts/wait-for-konflux.sh
- skills/pr-review/SKILL.md
- skills/retro-filing-policy/SKILL.md
- skills/update-upstream-deps/SKILL.md

## What its own definition files describe

- `operator-e2e-pipeline`
- Scope
- Operator process vs. test phase
- Inputs (params)
- Examples: Konflux CR (ConfigMap vs sample file)
- Examples: `integration-go-test-extra-args` / `conformance-go-test-extra-args`
- Expected Secret shapes
- `ibmcloud-credentials-secret`
- Verifying task or pipeline changes
- Resolution model
- (overrides optional) -> deprovision.
- # The deploy Task builds and runs bin/manager in-process
- operator install to keep the first upstream iteration minimal.
- # Input modes:
- konflux-cr-configmap / konflux-cr-relative-path drive deploy CR apply.
- - overrides-yaml examples:
- * PAC (from Pipelines as Code template values):
- ref: {{revision}}
- replacement: ...:on-pr-{{revision}}
- instrumented-container-image is only set for coverage-instrumented builds):
- ref: $(tasks.test-metadata.results.git-revision)
- replacement: $(tasks.test-metadata.results.container-image)
- apiVersion: tekton.dev/v1
- notify-and-trigger-github-release Pipeline

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
