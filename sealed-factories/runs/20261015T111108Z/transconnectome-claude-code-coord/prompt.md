# Factory stage: transconnectome-claude-code-coord

Source factory: Transconnectome/claude-code-coord (https://github.com/Transconnectome/claude-code-coord), licence MIT,
commit 6e9ca4218b734d46189d02deaaf2aa4b0c44c55a.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1 — Clone this repository (저장소 복사)
- 2 — Copy framework files (프레임워크 파일 복사)
- 3 — Install MCP servers, then verify (MCP 서버 설치 및 검증)
- by-Step (단계별 설치)
- workflow (8단계 워크플로우):**
- Overview (파이프라인 개요)
- 0 — Bootstrap (준비 단계)
- 1 — Memory Pre-Check (메모리 사전 확인)
- 2 — Plan (계획 단계)
- 3 — Research (리서치 단계)
- 4 — Execute (실행 단계)
- 5 — Review (리뷰 단계)
- 6 — Memory Update (메모리 업데이트 단계)

## Workers this factory defines

- Selection Quick Reference (에이전트 선택 빠른 참조)
- selection by task type (작업 유형별 에이전트 선택):**

## Checks this factory defines

- Phase: critic agent + (선택) Premium model
- 검토 생략 (Opus generator) → 5단계 강제 실행
- report with pass/fail per criterion (기준별 통과/실패가 있는 검토 보고서)
- findings (검토 결과)

## Prompt assets this factory ships

- .claude/skills/coord/SKILL.md
- .claude/skills/coord/memory_scan.sh

## What its own definition files describe

- coord — Intelligent Complex Task Workflow
- 호출 방식
- 1. 명시적 호출 (recommended)
- 2. Skill 자동 활성화
- 3. 자동 판단 (heuristic)
- 워크플로우 6단계 (요약)
- 도구 매핑
- Memory Pre-Check 헬퍼
- 안티 패턴 차단
- 비활성화 방법
- 검증 체크리스트
- 관련 파일
- memory_scan.sh — Auto-memory keyword search helper for /coord workflow.
- Usage: bash memory_scan.sh "<task description or keywords>"
- Output: Matched MEMORY.md entries + related feedback/lesson files.
- Extract 2-4 keywords (split on space, drop short/common words)
- Simple tokenizer — splits on whitespace + Korean particles.
- 1. Search MEMORY.md index for any keyword
- 2. Search feedback files (sycophancy/usage feedback patterns)
- 3. Search project files (active work context)
- 4. Recent lessons (last 7 days)
- Quick Start (빠른 시작)
- Architecture (아키텍처)
- 6-Stage Pipeline

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
