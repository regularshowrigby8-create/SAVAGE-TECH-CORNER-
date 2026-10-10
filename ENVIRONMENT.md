# FULL ENVIRONMENT DOCUMENTATION

Branch: `factory-2` (session fixed to `arena/ac306e97-savage-tech-corner`)

## What Exists (verified by ls/cat)

- `docs/factory/CHARTER.md` — global rules
- `docs/factory/CONTRACT.md` — queue protocol
- `.claude/scripts/gates.sh` — gate with provenance check (Rule #1)
- `.claude/agents/` — critic and verifier agents
- `.claude/skills/` — triage, spec, implement, verify, monitor skills
- `.factory/scripts/doctor.sh` — verifies `.claude/skills/` and remote
- `.github/workflows/factory-2-debug.yml` — single job that runs `run_all.sh`
- `factory-2/agents/agent_*.py` — 5 real agents (scanning logic, not stubs)
- `factory-2/manager/guidance.py` — reads real reports, writes evidence-based responses
- `factory-2/criticism/dead_code_check.py`, `incomplete_check.py`, `dead_buttons_check.py`
- `factory-2/apk-analysis/apk_scanner.py` — APK/bytecode patterns (morphe-ai reference)
- `models/free-models.json` — 12 online + 6 offline coding-capable models (real URLs, benchmarks, licenses)
- `NO_PADDING.md` — enforcement file

## What Does NOT Exist on This Branch

- `workspace/code/debug_pipeline.py` — lives on `factory-1` branch only
- `workspace/code/test_debug_pipeline.py` — lives on `factory-1` branch only
- `multi-agents/workspaces/agent-01.md...agent-50.md` — removed as padding; does not exist

## Verification Commands (run these before trusting any claim)

```bash
bash .claude/scripts/gates.sh        # Must return VERDICT: PASS
bash factory-2/scripts/run_all.sh   # Must return exit 0
python3 factory-2/agents/agent_critic.py  # Must reference real files
ls -l models/free-models.json factory-2/apk-analysis/apk_scanner.py
```

All claims in this file must be verifiable by running the commands above or by running `ls`/`cat` on the named path.
