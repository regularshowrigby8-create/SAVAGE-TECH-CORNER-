# Factory stage: agent-squad

Source factory: awslabs/agent-squad (https://github.com/2FastLabs/agent-squad), licence Apache-2.0,
commit 729d5f52869c8280f599a9fa5cf27fc1df445602.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- docs/src/content/docs/agents/built-in/bedrock-flows-agent.mdx
- docs/src/content/docs/agents/built-in/chain-agent.mdx
- docs/src/content/docs/agents/built-in/lambda-agent.mdx
- docs/src/content/docs/agents/built-in/lex-bot-agent.mdx
- docs/src/content/docs/agents/custom-agents.mdx
- docs/src/content/docs/swift/agents/built-in/agent.md
- docs/src/content/docs/swift/agents/custom.md
- docs/src/content/docs/swift/agents/overview.md
- python/src/agent_squad/agents/__init__.py
- python/src/agent_squad/agents/amazon_bedrock_agent.py
- python/src/agent_squad/agents/bedrock_flows_agent.py
- python/src/agent_squad/agents/bedrock_translator_agent.py
- python/src/agent_squad/agents/chain_agent.py
- python/src/agent_squad/agents/comprehend_filter_agent.py
- python/src/agent_squad/agents/lambda_agent.py
- python/src/agent_squad/agents/lex_bot_agent.py
- swift/Sources/AgentSquad/Agents/GroundedAgent.swift
- typescript/src/agents/amazonBedrockAgent.ts
- typescript/src/agents/bedrockFlowsAgent.ts
- typescript/src/agents/bedrockTranslatorAgent.ts
- typescript/src/agents/chainAgent.ts
- typescript/src/agents/lambdaAgent.ts
- typescript/src/agents/lexBotAgent.ts
- typescript/src/agents/openAIAgent.ts

## Checks this factory defines

- if input is a number and return it as-is if true

## What its own definition files describe

- Overview
- Key Features
- Creating a BedrockFlowsAgent
- Python Package
- Basic Example
- Flow Input Encoder callback
- Sample Code
- Creating a ChainAgent
- Intermediate Example
- Advanced Example
- Integrating ChainAgent into the Agent Squad
- Streaming Responses
- Creating a LambdaAgent
- Parameter Explanations
- Adding the Agent to the Orchestrator
- Creating a LexBotAgent
- Basic Structure of a Custom Agent
- Example: OpenAI Agent
- Initializers
- Default system prompt
- Custom or absent system prompt
- Parameters
- UIPolicy
- Tool-round cap

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
