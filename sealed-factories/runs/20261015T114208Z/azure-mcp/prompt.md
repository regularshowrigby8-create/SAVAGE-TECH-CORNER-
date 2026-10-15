# Factory stage: azure-mcp

Source factory: microsoft/mcp (https://github.com/microsoft/mcp), licence MIT,
commit 5b7cde21db880a3dbc23b39e1ef0eda8237b8db3.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- Generate SDKs
- SDK release plan
- Release SDK Package
- 1: Provide Information
- 2: Get Service Label
- 3: Validate Label
- 4: Create New Service Label
- eng/common/pipelines/templates/steps/devops-variables-clear.yml
- pwsh: |
- continueOnError
- displayName
- eng/common/pipelines/templates/steps/docsms-ensure-validation.yml
- condition
- eng/common/pipelines/templates/steps/enable-long-path-support.yml
- eng/common/pipelines/templates/steps/set-daily-docs-branch-name.yml
- eng/common/pipelines/templates/steps/verify-agent-os.yml
- task: PowerShell@2
- inputs
- pwsh
- workingDirectory
- filePath
- arguments
- eng/common/pipelines/templates/steps/verify-changelogs.yml
- eng/common/pipelines/templates/steps/write-filesystemmetrics.yml

## Workers this factory defines

- tools/Azure.Mcp.Tools.SreAgent/src/Options/Agents/AgentsDeleteOptions.cs
- tools/Azure.Mcp.Tools.SreAgent/src/Options/Agents/AgentsToolsGetOptions.cs

## Prompt assets this factory ships

- eng/common/instructions/azsdk-tools/typespec-docs.instructions.md
- eng/common/instructions/azsdk-tools/typespec-to-sdk.instructions.md
- eng/common/instructions/azsdk-tools/validate-service-label.instructions.md
- eng/common/instructions/azsdk-tools/verify-setup.instructions.md
- eng/tools/ToolDescriptionEvaluator/prompts/consolidated-prompts.json
- eng/tools/ToolDescriptionEvaluator/prompts/namespace-prompts.json
- eng/tools/ToolDescriptionEvaluator/prompts/namespace-tools.json
- tools/Azure.Mcp.Tools.SreAgent/src/Options/Skills/SkillsDeleteOptions.cs
- tools/Fabric.Mcp.Tools.OneLake/src/Prompts/OneLakePrompts.cs

## What its own definition files describe

- Fail the build if any of the packages failed validation. Valid values are
- "true" or "false". This step will skip if $(DocsMsPackagesAllValid) is not set

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
