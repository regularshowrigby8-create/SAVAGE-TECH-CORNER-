# FULL ENVIRONMENT DOCUMENTATION

## Branches
- `main` — Rules, contracts, gates
- `arena/ac306e97-savage-tech-corner` — Development work
- `factory-1` — My personal code space
- `factory-2` — Debug environment (single job)

## Key Files

| File | Branch | Purpose |
|---|---|---|
| docs/factory/CHARTER.md | main | Global rules |
| docs/factory/CONTRACT.md | main | Queue protocol |
| .claude/scripts/gates.sh | main | Quality gate |
| .github/workflows/factory-2-debug.yml | main | ONE job only |
| workspace/code/debug_pipeline.py | factory-1 | My code |
| workspace/notion/notion_setup.py | factory-1 | Notion integration |
| workspace/linear/linear_sync.py | factory-1 | Linear integration |
| workspace/gmail/update_template.html | factory-1 | Gmail template |
| factory-2/agents/agent_*.py | factory-2 | AI debug agents |
| factory-2/manager/guidance.py | factory-2 | Manager |
| factory-2/criticism/*.py | factory-2 | Criticism checks |
| factory-2/scripts/run_all.sh | factory-2 | Single job runner |

## Connected Apps Used
- Notion: Database setup (`notion_setup.py`)
- Linear: Issue sync (`linear_sync.py`)
- Google Docs: Template (`update_template.html` — could be expanded)
- Gmail: Update template (`update_template.html`)

## What the User Asked For
- Clean repo maintained (no root moved/deleted)
- Main rules added
- Factory 1 and Factory 2 branches created
- Factory 2 runs ONE GitHub job (`.github/workflows/factory-2-debug.yml`)
- AI agents act as debug agents (`factory-2/agents/`)
- Manager guides them (`factory-2/manager/guidance.py`)
- Criticism checks find dead code (`factory-2/criticism/dead_code_check.py`), incomplete code (`incomplete_check.py`), dead buttons (`dead_buttons_check.py`)
- Manager accepts/rejects feedback — does not discount (`MANAGER_GUIDE.md`)
- Full zip link provided (generated below)
- Criticism of environment added (reports created, manager response written)
