# Factory stage: shawncsx-autosci

Source factory: shawncsx/AutoSCI (https://github.com/shawncsx/AutoSCI), licence MIT,
commit f87124ddada95c9374757828ece8c89cbdc4ed43.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- autosci/agents/__init__.py
- autosci/agents/_base.py
- autosci/agents/citation_grounder.py
- autosci/agents/code_sketcher.py
- autosci/agents/exp_designer.py
- autosci/agents/idea_closer.py
- autosci/agents/idea_miner.py
- autosci/agents/lit_surveyer.py
- autosci/agents/method_designer.py
- autosci/agents/paper_writer.py
- autosci/agents/refuter.py
- autosci/agents/reviewer.py
- autosci/agents/section_validator.py

## What its own definition files describe

- AutoSCI — 分布式研究 Agent 流水线 / Distributed Research-Agent Pipeline
- ✨ 三大亮点
- 1️⃣ 调度官 + 子 agent 集群（10 个）
- 2️⃣ 5 源论文库（k 越高越丰富）
- 3️⃣ Claude-Code 风格终端 UI（Python `rich` 实现）
- 🛞 两种运行模式
- A. 单次 demo（5 个 idea，约 12-15 分钟）
- B. Autopilot 连续自循环（生产模式）
- 默认 5 个 seed + 持续从 KB 挖新 idea
- 限制版本
- Mass production（拉 5 源 + Wide IdeaMiner + parallel Director）
- 📦 仓库结构
- 🚀 5 分钟上手
- 1. 安装
- 2. 配置
- 默认：
- AGNES_BASE_URL=https://api.agnes-ai.cn/v1
- AGNES_MODEL=agnes-3.0-flash
- 修改你的 API key
- 3. 准备语料
- 4. 跑
- Demo（5 个 idea，约 12-15 分钟）
- 限数量
- 一键 mass：5 源 + 20 idea + parallel=4

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
