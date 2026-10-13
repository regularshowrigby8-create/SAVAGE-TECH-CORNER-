#!/usr/bin/env python3
"""APK identity, version and signing gate.

Checks a *given* APK against the identity the Android project declares in
``shell/android_project/app/build.gradle.kts`` and reports the exact evidence (file size, SHA-256,
package, versionCode, versionName, min/target SDK, signing schemes, certificate identity).

Design rules that this tool follows:
* it never defaults to a pre-built APK in the repository. The path must be supplied with ``--apk``,
  because a file that was not built by this checkout proves nothing about this checkout;
* a missing dependency (androguard) is a failure, not a pass: without it the manifest cannot be
  read, so the result would be an assertion without evidence;
* it exits non-zero on the first mismatch and prints every finding.

usage:
    python3 tools/reproducible_build_verifier.py --apk <path> [--expect-signed] [--json out.json]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
APP_GRADLE = ROOT_DIR / "shell" / "android_project" / "app" / "build.gradle.kts"
MANIFEST = ROOT_DIR / "shell" / "android_project" / "app" / "src" / "main" / "AndroidManifest.xml"

# androguard logs the whole parsing process at DEBUG level; the gate only needs its results.
logging.getLogger("androguard").setLevel(logging.CRITICAL)
logging.getLogger("asn1crypto").setLevel(logging.CRITICAL)
logging.getLogger("loguru").setLevel(logging.CRITICAL)


def read_expected_identity() -> dict:
    """Reads the expected identity from the Gradle build file (single source of truth)."""
    text = APP_GRADLE.read_text(encoding="utf-8")
    expected = {}
    for key, pattern in (
        ("application_id", r'applicationId\s*=\s*"([^"]+)"'),
        ("version_name", r'versionName\s*=\s*"([^"]+)"'),
        ("version_code", r"versionCode\s*=\s*(\d+)"),
        ("min_sdk", r"minSdk\s*=\s*(\d+)"),
        ("target_sdk", r"targetSdk\s*=\s*(\d+)"),
        ("compile_sdk", r"compileSdk\s*=\s*(\d+)"),
    ):
        match = re.search(pattern, text)
        if match is None:
            raise SystemExit(f"FATAL: {APP_GRADLE} does not declare {key}")
        expected[key] = match.group(1)
    return expected


def read_source_contract() -> dict:
    """Reads what the *source* declares: launcher activity and requested permissions."""
    namespace = "{http://schemas.android.com/apk/res/android}"
    root = ElementTree.parse(MANIFEST).getroot()
    permissions = {
        element.get(namespace + "name")
        for element in root.findall("uses-permission")
    }
    launcher = None
    for activity in root.findall("application/activity"):
        for intent_filter in activity.findall("intent-filter"):
            actions = {a.get(namespace + "name") for a in intent_filter.findall("action")}
            categories = {c.get(namespace + "name") for c in intent_filter.findall("category")}
            if ("android.intent.action.MAIN" in actions
                    and "android.intent.category.LAUNCHER" in categories):
                launcher = activity.get(namespace + "name")
    return {"permissions": sorted(permissions), "launcher_activity": launcher}


def load_apk(apk_path: Path):
    try:
        from androguard.core.apk import APK  # androguard >= 4
    except (ImportError, AttributeError):
        try:
            from androguard.core.bytecodes.apk import APK  # androguard 3.x
        except (ImportError, AttributeError):
            print("FATAL: androguard is required to read the APK manifest.")
            print("       install it with: python3 -m pip install 'androguard>=4.0'")
            raise SystemExit(2)
    return APK(str(apk_path))


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_zip(apk_path: Path) -> list:
    problems = []
    if not zipfile.is_zipfile(apk_path):
        return [f"{apk_path} is not a valid ZIP archive"]
    with zipfile.ZipFile(apk_path) as archive:
        names = set(archive.namelist())
        for required in ("AndroidManifest.xml", "resources.arsc"):
            if required not in names:
                problems.append(f"the APK is missing {required}")
        if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
            problems.append("the APK has no classes*.dex")
        if archive.testzip() is not None:
            problems.append("the APK ZIP is corrupt (bad CRC)")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description="APK identity, version and signing gate")
    parser.add_argument("--apk", required=True, help="path of the APK to verify")
    parser.add_argument("--expect-signed", action="store_true",
                        help="fail when the APK has no signature block")
    parser.add_argument("--json", help="write the evidence to this JSON file")
    args = parser.parse_args()

    apk_path = Path(args.apk).resolve()
    errors: list = []

    if not apk_path.is_file():
        print(f"[APK-GATE] FAIL: APK does not exist at {apk_path}")
        print("[APK-GATE] an APK that was not built by this checkout cannot be verified; "
              "run the Gradle build first (see .github/workflows/android-build.yml)")
        return 1

    expected = read_expected_identity()
    errors.extend(check_zip(apk_path))

    evidence = {
        "apk": str(apk_path),
        "size_bytes": apk_path.stat().st_size,
        "sha256": sha256_of(apk_path),
        "expected": expected,
        "source_of_expectations": str(APP_GRADLE.relative_to(ROOT_DIR)),
    }

    if errors:
        print("[APK-GATE] FAIL:")
        for problem in errors:
            print(f"  - {problem}")
        return 1

    apk = load_apk(apk_path)
    evidence["package"] = apk.get_package()
    evidence["version_name"] = apk.get_androidversion_name()
    evidence["version_code"] = apk.get_androidversion_code()
    evidence["min_sdk"] = apk.get_min_sdk_version()
    evidence["target_sdk"] = apk.get_target_sdk_version()
    evidence["permissions"] = sorted(apk.get_permissions())
    evidence["is_signed_v1"] = bool(apk.is_signed_v1())
    evidence["is_signed_v2"] = bool(apk.is_signed_v2())
    evidence["is_signed_v3"] = bool(apk.is_signed_v3())
    evidence["main_activity"] = apk.get_main_activity()
    evidence["activities"] = sorted(apk.get_activities())
    certificates = apk.get_certificates()
    if certificates:
        first = certificates[0]
        evidence["certificate_sha256"] = hashlib.sha256(first.dump()).hexdigest()
        subject = first.subject
        evidence["certificate_subject"] = str(
            getattr(subject, "human_friendly", subject)).replace("\n", "; ")
    else:
        evidence["certificate_sha256"] = None
        evidence["certificate_subject"] = None

    if evidence["package"] != expected["application_id"]:
        errors.append(f"package mismatch: expected {expected['application_id']}, "
                      f"found {evidence['package']}")
    if str(evidence["version_name"]) != expected["version_name"]:
        errors.append(f"versionName mismatch: expected {expected['version_name']}, "
                      f"found {evidence['version_name']}")
    if str(evidence["version_code"]) != expected["version_code"]:
        errors.append(f"versionCode mismatch: expected {expected['version_code']}, "
                      f"found {evidence['version_code']}")
    if str(evidence["min_sdk"]) != expected["min_sdk"]:
        errors.append(f"minSdk mismatch: expected {expected['min_sdk']}, found {evidence['min_sdk']}")
    if str(evidence["target_sdk"]) != expected["target_sdk"]:
        errors.append(f"targetSdk mismatch: expected {expected['target_sdk']}, "
                      f"found {evidence['target_sdk']}")

    allowed_permissions = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    }
    unexpected_permissions = sorted(set(evidence["permissions"]) - allowed_permissions)
    if unexpected_permissions:
        errors.append(f"unexpected permissions in the APK: {unexpected_permissions}")

    signed = bool(evidence["is_signed_v1"] or evidence["is_signed_v2"] or evidence["is_signed_v3"])
    if args.expect_signed and not signed:
        errors.append("the APK is not signed but --expect-signed was requested")

    contract = read_source_contract()
    evidence["source_contract"] = contract
    if contract["launcher_activity"] is None:
        errors.append(f"{MANIFEST.relative_to(ROOT_DIR)} declares no launcher activity")
    else:
        expected_launcher = expected["application_id"] + contract["launcher_activity"]
        if evidence["main_activity"] != expected_launcher:
            errors.append(f"launcher mismatch: the manifest declares {expected_launcher}, "
                          f"the APK launches {evidence['main_activity']}")
    if sorted(contract["permissions"]) != sorted(evidence["permissions"]):
        errors.append("the permissions in the APK differ from the permissions in the manifest: "
                      f"manifest={sorted(contract['permissions'])} apk={sorted(evidence['permissions'])}")

    print(f"[APK-GATE] APK:            {evidence['apk']}")
    print(f"[APK-GATE] size / sha256:  {evidence['size_bytes']} bytes / {evidence['sha256']}")
    print(f"[APK-GATE] package:        {evidence['package']} "
          f"(expected {expected['application_id']})")
    print(f"[APK-GATE] version:        {evidence['version_name']} "
          f"({evidence['version_code']}) (expected {expected['version_name']} "
          f"({expected['version_code']}))")
    print(f"[APK-GATE] sdk:            min {evidence['min_sdk']} / target {evidence['target_sdk']}")
    print(f"[APK-GATE] permissions:    {evidence['permissions']}")
    print(f"[APK-GATE] signed:         v1={evidence['is_signed_v1']} v2={evidence['is_signed_v2']} "
          f"v3={evidence['is_signed_v3']}")
    print(f"[APK-GATE] launcher:       {evidence['main_activity']}")
    print(f"[APK-GATE] activities:     {len(evidence['activities'])} declared")
    print(f"[APK-GATE] certificate:    {evidence['certificate_subject']} "
          f"sha256={evidence['certificate_sha256']}")

    if args.json:
        Path(args.json).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(f"[APK-GATE] evidence written to {args.json}")

    if errors:
        print("[APK-GATE] FAIL:")
        for problem in errors:
            print(f"  - {problem}")
        return 1
    print("[APK-GATE] PASS: identity, version, permissions and signing verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
