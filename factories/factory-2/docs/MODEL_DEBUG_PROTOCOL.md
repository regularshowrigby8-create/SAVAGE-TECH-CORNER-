# Factory 2 Model Debug & Main Agent Implementation Protocol

## 1. Overview

This protocol defines how 50+ models (offline and online) collaborate to debug and build applications within Factory 2 without compromising code stability or violating the Charter.

## 2. Model Catalog (50+ Configured Models)

All models are declared and verified in `models/free-models.json`.

- **35 Local / Offline Coding Models:** Zero API keys required; runnable locally via Ollama CLI specs (e.g. Qwen2.5-Coder series, DeepSeek-R1-Distill series, DeepSeek-Coder, StarCoder2, Granite-Code, CodeLlama, Yi-Coder).
- **25 Online Free/Trial Models:** Multi-provider API routing across Groq, OpenRouter, NVIDIA NIM, GitHub Models, LLM7, Mistral, Cohere, xAI, Together AI, Cerebras, Fireworks, and Cloudflare.

## 3. Strict Non-Mutating Debug Rule

When any model analyzes and debugs application code:

1. **Never mutate source code in-place:** Models are strictly prohibited from writing directly to `factory-2/app/src/main.py` or other app files.
2. **Create isolated proposals:** The model outputs a candidate patch file and a JSON metadata manifest into `factory-2/debug/proposals/`.
3. **Structured Explanation:** Every proposal must contain:
   - Root cause and classification of the defect.
   - Detailed justification of why the code was changed.
   - Unified line-by-line diff.
   - SHA-256 integrity hash of the original file.

## 4. Main Agent Verification & Implementation

The Main Agent (`factory-2/scripts/main_agent_verifier.py`) acts as the lead gatekeeper:

1. Validates that the original file has not drifted.
2. Runs AST syntax parsing and compile checks on the candidate code.
3. Checks for regressions (no dead buttons, no unhandled exceptions, no credentials).
4. **Implementation:** Only upon successful verification does the Main Agent apply the candidate code into the application codebase and mark the proposal `IMPLEMENTED`.
5. Rejections are marked `REJECTED` with explicit diagnostic feedback.

## 5. Automation & Pipeline Wiring

- `factory-2/scripts/model_debugger.py` executes model analysis.
- `factory-2/scripts/main_agent_verifier.py` executes main agent verification and patch implementation.
- `factory-2/agents/agent_model_debugger.py` integrates the audit into `factory-2/scripts/run_all.sh`.
