# Factory stage: microsoft-agent-framework

Source factory: microsoft/agent-framework (https://github.com/microsoft/agent-framework), licence MIT,
commit abeed6c62a8cc70e796334a5b490c8e94621f89d.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- dotnet/samples/02-agents/Agents/Agent_Step01_UsingFunctionToolsWithApprovals/Agent_Step01_UsingFunctionToolsWithApprovals.csproj
- dotnet/samples/02-agents/Agents/Agent_Step02_StructuredOutput/Agent_Step02_StructuredOutput.csproj
- dotnet/samples/02-agents/Agents/Agent_Step03_PersistedConversations/Agent_Step03_PersistedConversations.csproj
- dotnet/samples/02-agents/Agents/Agent_Step10_BackgroundResponsesWithToolsAndPersistence/Agent_Step10_BackgroundResponsesWithToolsAndPersistence.csproj
- dotnet/samples/02-agents/Agents/Agent_Step13_ChatReduction/Agent_Step13_ChatReduction.csproj
- dotnet/samples/02-agents/Agents/Agent_Step14_BackgroundResponses/Agent_Step14_BackgroundResponses.csproj
- dotnet/samples/02-agents/Agents/Agent_Step17_AdditionalAIContext/Agent_Step17_AdditionalAIContext.csproj
- dotnet/samples/02-agents/Agents/Agent_Step18_CompactionPipeline/Agent_Step18_CompactionPipeline.csproj
- dotnet/samples/02-agents/Agents/Agent_Step19_InFunctionLoopCheckpointing/Agent_Step19_InFunctionLoopCheckpointing.csproj
- dotnet/samples/02-agents/Agents/Agent_Step20_DynamicFunctionTools/Agent_Step20_DynamicFunctionTools.csproj
- dotnet/samples/02-agents/Agents/Agent_Step22_AgentMode/Agent_Step22_AgentMode.csproj
- dotnet/samples/02-agents/Agents/Agent_Step23_TodoList/Agent_Step23_TodoList.csproj
- dotnet/samples/02-agents/Agents/Agent_Step24_MultiModelRouting/Agent_Step24_MultiModelRouting.csproj
- dotnet/samples/05-end-to-end/M365Agent/Agents/WeatherForecastAgentResponseContentType.cs
- dotnet/src/Shared/Foundry/Agents/README.md
- python/packages/devui/frontend/src/components/features/agent/index.ts
- Feature - Exports
- python/packages/devui/frontend/src/components/features/agent/message-renderers/index.ts

## Prompt assets this factory ships

- dotnet/samples/02-agents/AgentSkills/Agent_Step01_FileBasedSkills/skills/unit-converter/references/conversion-table.md
- dotnet/samples/02-agents/AgentSkills/Agent_Step04_MixedSkills/skills/unit-converter/references/unit-conversion-table.md
- dotnet/samples/02-agents/AgentSkills/Agent_Step07_SkillsAutoApproval/skills/unit-converter/references/conversion-table.md
- dotnet/src/Microsoft.Agents.AI.Mcp/Skills/Loaders/ArchiveFormat.cs
- python/samples/02-agents/skills/file_based_skill/skills/unit-converter/references/CONVERSION_TABLES.md
- python/samples/02-agents/skills/mixed_skills/skills/unit-converter/references/CONVERSION_TABLES.md

## What its own definition files describe

- Conversion Tables
- Foundry Agents
- Copyright (c) Microsoft. All rights reserved.

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
