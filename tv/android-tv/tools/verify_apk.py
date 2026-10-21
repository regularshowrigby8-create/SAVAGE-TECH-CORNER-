#!/usr/bin/env python3
"""Verify a ZeroPlay TV APK before it is offered for sideloading.

This is deliberately TV-specific rather than a reuse of the old SavageBrowser
checks. It verifies the package identity, ARMv7 native-library contract,
Leanback manifest contract, DEX integrity, ZIP alignment, launcher assets and,
when Android SDK tools are available, aapt2/apksigner/zipalign results.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path
from typing import Iterable

EXPECTED_PACKAGE = "com.savage.zeroplay.tv"
EXPECTED_MAIN_ACTIVITY = "com.savage.zeroplay.tv.MainActivity"
EXPECTED_MIN_SDK = 26
EXPECTED_TARGET_SDK = 34
ALLOWED_ABIS = {"armeabi-v7a"}
REQUIRED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def png_size(data: bytes) -> tuple[int, int] | None:
    if len(data) < 24 or not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def candidate_sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_name)
        if value:
            roots.append(Path(value))
    roots.extend([
        Path("/usr/local/lib/android/sdk"),
        Path.home() / "Android" / "Sdk",
        Path.home() / "Library" / "Android" / "sdk",
    ])
    out: list[Path] = []
    for root in roots:
        if root not in out:
            out.append(root)
    return out


def find_sdk_tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    suffixes = ["", ".bat", ".exe"] if os.name == "nt" else [""]
    candidates: list[Path] = []
    for root in candidate_sdk_roots():
        build_tools = root / "build-tools"
        if build_tools.is_dir():
            for version_dir in build_tools.iterdir():
                for suffix in suffixes:
                    candidate = version_dir / f"{name}{suffix}"
                    if candidate.is_file():
                        candidates.append(candidate)
        for relative in (Path("platform-tools"), Path("cmdline-tools/latest/bin")):
            for suffix in suffixes:
                candidate = root / relative / f"{name}{suffix}"
                if candidate.is_file():
                    candidates.append(candidate)
    if not candidates:
        return None
    # The highest build-tools directory sorts last for normal numeric releases.
    return str(sorted(candidates)[-1])


def run_tool(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)


def contains_binary_text(data: bytes, text: str) -> bool:
    return text.encode("utf-8") in data or text.encode("utf-16le") in data


def verify(apk_path: Path, require_sdk_tools: bool = False) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    metrics: dict[str, object] = {
        "apk": str(apk_path),
        "expected_package": EXPECTED_PACKAGE,
        "expected_main_activity": EXPECTED_MAIN_ACTIVITY,
        "allowed_native_abis": sorted(ALLOWED_ABIS),
    }
    if not apk_path.is_file():
        return {"passed": False, "errors": [f"APK not found: {apk_path}"], "warnings": warnings, "metrics": metrics}

    metrics["size_bytes"] = apk_path.stat().st_size
    metrics["sha256"] = sha256_file(apk_path)

    names: list[str] = []
    try:
        with apk_path.open("rb") as raw, zipfile.ZipFile(raw) as zf:
            bad_file = zf.testzip()
            if bad_file:
                errors.append(f"Corrupt ZIP entry: {bad_file}")
            infos = zf.infolist()
            names = [info.filename for info in infos]
            metrics["file_count"] = len(names)

            for required in ("AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/offline.html"):
                if required not in names:
                    errors.append(f"Missing required APK entry: {required}")

            native_libs = sorted(name for name in names if name.startswith("lib/") and name.endswith(".so"))
            native_abis = sorted({name.split("/")[1] for name in native_libs if len(name.split("/")) > 2})
            metrics["native_libraries"] = native_libs
            metrics["native_library_abis"] = native_abis
            disallowed = sorted(set(native_abis) - ALLOWED_ABIS)
            if disallowed:
                errors.append(f"Native libraries use disallowed ABIs for the target box: {', '.join(disallowed)}")

            if "classes.dex" in names:
                dex = zf.read("classes.dex")
                metrics["dex_size_bytes"] = len(dex)
                if len(dex) < 112 or not dex.startswith(b"dex\n"):
                    errors.append("classes.dex has an invalid DEX header")
                else:
                    stored_checksum = struct.unpack_from("<I", dex, 8)[0]
                    computed_checksum = zlib.adler32(dex[12:]) & 0xFFFFFFFF
                    if stored_checksum != computed_checksum:
                        errors.append("classes.dex Adler-32 checksum does not match its contents")
                    stored_signature = dex[12:32]
                    computed_signature = hashlib.sha1(dex[32:]).digest()
                    if stored_signature != computed_signature:
                        errors.append("classes.dex SHA-1 signature does not match its contents")

            manifest = zf.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
            fallback_checks = {
                "package": EXPECTED_PACKAGE,
                "activity": ".MainActivity",
                "Leanback launcher": "android.intent.category.LEANBACK_LAUNCHER",
                "Leanback feature": "android.software.leanback",
                "Internet permission": "android.permission.INTERNET",
                "Network state permission": "android.permission.ACCESS_NETWORK_STATE",
            }
            metrics["binary_manifest_fallback"] = {
                label: contains_binary_text(manifest, needle) for label, needle in fallback_checks.items()
            }
            for label, present in metrics["binary_manifest_fallback"].items():
                if not present:
                    warnings.append(f"Binary-manifest fallback did not find {label}; SDK XML output is authoritative when available")

            banners = [name for name in names if name.endswith("tv_banner.png")]
            icons = [name for name in names if name.endswith("ic_launcher.png")]
            metrics["tv_banner_entries"] = banners
            metrics["launcher_icon_entries"] = icons
            if not banners:
                errors.append("No compiled tv_banner.png found in the APK")
            for banner in banners:
                dimensions = png_size(zf.read(banner))
                metrics.setdefault("tv_banner_sizes", {})[banner] = dimensions
                if dimensions != (320, 180):
                    errors.append(f"TV banner {banner} is {dimensions}, expected (320, 180)")
            if not icons:
                errors.append("No compiled ic_launcher.png found in the APK")
            for icon in icons:
                dimensions = png_size(zf.read(icon))
                metrics.setdefault("launcher_icon_sizes", {})[icon] = dimensions
                if not dimensions or dimensions[0] != dimensions[1] or dimensions[0] < 48:
                    errors.append(f"Launcher icon {icon} has invalid dimensions {dimensions}")

            for info in infos:
                if info.compress_type != zipfile.ZIP_STORED:
                    continue
                raw.seek(info.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b"PK\x03\x04":
                    warnings.append(f"Could not inspect ZIP local header for {info.filename}")
                    continue
                name_len, extra_len = struct.unpack_from("<HH", header, 26)
                data_offset = info.header_offset + 30 + name_len + extra_len
                if data_offset % 4:
                    errors.append(f"Stored ZIP entry is not 4-byte aligned: {info.filename} at {data_offset}")
    except Exception as exc:  # noqa: BLE001 - verifier must report malformed archives.
        errors.append(f"Could not inspect APK ZIP: {exc}")

    aapt2 = find_sdk_tool("aapt2") or find_sdk_tool("aapt")
    apksigner = find_sdk_tool("apksigner")
    zipalign = find_sdk_tool("zipalign")
    metrics["sdk_tools"] = {"aapt": aapt2, "apksigner": apksigner, "zipalign": zipalign}

    if require_sdk_tools:
        for label, tool in (("aapt2/aapt", aapt2), ("apksigner", apksigner), ("zipalign", zipalign)):
            if not tool:
                errors.append(f"Required Android SDK tool not found: {label}")
    else:
        for label, tool in (("aapt2/aapt", aapt2), ("apksigner", apksigner), ("zipalign", zipalign)):
            if not tool:
                warnings.append(f"Android SDK tool not found, skipped: {label}")

    if aapt2:
        badging = run_tool([aapt2, "dump", "badging", str(apk_path)])
        metrics["aapt_badging_exit"] = badging.returncode
        if badging.returncode != 0:
            errors.append("aapt dump badging failed")
            warnings.append(badging.stdout[-1000:])
        else:
            output = badging.stdout
            package_match = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", output)
            if package_match:
                metrics["package"] = package_match.group(1)
                metrics["version_code"] = package_match.group(2)
                metrics["version_name"] = package_match.group(3)
                if package_match.group(1) != EXPECTED_PACKAGE:
                    errors.append(f"Package mismatch: {package_match.group(1)}")
            else:
                errors.append("aapt badging did not report package/version")
            sdk_match = re.search(r"sdkVersion:'(\d+)'", output)
            target_match = re.search(r"targetSdkVersion:'(\d+)'", output)
            if sdk_match:
                metrics["min_sdk"] = int(sdk_match.group(1))
                if metrics["min_sdk"] != EXPECTED_MIN_SDK:
                    errors.append(f"Min SDK mismatch: {metrics['min_sdk']}")
            if target_match:
                metrics["target_sdk"] = int(target_match.group(1))
                if metrics["target_sdk"] != EXPECTED_TARGET_SDK:
                    errors.append(f"Target SDK mismatch: {metrics['target_sdk']}")
            native_match = re.search(r"native-code: ([^\n]+)", output)
            if native_match:
                badging_abis = re.findall(r"'([^']+)'", native_match.group(1))
                metrics["aapt_native_code"] = badging_abis
                if set(badging_abis) - ALLOWED_ABIS:
                    errors.append(f"aapt reports disallowed native ABIs: {badging_abis}")
            permissions = set(re.findall(r"uses-permission: name='([^']+)'", output))
            metrics["permissions"] = sorted(permissions)
            missing_permissions = REQUIRED_PERMISSIONS - permissions
            if missing_permissions:
                errors.append(f"Missing required permissions: {sorted(missing_permissions)}")
            unexpected_permissions = permissions - REQUIRED_PERMISSIONS
            if unexpected_permissions:
                warnings.append(f"Unexpected permissions present: {sorted(unexpected_permissions)}")

        xmltree = run_tool([aapt2, "dump", "xmltree", str(apk_path), "AndroidManifest.xml"])
        metrics["aapt_xmltree_exit"] = xmltree.returncode
        if xmltree.returncode != 0:
            errors.append("aapt dump xmltree failed for AndroidManifest.xml")
        else:
            xml = xmltree.stdout
            for label, needle in (
                ("MainActivity", ".MainActivity"),
                ("Leanback launcher category", "android.intent.category.LEANBACK_LAUNCHER"),
                ("Leanback feature", "android.software.leanback"),
                ("TV banner", "tv_banner"),
            ):
                if needle not in xml:
                    errors.append(f"Compiled manifest does not contain {label}: {needle}")
            if 'android.hardware.touchscreen' in xml and 'required(0x010102bd)=true' in xml:
                warnings.append("Touchscreen appears in the manifest; confirm it remains optional in aapt feature output")

    if apksigner:
        signed = run_tool([apksigner, "verify", "--verbose", "--print-certs", str(apk_path)])
        metrics["apksigner_exit"] = signed.returncode
        if signed.returncode != 0:
            errors.append("apksigner verification failed")
            warnings.append(signed.stdout[-1000:])
        else:
            output = signed.stdout
            metrics["signature_schemes"] = {
                "v1": bool(re.search(r"Verified using v1 scheme.*true", output, re.I)),
                "v2": bool(re.search(r"Verified using v2 scheme.*true", output, re.I)),
                "v3": bool(re.search(r"Verified using v3 scheme.*true", output, re.I)),
                "v4": bool(re.search(r"Verified using v4 scheme.*true", output, re.I)),
            }
            if not (metrics["signature_schemes"]["v2"] or metrics["signature_schemes"]["v3"]):
                errors.append("APK is not verified with signature scheme v2 or v3")
            cert_match = re.search(r"Signer #1 certificate DN: (.+)", output)
            if cert_match:
                metrics["signer_certificate_dn"] = cert_match.group(1).strip()

    if zipalign:
        aligned = run_tool([zipalign, "-c", "-v", "4", str(apk_path)])
        metrics["zipalign_exit"] = aligned.returncode
        if aligned.returncode != 0:
            errors.append("zipalign verification failed")

    return {"passed": not errors, "errors": errors, "warnings": warnings, "metrics": metrics}


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify the ZeroPlay TV ARMv7 APK")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--json", dest="json_path", type=Path, help="Write the full report to this JSON file")
    parser.add_argument("--require-sdk-tools", action="store_true", help="Fail unless aapt, apksigner and zipalign are available")
    args = parser.parse_args(list(argv) if argv is not None else None)

    report = verify(args.apk, require_sdk_tools=args.require_sdk_tools)
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(rendered + "\n", encoding="utf-8")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
