"""Build a fail-closed production handoff chain from registered factory tools."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.factory import factory

STAGES = [
    ("source", "Repository, policy and unfinished-source evidence"),
    ("static", "Language, formatting and complexity checks"),
    ("security", "Secrets, WebView, dependency and supply-chain checks"),
    ("backend", "API, schema and service checks"),
    ("android", "Compile, unit tests, lint and release checks"),
    ("device", "Emulator, accessibility and behavioral evidence"),
    ("release", "APK signature, manifest, smoke and delivery checks"),
]


def stage_for(tool: dict) -> str:
    profiles = set(tool.get("profiles", []))
    if profiles & {"apk-inspection", "release", "device"}:
        return "release" if "apk-inspection" in profiles else "device"
    if profiles & {"android", "android-config"}:
        return "android"
    if profiles & {"api", "containers", "python"}:
        return "backend"
    if profiles & {"security", "supply-chain"}:
        return "security"
    if profiles & {"javascript", "browser", "accessibility", "web-assets"}:
        return "device"
    if profiles & {"core", "fast", "docs", "evidence", "environment"}:
        return "source"
    return "static"


def build_chain() -> dict:
    tools = factory.load_tools()
    stages = []
    previous = None
    for stage_id, purpose in STAGES:
        selected = [tool for tool in tools if stage_for(tool) == stage_id]
        handoff = {
            "from": previous,
            "to": stage_id,
            "input": "prior stage receipt plus current source tree",
            "output": f"build/reports/factory/chain/{stage_id}.json",
            "pass_condition": "every selected job PASS or an explicitly reviewable REPORTED result",
            "block_condition": "FAIL, BLOCKED, missing evidence or unavailable prerequisite",
        }
        stages.append({"id": stage_id, "purpose": purpose,
                       "tools": [tool["id"] for tool in selected], "handoff": handoff})
        previous = stage_id
    return {"schema_version": 1, "registered_tools": len(tools),
            "minimum_requested_tools": 50, "tool_threshold_met": len(tools) >= 50,
            "release_approved": False, "stages": stages,
            "chain_rule": "A later stage cannot erase an earlier FAIL or BLOCKED result.",
            "source_rule": "A catalogue or URL is provenance, not execution evidence."}


def write_markdown(chain: dict, output: Path) -> None:
    lines = ["# Searchhh production verification chain", "",
             "This is an executable handoff structure, not a claim that every job currently passes.",
             "Each stage consumes the previous receipt and may only hand off PASS or explicitly reviewable evidence.",
             "FAIL, BLOCKED, missing prerequisites and missing evidence stop release approval.", "",
             f"Registered engines: **{chain['registered_tools']}**; minimum requested: **50**.", ""]
    for stage in chain["stages"]:
        lines += [f"## {stage['id']}: {stage['purpose']}", "",
                  "Tools: " + (", ".join(f"`{tool}`" for tool in stage["tools"]) or "none"), "",
                  f"Handoff: `{stage['handoff']['from'] or 'source tree'} → {stage['id']}`; output `{stage['handoff']['output']}`.", ""]
    lines += ["## Stop rule", "", chain["chain_rule"], "", chain["source_rule"], ""]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, default=Path("build/reports/factory/production-chain.json"))
    parser.add_argument("--markdown", type=Path, default=Path("docs/factory/PRODUCTION_CHAIN.md"))
    args = parser.parse_args()
    chain = build_chain()
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(chain, indent=2) + "\n")
    write_markdown(chain, args.markdown)
    print(json.dumps({"registered_tools": chain["registered_tools"], "stages": len(chain["stages"]), "release_approved": False}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
