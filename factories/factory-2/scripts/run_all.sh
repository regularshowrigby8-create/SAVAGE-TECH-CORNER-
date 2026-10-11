#!/usr/bin/env bash
# Factory 2 — one deterministic job that runs every agent and check.
set -uo pipefail

critic_status=0
check_status=0

echo "=== FACTORY 2 SINGLE JOB START ==="
python3 factory-2/agents/agent_correctness.py || check_status=1
python3 factory-2/agents/agent_security.py || check_status=1
python3 factory-2/agents/agent_architecture.py || check_status=1
python3 factory-2/agents/agent_quality.py || check_status=1
python3 factory-2/agents/agent_model_debugger.py || check_status=1
python3 factory-2/agents/agent_critic.py || critic_status=1

echo "=== AGENTS COMPLETE ==="
python3 tools/generate_ai_activity_script.py || true
python3 factory-2/manager/guidance.py || check_status=1
echo "=== MANAGER REVIEW COMPLETE ==="
python3 factory-2/criticism/dead_code_check.py || check_status=1
python3 factory-2/criticism/incomplete_check.py || check_status=1
python3 factory-2/criticism/dead_buttons_check.py || check_status=1
echo "=== CRITICISM CHECKS COMPLETE ==="
echo "=== FACTORY 2 SINGLE JOB END ==="

if [ "$critic_status" -ne 0 ] || [ "$check_status" -ne 0 ]; then
  echo "FACTORY 2 RESULT: FAIL"
  exit 1
fi
echo "FACTORY 2 RESULT: PASS"
