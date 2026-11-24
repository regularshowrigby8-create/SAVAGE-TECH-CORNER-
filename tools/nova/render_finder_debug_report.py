#!/usr/bin/env python3
"""Render the CI APK finder debug session into a committed Markdown report."""
from __future__ import annotations

import datetime
import json
import shutil
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / "tools/nova/evidence/ci"
DOCS = ROOT / "nova-tv/docs"
REPORT = DOCS / "finder-debug-report.md"


def load(path: Path) -> Any:
    if not path.is_file():
        return {"__missing__": str(path.relative_to(ROOT))}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        return {"__invalid_json__": f"{type(error).__name__}: {error}"}


def status_of(payload: dict[str, Any]) -> str:
    if "__missing__" in payload:
        return "MISSING"
    if "__invalid_json__" in payload:
        return "INVALID"
    if payload.get("ok") is True:
        return "PASS"
    return "FAIL"


def copy_json(name: str) -> str | None:
    source = CI / name
    if not source.is_file():
        return None
    target = DOCS / f"finder-debug-{name}"
    shutil.copyfile(source, target)
    return target.name


def main() -> int:
    crawl = load(CI / "crawl-probe.json")
    pipeline = load(CI / "pipeline.json")
    ui = load(CI / "ui.json")
    copied = [name for name in (
        copy_json("crawl-probe.json"),
        copy_json("pipeline.json"),
        copy_json("ui.json"),
    ) if name]

    crawl_result = crawl.get("result", {}) if isinstance(crawl, dict) else {}
    pipeline_result = pipeline.get("result", {}) if isinstance(pipeline, dict) else {}
    ui_result = ui.get("result", {}) if isinstance(ui, dict) else {}
    logcat = CI / "ui-navigation.logcat.txt"
    logcat_excerpt = ""
    if logcat.is_file():
        lines = [
            line for line in logcat.read_text(errors="ignore").splitlines()
            if "NovaFinderUi" in line or "Find sites" in line
        ][:20]
        logcat_excerpt = "\n".join(f"    {line}" for line in lines)

    failure_lines = []
    failure_terms = (
        "NovaFinderDebug", "AndroidRuntime", "FATAL EXCEPTION", "ActivityManager",
        "finder probe", "BUDGET_STOPPED", "DEGRADED", "timeout", "threw", "Exception",
        "network-error", "renderer", "pipeline.done",
    )
    diagnostic_paths = sorted(CI.glob("*.logcat*.txt")) + sorted(CI.glob("*.heartbeat.txt")) + sorted(CI.glob("*.finder-debug-ls.txt"))
    for path in diagnostic_paths:
        for number, line in enumerate(path.read_text(errors="ignore").splitlines(), 1):
            if path.name.endswith(".heartbeat.txt") or path.name.endswith(".finder-debug-ls.txt") or any(term in line for term in failure_terms):
                failure_lines.append(f"{path.name}:{number}:{line}")
    failure_excerpt = "\n".join(f"    {line}" for line in failure_lines[-120:])

    overall = "PASS" if all(status_of(item) == "PASS" for item in (crawl, pipeline, ui)) and logcat_excerpt else "INCOMPLETE"
    lines = [
        "# APK finder debug session",
        "",
        f"Generated: {datetime.datetime.now(datetime.timezone.utc).isoformat()}",
        "",
        f"**Overall: {overall}**",
        "",
        "This report is written by a GitHub Actions job that built the current worktree, "
        "ran the public-index crawl probe, installed the mobile debug APK on an emulator, "
        "ran both production pipeline paths, and navigated the real mobile UI. A green "
        "local static gate is not APK evidence.",
        "",
        "## Results",
        "",
        "| Check | Status | Measured evidence |",
        "|---|---|---|",
        f"| Public-index crawl probe | {status_of(crawl)} | engines={crawl_result.get('engines_selected', 0)}/{crawl_result.get('engines_total', 0)}, responses_ok={crawl_result.get('responses_ok', 0)}, candidates={crawl_result.get('candidates', 0)}, verified={crawl_result.get('verified', 0)} |",
        f"| APK pipeline probe | {status_of(pipeline)} | newPipelineSites={pipeline_result.get('newPipelineSites', 0)}, durationMs={pipeline.get('durationMs', 0) if isinstance(pipeline, dict) else 0} |",
        f"| APK UI-button probe | {status_of(ui)} | uiButtonAdditions={ui_result.get('uiButtonAdditions', 0)}, newUiSites={ui_result.get('newUiSites', 0)}, durationMs={ui.get('durationMs', 0) if isinstance(ui, dict) else 0} |",
        f"| UI navigation logcat | {'PASS' if logcat_excerpt else 'MISSING'} | Find sites reached NovaViewModel and returned a result |" if logcat_excerpt else "| UI navigation logcat | MISSING | No logcat evidence was written |",
        "",
        "## Crawl probe",
        "",
        f"- profile: `{crawl_result.get('profile', 'unknown')}`",
        f"- round: `{crawl_result.get('round', 'unknown')}`",
        f"- engines selected: `{crawl_result.get('engines_selected', 0)}` of `{crawl_result.get('engines_total', 0)}`",
        f"- engine responses OK: `{crawl_result.get('responses_ok', 0)}`; failed: `{crawl_result.get('responses_failed', 0)}`",
        f"- distinct candidates after sorter: `{crawl_result.get('candidates', 0)}`",
        f"- candidate look attempts: `{crawl_result.get('looked', 0)}`; verified sites: `{crawl_result.get('verified', 0)}`",
        f"- look stop reason: `{crawl_result.get('look_stop_reason', 'unknown')}`",
        f"- sorter rejections: `{json.dumps(crawl_result.get('rejection_counts', {}), sort_keys=True)}`",
        "",
        "Verified site samples:",
        "",
    ]
    verified_sites = crawl_result.get("verified_sites", [])
    if isinstance(verified_sites, list) and verified_sites:
        for site in verified_sites[:10]:
            lines.append(f"- `{site.get('url')}` — {site.get('family')}, catalogue={site.get('catalogueItemCount')}, sources={site.get('sourceCount')}")
    else:
        lines.append("- No verified site sample was present in the crawl probe evidence.")
    lines += [
        "",
        "## APK pipeline probe",
        "",
    ]
    for key in ("websiteReport", "addonReport"):
        report = pipeline_result.get(key, {})
        lines += [
            f"### {key}",
            "",
            f"- marker: `{report.get('marker', 'missing')}`",
            f"- status: `{report.get('status', 'missing')}`",
            f"- engines/stages: `{report.get('engineCount', 0)}/{report.get('stageCount', 0)}`",
            f"- verifiedCount: `{report.get('verifiedCount', 0)}`",
            f"- stageCounts: `{json.dumps(report.get('stageCounts', {}), sort_keys=True)}`",
            f"- errors: `{json.dumps(report.get('errors', []))}`",
            "",
        ]
    lines += [
        "## APK UI-button probe",
        "",
        f"- websitesAdded: `{ui_result.get('websitesAdded', 0)}`",
        f"- addonsAdded: `{ui_result.get('addonsAdded', 0)}`",
        f"- websiteRejections: `{ui_result.get('websiteRejections', '')}`",
        f"- addonRejections: `{ui_result.get('addonRejections', '')}`",
        f"- websitePoolSample entries: `{len(ui_result.get('websitePoolSample', [])) if isinstance(ui_result.get('websitePoolSample'), list) else 0}`",
        f"- serverPoolSample entries: `{len(ui_result.get('serverPoolSample', [])) if isinstance(ui_result.get('serverPoolSample'), list) else 0}`",
        "",
        "## UI navigation logcat excerpt",
        "",
        "```text",
        logcat_excerpt or "(missing)",
        "```",
        "",
        "## Failure diagnostics",
        "",
        "```text",
        failure_excerpt or "(none captured)",
        "```",
        "",
        "## Evidence files committed",
        "",
    ]
    if copied:
        for name in copied:
            lines.append(f"- `nova-tv/docs/{name}`")
    else:
        lines.append("- No JSON evidence files were available to copy.")
    lines += [
        "",
        "The full CI artifact contains the trace files, full logcat, UIAutomator dumps, "
        "persisted pool JSON and final screenshot. A `MISSING` or `INCOMPLETE` status "
        "means the workflow did not prove that path; it is not a green result.",
        "",
    ]
    DOCS.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {REPORT.relative_to(ROOT)} overall={overall}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
