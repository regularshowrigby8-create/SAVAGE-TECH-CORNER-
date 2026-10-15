# Factory stage: xerrors-yuxi

Source factory: xerrors/Yuxi (https://github.com/xerrors/Yuxi), licence MIT,
commit ca21a30d64a58269e7c5eefffd1afe24e0ad6f62.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- backend/package/yuxi/agents/__init__.py
- backend/package/yuxi/agents/backends/__init__.py
- backend/package/yuxi/agents/backends/knowledge_base_backend.py
- backend/package/yuxi/agents/backends/sandbox/__init__.py
- backend/package/yuxi/agents/buildin/__init__.py
- backend/package/yuxi/agents/buildin/chatbot/__init__.py
- backend/package/yuxi/agents/buildin/chatbot/context.py
- backend/package/yuxi/agents/buildin/subagent/__init__.py
- backend/package/yuxi/agents/buildin/subagent/context.py
- backend/package/yuxi/agents/callbacks/__init__.py
- backend/package/yuxi/agents/mcp/builtin.py
- backend/package/yuxi/agents/middlewares/__init__.py
- backend/package/yuxi/agents/middlewares/context.py
- backend/package/yuxi/agents/presets/__init__.py
- backend/package/yuxi/agents/presets/default_chatbot.py
- backend/package/yuxi/agents/presets/subagents/__init__.py
- backend/package/yuxi/agents/presets/subagents/fact_verifier.py
- backend/package/yuxi/agents/presets/subagents/general_purpose.py
- backend/package/yuxi/agents/skills/buildin/__init__.py
- backend/package/yuxi/agents/state.py
- backend/package/yuxi/agents/toolkits/__init__.py
- backend/package/yuxi/agents/toolkits/buildin/__init__.py
- backend/package/yuxi/agents/toolkits/debug/__init__.py
- backend/package/yuxi/agents/toolkits/kbs/__init__.py

## Prompt assets this factory ships

- backend/package/yuxi/agents/skills/buildin/__init__.py
- backend/package/yuxi/services/skills/__init__.py

## What its own definition files describe

- Base classes - 核心基类
- MCP - Agent 层统一入口（自动过滤 disabled_tools）
- Tools - 核心工具函数
- Model utilities - 模型加载
- toolkits 包
- 触发各模块的 @tool 装饰器执行，自动注册工具
- 工具获取函数
- buildin 工具包
- debug 工具包

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
