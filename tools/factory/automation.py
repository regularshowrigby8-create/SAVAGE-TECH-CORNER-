"""Route checks, summarize diagnostics and show ownership without repairing source."""

import argparse
import atexit
import hashlib
import json
import signal
import subprocess
from collections import Counter
from pathlib import Path

from tools.factory import factory
from tools.factory.factory_map import write_overview
from tools.factory.safety import safe_relative

ROOT = factory.ROOT
OUTPUT = ROOT / "build/reports/factory"


def plan(paths: list[str]) -> dict:
    """Select additional diagnostics; canonical release gates are never skipped."""
    routes = {}
    for path in sorted(set(paths)):
        safe_relative(ROOT, path)
        if path.startswith(("tools/factory/", ".github/")):
            profiles = {"all"}
        elif path.startswith("backend/"):
            profiles = {
                "python",
                "api",
                "tests",
                "security",
                "containers",
                "supply-chain",
            }
        elif path.startswith(
            ("app/", "ad-filter/", "adblock-client/", "gradle/")
        ) or path.endswith((".kts", ".gradle")):
            profiles = {
                "android",
                "android-config",
                "tests",
                "browser",
                "accessibility",
                "web-assets",
                "security",
            }
        elif path.startswith("js-tests/"):
            profiles = {"javascript", "tests", "browser", "supply-chain"}
        elif path.startswith("tools/quality/"):
            profiles = {"python", "tests", "security"}
        elif path.endswith(".md"):
            profiles = {"docs"}
        else:
            profiles = {"all"}
        routes[path] = sorted(profiles)
    profiles = {profile for group in routes.values() for profile in group}
    tools = factory.load_tools()
    chosen = [
        tool["id"]
        for tool in tools
        if "all" in profiles or profiles.intersection(tool["profiles"])
    ]
    return {
        "schema_version": 1,
        "paths": routes,
        "tools": chosen,
        "state": "PLANNED" if chosen else "NO_CHANGES",
        "canonical_gates_still_required": True,
        "release_approved": False,
        "scope_note": (
            "Commands retain their declared scope; "
            "selection is not proof of complete changed-file coverage."
        ),
    }


def changed_paths(base: str | None) -> list[str]:
    """Resolve refs before diffing; include dirty/untracked source for local work."""
    ref = base or "HEAD"
    commit = subprocess.check_output(
        ["git", "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"],
        cwd=ROOT,
        text=True,
    ).strip()
    data = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z", commit, "--"], cwd=ROOT
    )
    data += subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT
    )
    return sorted(set(data.decode().split("\0")) - {""})


def extract(tool: str, data) -> list[tuple[str, str, int]]:
    """Return only rule/location metadata, not messages, snippets or secret values."""
    if tool == "ruff":
        return [(r["code"], r["filename"], r["location"]["row"]) for r in data]
    if tool == "bandit":
        return [
            (r["test_id"], r["filename"], r["line_number"]) for r in data["results"]
        ]
    if tool in {"eslint", "html-validate", "stylelint"}:
        return [
            (
                str(r.get("ruleId") or r.get("rule") or "unclassified"),
                f.get("filePath", f.get("source", "unknown")),
                r.get("line", 0),
            )
            for f in data
            for r in f.get("messages", f.get("warnings", []))
        ]
    if tool == "npm-audit":
        return [
            ("npm-audit:" + name, "tools/factory/npm/package-lock.json", 0)
            for name in data["vulnerabilities"]
        ]
    if tool == "gitleaks":
        return [(r["RuleID"], r["File"], r["StartLine"]) for r in data]
    if tool == "axe-core":
        return [(r["id"], r["file"], 0) for r in data["violations"]]
    raise ValueError("Unsupported diagnostic format: " + tool)


def read_diagnostics(result: dict, root: Path) -> tuple[str, list]:
    """Read only bounded, authenticated output from its documented stream."""
    tool = result["id"]
    log = safe_relative(root, result["log"])
    if not log.is_relative_to((root / "build/reports/factory").resolve()):
        raise ValueError("Report must be inside factory output")
    digest = result["log_sha256"]
    if tool == "stylelint":
        log = safe_relative(log.parent, "stderr.log")
        digest = result["stderr_sha256"]
    if tool in {"gitleaks", "axe-core"}:
        artifact = next(
            r
            for r in result["reports"]
            if r["path"] == ("gitleaks.json" if tool == "gitleaks" else "axe.json")
        )
        log = safe_relative(log.parent, artifact["path"])
        digest = artifact["sha256"]
    if log.stat().st_size > factory.MAX_LOG_BYTES:
        raise ValueError("Report exceeds output bound")
    payload = log.read_bytes()
    if (
        len(payload) > factory.MAX_LOG_BYTES
        or hashlib.sha256(payload).hexdigest() != digest
    ):
        raise ValueError("Report bound or checksum mismatch")
    return digest, extract(tool, json.loads(payload))


def group_observations(packets: dict, tool: str, digest: str, entries: list) -> None:
    """Preserve each report entry as an alias in provisional file/rule groups."""
    for index, (rule, file, line) in enumerate(entries):
        key = hashlib.sha256(json.dumps([tool, rule, file]).encode()).hexdigest()[:20]
        packet = packets.setdefault(
            key,
            {
                "id": key,
                "tool": tool,
                "rule": rule,
                "file": file,
                "observations": [],
                "owner": "review required",
                "closed": False,
            },
        )
        packet["observations"].append(
            {"report_sha256": digest, "entry": index, "line": line}
        )


def summarize(summary: dict, root: Path = ROOT) -> dict:
    """Retain every job outcome and make unparsed/invalid evidence explicit."""
    packets: dict[str, dict] = {}
    opaque = []
    errors = []
    for result in summary["results"]:
        tool = result["id"]
        if tool not in {
            "ruff",
            "bandit",
            "eslint",
            "html-validate",
            "stylelint",
            "gitleaks",
            "axe-core",
            "npm-audit",
        }:
            opaque.append(
                {
                    "id": tool,
                    "status": result["status"],
                    "reason": "No location parser; review original report",
                }
            )
            continue
        try:
            digest, entries = read_diagnostics(result, root)
            group_observations(packets, tool, digest, entries)
        except (ValueError, KeyError, TypeError, OSError, StopIteration) as error:
            errors.append(
                {
                    "id": tool,
                    "error": type(error).__name__,
                    "reason": (
                        "Evidence missing, malformed, unsafe or mismatched; "
                        "inspect original job"
                    ),
                }
            )
    return {
        "source_commit": summary.get("source_commit"),
        "job_counts": dict(Counter(r["status"] for r in summary["results"])),
        "jobs": [{"id": r["id"], "status": r["status"]} for r in summary["results"]],
        "packets": list(packets.values()),
        "opaque_jobs": opaque,
        "parse_errors": errors,
        "raw_aliases_preserved": sum(len(p["observations"]) for p in packets.values()),
        "semantic_deduplication": False,
        "release_approved": False,
    }


def savings(samples: list[dict]) -> dict:
    """Compute active human-effort reduction only from sufficient paired observations."""
    if len(samples) < 10:
        return {
            "state": "NOT_MEASURED",
            "reason": "Need at least 10 comparable paired work items",
            "target": [0.70, 0.80],
        }
    ids = set()
    for sample in samples:
        if sample["id"] in ids or sample["required_checks_complete"] is not True:
            raise ValueError("Duplicate work item or missing required verification")
        ids.add(sample["id"])
        for key in ["before_minutes", "after_minutes"]:
            value = sample[key]
            if (
                not isinstance(value, (int, float))
                or isinstance(value, bool)
                or not 0 <= value < 100000
            ):
                raise ValueError("Invalid active-effort measurement")
        if sample["before_minutes"] == 0:
            raise ValueError("Baseline must be nonzero")
    before = sum(s["before_minutes"] for s in samples)
    after = sum(s["after_minutes"] for s in samples)
    reduction = 1 - after / before
    return {
        "state": "MEASURED",
        "samples": len(samples),
        "active_minutes_before": before,
        "active_minutes_after": after,
        "reduction": reduction,
        "target_70_percent_met": reduction >= 0.70,
        "scope": (
            "Active routine checking/triage effort only; "
            "self-reported observations need review"
        ),
    }


def main() -> int:
    """Expose planning, actual execution, evidence routing and honest measurement."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action", choices=["plan", "check", "report", "overview", "measure"]
    )
    parser.add_argument("--paths", nargs="+")
    parser.add_argument("--base")
    parser.add_argument("--summary")
    parser.add_argument("--samples")
    parser.add_argument("--publish-doc", action="store_true")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if args.action == "overview":
        target = (
            ROOT / "docs/factory/FACTORY.html"
            if args.publish_doc
            else OUTPUT / "overview.html"
        )
        write_overview(target)
        print(target)
        return 0
    if args.action in {"plan", "check"}:
        result = plan(
            args.paths if args.paths is not None else changed_paths(args.base)
        )
        (OUTPUT / "change-plan.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
        if args.action == "check":
            if not result["tools"]:
                return 2  # No check ran; do not emit a quality pass.
            selected = [t for t in factory.load_tools() if t["id"] in result["tools"]]
            run_args = argparse.Namespace(
                workers=1 if any("android" in t["profiles"] for t in selected) else 3,
                apk=None,
            )
            return factory.run_batch(selected, run_args)
        return 0
    if args.action == "measure":
        result = savings(
            json.loads(safe_relative(ROOT, args.samples).read_text())
            if args.samples
            else []
        )
        print(json.dumps(result, indent=2))
        return 0 if result["state"] == "MEASURED" else 2
    if not args.summary:
        parser.error("--summary is required; never silently use stale evidence")
    summary = json.loads(safe_relative(ROOT, args.summary).read_text())
    result = summarize(summary)
    out = OUTPUT / "review-queue.json"
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(
        f"{len(result['packets'])} provisional packets; "
        f"{len(result['opaque_jobs'])} opaque jobs; "
        f"{len(result['parse_errors'])} parse errors; {out}"
    )
    return (
        1
        if result["parse_errors"]
        or any(r["status"] in {"FAIL", "BLOCKED"} for r in result["jobs"])
        else 0
    )


if __name__ == "__main__":
    atexit.register(factory.stop_children)
    signal.signal(signal.SIGTERM, factory.shutdown)
    signal.signal(signal.SIGINT, factory.shutdown)
    raise SystemExit(main())
