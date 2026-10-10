#!/bin/bash
# Factory 2 — Single Job Execution Script
# Runs all agents, then manager, then criticism checks.

set -euo pipefail

echo "=== FACTORY 2 SINGLE JOB START ==="

python3 factory-2/agents/agent_correctness.py
python3 factory-2/agents/agent_security.py
python3 factory-2/agents/agent_architecture.py
python3 factory-2/agents/agent_quality.py
python3 factory-2/agents/agent_critic.py

echo "=== AGENTS COMPLETE ==="

python3 factory-2/manager/guidance.py

echo "=== MANAGER REVIEW COMPLETE ==="

python3 factory-2/criticism/dead_code_check.py
python3 factory-2/criticism/incomplete_check.py
python3 factory-2/criticism/dead_buttons_check.py

echo "=== CRITICISM CHECKS COMPLETE ==="

echo "=== FACTORY 2 SINGLE JOB END ==="
