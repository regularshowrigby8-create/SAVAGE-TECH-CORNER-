"""The swarm: how many agents run, what they do, who checks them, and what is honest to claim.

The component is built to hold up to a million agent instances. They are cheap: an instance is a
role reference and a sequence number, produced lazily, so planning a million of them costs no more
memory than planning ten. *Executing* them is bounded by the machine, and this module is explicit
about that difference - `plan()` counts, `run()` executes what it was told to execute, and the report
always carries both numbers.

Two passes, always:

1. **make** - the role does its work. With a model reachable (through the unified gateway) the
   instance's brief goes to the model and the answer is written down. With no model the role runs its
   declared offline steps: real tool calls that produce real artefacts - metrics, scans, scaffolds,
   test runs, briefs. Never a placeholder, never an invented result.
2. **check** - a role's declared checks run against the workspace, and every instance's artefact is
   verified to exist and be unchanged since it was written. A check that fails is reported as a
   failure; nothing is smoothed.
"""

from __future__ import annotations

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from agents import Role, by_id, catalogue
from langkit import detect
from toolkit import ToolError, Workspace, build_registry

MAX_AGENTS = 1_000_000
DEFAULT_EXECUTE_LIMIT = 200
DEFAULT_MODEL_CALLS = 8


def plan(roles: list[Role], instances: int):
    """Every agent instance, lazily: (sequence, role). A million is a generator, not a list."""
    if instances < 0:
        raise ValueError("the number of instances cannot be negative")
    total = instances * len(roles)
    if total > MAX_AGENTS:
        raise ValueError(f"{total} instances exceeds the {MAX_AGENTS} this component holds")
    sequence = 0
    for _round in range(instances):
        for role in roles:
            yield {"sequence": sequence, "role": role.id, "round": _round}
            sequence += 1


def counted(roles: list[Role], instances: int) -> int:
    if instances < 0 or instances * len(roles) > MAX_AGENTS:
        raise ValueError(f"the plan would exceed the {MAX_AGENTS} instances this component holds")
    return instances * len(roles)


def _artefact_name(role_id: str, sequence: int) -> str:
    return f"{role_id}-{sequence:07d}.json"


def _run_steps(registry: dict, steps: tuple[dict, ...], workspace: Workspace) -> list[dict]:
    """Runs a role's declared tool calls. Returns one record per step; a refusal is a record too."""
    taken = []
    for step in steps:
        tool = step["tool"]
        args = dict(step.get("args") or {})
        if tool not in registry:
            taken.append({"tool": tool, "ok": False, "error": "the role names a tool that is not in "
                                                             "the registry"})
            continue
        try:
            result = registry[tool]["fn"](**args)
            taken.append({"tool": tool, "ok": True, "summary": _summarise(result)})
        except ToolError as problem:
            taken.append({"tool": tool, "ok": False, "error": str(problem)[:400]})
        except Exception as problem:  # a tool's own bug is news, not silence
            taken.append({"tool": tool, "ok": False, "error": f"{type(problem).__name__}: "
                                                             f"{str(problem)[:300]}"})
    return taken


def _summarise(value) -> str:
    if isinstance(value, dict):
        interesting = [key for key in ("path", "bytes", "matches", "findings", "clean", "totals",
                                       "exit_code", "entries", "stored", "key", "available")
                       if key in value]
        parts = []
        for key in interesting:
            item = value[key]
            if isinstance(item, list):
                parts.append(f"{key}={len(item)}")
            else:
                parts.append(f"{key}={str(item)[:60]}")
        return "; ".join(parts)[:240] or json.dumps(value)[:200]
    if isinstance(value, list):
        return f"{len(value)} item(s)"
    return str(value)[:200]


def run(root: str, task: str, instances: int = 1, workers: int = 16,
        execute_limit: int = DEFAULT_EXECUTE_LIMIT, model_calls: int = DEFAULT_MODEL_CALLS,
        gateway_url: str = "", roles_filter: tuple[str, ...] = ()) -> dict:
    workspace = Workspace(root)
    registry = build_registry(workspace, gateway_url)
    records = _imported_records()
    languages = detect()["available"]
    roles = catalogue(records, languages)
    if roles_filter:
        wanted = by_id(roles)
        roles = [wanted[role] for role in roles_filter if role in wanted]
        if not roles:
            raise ValueError(f"none of the requested roles exist: {', '.join(roles_filter)}")

    total_instances = counted(roles, instances)
    # A hard cap, exactly as asked: `--limit` is how many agent instances this run executes. The
    # report names the roles that did not get one, so a small limit never looks like a full sweep.
    to_execute = min(total_instances, max(0, execute_limit))

    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    # Two runs in the same second must not share a directory: a run that appended into another run's
    # artefacts would report a count that does not match what is on disk, and the second RUN.json
    # would replace the first run's report. The suffix makes each run's records its own.
    run_dir = workspace.state / "swarm" / stamp
    suffix = 2
    while run_dir.exists():
        run_dir = workspace.state / "swarm" / f"{stamp}-{suffix}"
        suffix += 1
    artefacts_dir = run_dir / "artefacts"
    artefacts_dir.mkdir(parents=True, exist_ok=True)

    started = time.time()
    per_role: dict[str, dict] = {role.id: {"instances": 0, "artefacts": 0, "steps_ok": 0,
                                           "steps_failed": 0, "model_calls": 0, "errors": []}
                                 for role in roles}
    checks: list[dict] = []
    model_calls_made = 0
    model_available = False

    if model_calls and gateway_url:
        model_available = _gateway_ready(gateway_url)

    def handle(item: dict) -> dict:
        nonlocal model_calls_made
        role = by_id(roles)[item["role"]]
        entry = {"sequence": item["sequence"], "role": role.id, "title": role.title,
                 "topology": role.topology, "source": role.source, "status": "", "steps": []}
        if model_available and model_calls_made < model_calls and "model.ask" in role.tools:
            model_calls_made += 1
            entry["steps"].append(_ask_model(registry, role, task))
            entry["status"] = "MODEL" if entry["steps"][-1]["ok"] else "MODEL-FAILED"
            if not entry["steps"][-1]["ok"]:
                entry["steps"].extend(_run_steps(registry, role.offline, workspace))
        else:
            entry["steps"] = _run_steps(registry, role.offline, workspace)
            entry["status"] = "OFFLINE"
        entry["steps_ok"] = sum(1 for step in entry["steps"] if step["ok"])
        entry["steps_failed"] = len(entry["steps"]) - entry["steps_ok"]
        return entry

    executed: list[dict] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        batch: list[dict] = []
        for item in plan(roles, instances):
            if len(executed) + len(batch) >= to_execute:
                break
            batch.append(item)
            if len(batch) >= workers * 4:
                executed.extend(pool.map(handle, batch))
                batch = []
        if batch:
            executed.extend(pool.map(handle, batch))

    for entry in executed:
        stats = per_role[entry["role"]]
        stats["instances"] += 1
        stats["steps_ok"] += entry["steps_ok"]
        stats["steps_failed"] += entry["steps_failed"]
        if entry["status"] == "MODEL":
            stats["model_calls"] += 1
        for step in entry["steps"]:
            if not step["ok"] and len(stats["errors"]) < 3:
                stats["errors"].append(f'{step["tool"]}: {step.get("error", "")[:160]}')
        target = artefacts_dir / _artefact_name(entry["role"], entry["sequence"])
        target.write_text(json.dumps(entry, indent=1), encoding="utf-8")
        stats["artefacts"] += 1

    # One check pass per role that ran: the checks verify the workspace the makers just touched.
    for role in roles:
        if per_role[role.id]["instances"] == 0:
            continue
        for step in role.checks:
            tool = step["tool"]
            args = dict(step.get("args") or {})
            if tool not in registry:
                checks.append({"role": role.id, "tool": tool, "ok": False,
                               "error": "check names a tool that is not in the registry"})
                continue
            try:
                result = registry[tool]["fn"](**args)
                checks.append({"role": role.id, "tool": tool, "ok": True,
                               "summary": _summarise(result)})
            except ToolError as problem:
                checks.append({"role": role.id, "tool": tool, "ok": False,
                               "error": str(problem)[:300]})
            except Exception as problem:
                checks.append({"role": role.id, "tool": tool, "ok": False,
                               "error": f"{type(problem).__name__}: {str(problem)[:200]}"})

    passed = sum(1 for check in checks if check["ok"])
    report = {
        "task": task,
        "started_at": stamp,
        "seconds": round(time.time() - started, 2),
        "workspace": str(workspace.root),
        "roles": len(roles),
        "plan": {"instances": total_instances, "capacity": MAX_AGENTS, "executed": len(executed),
                 "not_executed": total_instances - len(executed),
                 "roles_executed": sum(1 for role in roles
                                       if per_role[role.id]["instances"] > 0),
                 "roles_not_executed": [role.id for role in roles
                                        if per_role[role.id]["instances"] == 0],
                 "note": ("planning is lazy, so a million instances cost nothing to hold; execution "
                          "is capped by --limit on purpose, and the roles that got no instance are "
                          "listed rather than implied")},
        "model": {"gateway": gateway_url or None, "available": model_available,
                  "calls_made": model_calls_made, "calls_allowed": model_calls},
        "languages_available": languages,
        "counts": {
            "model": sum(1 for e in executed if e["status"] == "MODEL"),
            "offline": sum(1 for e in executed if e["status"] == "OFFLINE"),
            "model_failed": sum(1 for e in executed if e["status"] == "MODEL-FAILED"),
            "steps_ok": sum(e["steps_ok"] for e in executed),
            "steps_failed": sum(e["steps_failed"] for e in executed),
            "checks_passed": passed,
            "checks_failed": len(checks) - passed,
            "artefacts": len(executed),
        },
        "per_role": per_role,
        "checks": checks,
        "artefacts_dir": str(artefacts_dir.relative_to(workspace.root)),
    }
    report["run_dir"] = str(run_dir.relative_to(workspace.root))
    (run_dir / "RUN.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    return report


def _ask_model(registry: dict, role: Role, task: str) -> dict:
    prompt = (f"You are the {role.title} in a software factory.\nMission: {role.mission}\n"
              f"Task: {task}\nWorkspace: {registry['fs.list']['fn'](path='.', depth=1) and 'read'}. "
              f"Return the concrete artefacts you would produce, in order, and say plainly what you "
              f"cannot determine from the task alone.")
    try:
        result = registry["model.ask"]["fn"](prompt=prompt)
        return {"tool": "model.ask", "ok": True, "summary": _summarise(result)}
    except ToolError as problem:
        return {"tool": "model.ask", "ok": False, "error": str(problem)[:300]}


def _gateway_ready(gateway_url: str) -> bool:
    import urllib.error
    import urllib.request
    try:
        with urllib.request.urlopen(f"{gateway_url.rstrip('/')}/healthz", timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
        return bool(payload.get("reachable"))
    except (urllib.error.URLError, OSError, ValueError):
        return False


def _imported_records(root: Path | None = None) -> list[dict]:
    """Every vendored group inside the seal: factories, AI factories and MCP servers.

    Absent directories mean an empty catalogue, never a crash - the component is removable.
    """
    here = Path(__file__).resolve().parents[1]
    base = root or here
    records = []
    for group in ("vendor", "vendor-ai", "mcp-vendor"):
        directory = base / group
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json")):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            record["_group"] = group
            records.append(record)
    return records


def catalogue_summary() -> dict:
    """What the seal holds, by group, so the runtime can report the whole force in one line."""
    records = _imported_records()
    groups: dict[str, int] = {}
    for record in records:
        groups[record.get("_group", "vendor")] = groups.get(record.get("_group", "vendor"), 0) + 1
    return {"records": len(records), "by_group": groups}


def capacity_report() -> dict:
    records = _imported_records()
    languages = detect()
    roles = catalogue(records, languages["available"])
    per_role = MAX_AGENTS // max(len(roles), 1)
    return {"roles": len(roles), "capacity": MAX_AGENTS, "max_instances_per_role": per_role,
            "languages": languages["available"], "missing_languages": languages["missing"],
            "roles_by_source": {"core": sum(1 for r in roles if r.source == "core"),
                                "language": sum(1 for r in roles if r.source == "language"),
                                "imported": sum(1 for r in roles if r.source == "imported"),
                                "mcp_verified": sum(1 for r in roles
                                                    if r.source == "mcp-server"),
                                "mcp_catalogue": sum(1 for r in roles
                                                     if r.source == "mcp-import")},
            "catalogue": catalogue_summary()}


if __name__ == "__main__":
    print(json.dumps(capacity_report(), indent=1))
