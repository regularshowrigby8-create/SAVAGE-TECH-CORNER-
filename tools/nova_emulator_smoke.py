#!/usr/bin/env python3
"""Install both APKs on an already-running Android emulator and observe startup.

This is a bounded smoke test, not proof that playback or every UI control works.
It requires real log markers for both bundled-pool imports and the server-fill
coroutine, an alive app process after the observation period, and no crash/ANR
for the tested package. Import counts are newly added entries, not pool totals.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path


CASES = (
    ("tv", "dev.nova.tv", "dev.nova.tv.ui.NovaActivity"),
    ("mobile", "dev.nova.mobile", "dev.nova.tv.mobile.MobileActivity"),
)
MARKER = "NovaHarvester: server pool fill started"
IMPORT_MARKERS = {
    "server": re.compile(r"NovaServerPool:\s+imported\s+(\d+)\s+bundled servers\b"),
    "site": re.compile(r"NovaSitePool:\s+imported\s+(\d+)\s+bundled sites\b"),
}


def imported_counts(log: str) -> dict[str, int]:
    """Parse successful import log lines; missing lines are not zero imports."""
    return {name: int(match.group(1)) for name, pattern in IMPORT_MARKERS.items()
            if (match := pattern.search(log)) is not None}


def assess_observation(package: str, launch: str, pid: str, log: str, crash_log: str) -> list[str]:
    """Return failures, never a pass when a required observation is absent."""
    errors = []
    if not re.search(r"^Status:\s*ok\s*$", launch, re.MULTILINE | re.IGNORECASE):
        errors.append("activity did not report a successful start")
    if not pid.strip() or not all(part.isdigit() for part in pid.split()):
        errors.append("app process was not alive at the end of the observation")
    if MARKER not in log:
        errors.append("the server-fill start marker was not observed")
    if "NovaHarvester: server pool fill failed" in log:
        errors.append("the server-fill coroutine reported an error")
    imports = imported_counts(log)
    for name in IMPORT_MARKERS:
        if name not in imports:
            errors.append(f"the bundled {name} pool did not report a successful import")
    if re.search(r"Process:\s*" + re.escape(package) + r"(?:\s*[,\n]|\s*$)", crash_log):
        errors.append("Android crash buffer contains a fatal exception for this package")
    if f">>> {package} <<<" in crash_log:
        errors.append("Android crash buffer contains a native crash for this package")
    if re.search(r"ANR in\s+" + re.escape(package) + r"(?:\s|\(|$)", log):
        errors.append("an application-not-responding event was observed")
    return errors


def adb(*args: str, required: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=180)
    if required and result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed ({result.returncode}): {(result.stderr or result.stdout).strip()}")
    return result


def run_case(flavor: str, package: str, activity: str, apk: Path, seconds: int, out: Path) -> dict:
    record = {"flavor": flavor, "package": package, "apk": apk.name,
              "requested_seconds": seconds, "observed_seconds": 0,
              "sha256": "", "status": "failed", "errors": []}
    try:
        if not apk.is_file():
            raise RuntimeError(f"APK does not exist: {apk}")
        record["sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        install = adb("install", "-r", str(apk)).stdout
        if "Success" not in install:
            raise RuntimeError(f"install did not report success: {install.strip()}")
        adb("shell", "am", "force-stop", package)
        adb("logcat", "-c")
        adb("logcat", "-b", "crash", "-c")
        launch = adb("shell", "am", "start", "-W", "-n", f"{package}/{activity}").stdout
        (out / f"{flavor}-launch.txt").write_text(launch)
        # Wait for the actual fill and import markers. Preserve their lines
        # across polls in case logcat wraps before the other markers arrive.
        # The full observation interval starts only *after* the fill marker.
        evidence: dict[str, None] = {}
        latest_log = ""
        for attempt in range(30):
            latest_log = adb("logcat", "-d", "-v", "threadtime").stdout
            for line in latest_log.splitlines():
                if any(tag in line for tag in ("NovaHarvester:", "NovaServerPool:", "NovaSitePool:")):
                    evidence[line] = None
            captured = "\n".join(evidence)
            if MARKER in captured and len(imported_counts(captured)) == len(IMPORT_MARKERS):
                break
            if attempt < 29:
                time.sleep(3)
        start_log = "\n".join(evidence) + "\n" + latest_log
        started = MARKER in start_log
        (out / f"{flavor}-start-logcat.txt").write_text(start_log)
        if started:
            time.sleep(seconds)
            record["observed_seconds"] = seconds
        pid = adb("shell", "pidof", package, required=False).stdout.strip()
        log = adb("logcat", "-d", "-v", "threadtime").stdout
        crashes = adb("logcat", "-b", "crash", "-d", "-v", "threadtime").stdout
        (out / f"{flavor}-logcat.txt").write_text(log)
        (out / f"{flavor}-crashes.txt").write_text(crashes)
        combined_log = start_log + "\n" + log
        errors = assess_observation(package, launch, pid, combined_log, crashes)
        if not started:
            errors.append("finder marker did not arrive in time to observe it for the required interval")
        record.update({"pid": pid, "finder_marker": started,
                       "bundled_imports": imported_counts(combined_log), "errors": errors,
                       "status": "passed" if not errors else "failed"})
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        record["errors"] = [str(error)]
    finally:
        try:
            adb("shell", "am", "force-stop", package, required=False)
        except (OSError, subprocess.TimeoutExpired):
            pass
    return record


def summary(records: list[dict]) -> str:
    lines = ["# Nova emulator startup smoke", "",
             "Both flavors were tested separately on an Android TV emulator. This is a bounded",
             "startup/server-fill observation, not a playback or complete UI test.", "",
             "| flavor | result | observed seconds | fill marker | newly imported server / site entries | APK SHA-256 |",
             "|---|---|---:|---|---:|---|"]
    for item in records:
        imports = item.get("bundled_imports", {})
        counts = f"{imports.get('server', 'missing')} / {imports.get('site', 'missing')}"
        lines.append(f"| {item['flavor']} | {item['status']} | {item['observed_seconds']} | "
                     f"{item.get('finder_marker', False)} | {counts} | `{item['sha256']}` |")
    for item in records:
        if item.get("errors"):
            lines.extend(["", f"**{item['flavor']} errors:**", ""] + [f"- {e}" for e in item["errors"]])
    lines.extend(["", "Import counts are new entries, not pool totals; existing or duplicate entries",
                  "can make them smaller. Full device logs are in the workflow artifact. These",
                  "results do not establish the cause of any separate device-specific crash.", ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tv", required=True, type=Path)
    parser.add_argument("--mobile", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--seconds", type=int, default=90)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 600:
        parser.error("--seconds must be between 1 and 600")
    args.out.mkdir(parents=True, exist_ok=True)
    records = [run_case(*case, apk, args.seconds, args.out)
               for case, apk in zip(CASES, (args.tv, args.mobile))]
    (args.out / "status.json").write_text(json.dumps(records, indent=2) + "\n")
    (args.out / "summary.md").write_text(summary(records))
    print(summary(records))
    return 0 if len(records) == 2 and all(item["status"] == "passed" for item in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
