#!/usr/bin/env python3
"""Verify every Nova APK artifact with explicit, non-silent evidence.

The old CI accepted any APK it happened to find and used warn-only uploads.
This checker requires exactly one APK in each of the four declared cells:
TV debug, TV release, mobile debug and mobile release. For each artifact it
checks the archive, Android package id, signature, alignment and the source
markers inside one or more dex files. A missing source marker is a failure, so
the APK assertion cannot rot into a tautology.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys
import traceback
import zipfile
from pathlib import Path
from typing import Any

APK_SPECS = [
    {
        "id": "tv_debug",
        "pattern": "nova-tv/android/app/build/outputs/apk/tv/debug/*.apk",
        "package": "dev.nova.tv",
    },
    {
        "id": "tv_release",
        "pattern": "nova-tv/android/app/build/outputs/apk/tv/release/*.apk",
        "package": "dev.nova.tv",
    },
    {
        "id": "mobile_debug",
        "pattern": "nova-tv/android/app/build/outputs/apk/mobile/debug/*.apk",
        "package": "dev.nova.mobile",
    },
    {
        "id": "mobile_release",
        "pattern": "nova-tv/android/app/build/outputs/apk/mobile/release/*.apk",
        "package": "dev.nova.mobile",
    },
]

SOURCE_MARKERS = {
    "documentElement.outerHTML": [
        "nova-tv/android/app/src/main/java/dev/nova/tv/finder/translated/FinderBrowser.kt"
    ],
    "nova-finder-pipeline-v3": [
        "nova-tv/android/app/src/main/java/dev/nova/tv/finder/pipeline/PipelineModels.kt"
    ],
}

DEX_MARKERS = [b"documentElement.outerHTML", b"nova-finder-pipeline-v3"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_tool_paths(root: Path) -> dict[str, str]:
    tools = {}
    for name in ("aapt2", "apksigner", "zipalign"):
        path = root / name
        if not path.is_file() or not os.access(path, os.X_OK):
            raise RuntimeError(f"required Android build tool is missing or not executable: {path}")
        tools[name] = str(path)
    return tools


def run_tool(argv: list[str], label: str) -> dict[str, Any]:
    completed = subprocess.run(argv, capture_output=True, text=True, check=False)
    return {
        "label": label,
        "argv": argv,
        "returncode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def check_source_markers(repo: Path, errors: list[str]) -> list[dict[str, Any]]:
    records = []
    for marker, paths in SOURCE_MARKERS.items():
        found = []
        for rel in paths:
            path = repo / rel
            if not path.is_file():
                errors.append(f"source marker file missing: {rel}")
                continue
            if marker in path.read_text(encoding="utf-8"):
                found.append(rel)
        if not found:
            errors.append(f"source marker {marker!r} is absent from declared source files")
        records.append({"marker": marker, "paths_checked": paths, "found_in": found, "ok": bool(found)})
    return records


def check_apk(
    repo: Path,
    spec: dict[str, str],
    tools: dict[str, str],
    errors: list[str],
) -> dict[str, Any]:
    matches = sorted(repo.glob(spec["pattern"]))
    record: dict[str, Any] = {
        "id": spec["id"],
        "pattern": spec["pattern"],
        "matched": [str(path.relative_to(repo)) for path in matches],
        "ok": False,
    }
    if len(matches) != 1:
        errors.append(f"{spec['id']}: expected exactly one APK, found {len(matches)}")
        return record
    apk = matches[0]
    record["path"] = str(apk.relative_to(repo))
    record["bytes"] = apk.stat().st_size
    record["sha256"] = sha256_file(apk)
    if record["bytes"] <= 0:
        errors.append(f"{spec['id']}: APK is empty")
        return record

    try:
        with zipfile.ZipFile(apk) as archive:
            bad = archive.testzip()
            if bad is not None:
                errors.append(f"{spec['id']}: corrupt zip member {bad}")
            names = archive.namelist()
            dex_names = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
            if "AndroidManifest.xml" not in names:
                errors.append(f"{spec['id']}: AndroidManifest.xml is missing")
            if not dex_names:
                errors.append(f"{spec['id']}: no classes*.dex entries")
            marker_found: dict[str, list[str]] = {marker.decode(): [] for marker in DEX_MARKERS}
            for dex_name in dex_names:
                payload = archive.read(dex_name)
                for marker in DEX_MARKERS:
                    if marker in payload:
                        marker_found[marker.decode()].append(dex_name)
            record["dex_entries"] = dex_names
            record["dex_markers"] = marker_found
            for marker, locations in marker_found.items():
                if not locations:
                    errors.append(f"{spec['id']}: dex marker {marker!r} is absent")
    except (OSError, zipfile.BadZipFile) as error:
        errors.append(f"{spec['id']}: APK archive check failed: {error}")
        return record

    tool_runs: list[dict[str, Any]] = []
    badging = run_tool([tools["aapt2"], "dump", "badging", str(apk)], "aapt2_badging")
    tool_runs.append(badging)
    if badging["returncode"] != 0:
        errors.append(f"{spec['id']}: aapt2 badging failed")
    expected_package = f"package: name='{spec['package']}'"
    if expected_package not in badging["stdout"]:
        errors.append(f"{spec['id']}: aapt2 did not report {expected_package!r}")

    signer = run_tool(
        [tools["apksigner"], "verify", "--print-certs", str(apk)], "apksigner_verify"
    )
    tool_runs.append(signer)
    if signer["returncode"] != 0 or not signer["stdout"].strip():
        errors.append(f"{spec['id']}: apksigner verification did not produce proof")

    alignment = run_tool([tools["zipalign"], "-c", "-v", "4", str(apk)], "zipalign_check")
    tool_runs.append(alignment)
    if alignment["returncode"] != 0 or not alignment["stdout"].strip():
        errors.append(f"{spec['id']}: zipalign verification did not produce proof")

    record["tool_runs"] = tool_runs
    record["ok"] = not any(message.startswith(f"{spec['id']}:") for message in errors)
    return record


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify all Nova APK artifacts")
    parser.add_argument("--repo", required=True, help="repository root")
    parser.add_argument("--evidence", required=True, help="exact evidence JSON path")
    parser.add_argument("--json", action="store_true", help="print the evidence JSON")
    args = parser.parse_args(sys.argv[1:] if argv is None else argv)
    repo = Path(args.repo).resolve()
    evidence_path = Path(args.evidence).resolve()
    errors: list[str] = []
    artifacts: list[dict[str, Any]] = []
    source_markers: list[dict[str, Any]] = []
    build_tools_value = os.environ.get("NOVA_BUILD_TOOLS", "")
    build_tools: dict[str, str] = {}
    try:
        if not build_tools_value:
            raise RuntimeError("NOVA_BUILD_TOOLS is required; refusing to infer an SDK path")
        tools_root = Path(build_tools_value)
        if not tools_root.is_dir():
            raise RuntimeError(f"NOVA_BUILD_TOOLS is not a directory: {tools_root}")
        build_tools = build_tool_paths(tools_root)
        source_markers = check_source_markers(repo, errors)
        for spec in APK_SPECS:
            artifacts.append(check_apk(repo, spec, build_tools, errors))
        if len(artifacts) != len(APK_SPECS):
            errors.append("not every declared APK cell produced a record")
    except Exception as error:  # visibility, never suppression
        errors.append(f"{type(error).__name__}: {error}")
        traceback.print_exc()
    ok = not errors and len(artifacts) == 4 and all(item["ok"] for item in artifacts)
    evidence = {
        "ok": ok,
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "repository_root": str(repo),
        "build_tools": build_tools,
        "source_markers": source_markers,
        "artifact_count": len(artifacts),
        "artifacts": artifacts,
        "errors": errors,
    }
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if args.json:
        # Keep stdout a single JSON object so strict verifiers can parse it;
        # human-readable PASS/FAIL evidence goes to stderr.
        print(json.dumps(evidence, indent=2, sort_keys=True))
    if not ok:
        for error in errors:
            print(f"[check-artifacts] FAIL: {error}", file=sys.stderr)
        return 1
    print(
        "[check-artifacts] PASS: four APKs verified for both flavours",
        file=sys.stderr if args.json else sys.stdout,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
