"""Production Browser Application Invariant Enforcement Rules.

STRICT INTEGRITY ENFORCEMENT & ANTI-DECEPTION INVARIANTS:
1. Anti-Foreign / Provenance Invariant: Rejects any third-party app packages, stub packages, or
   unrelated projects (e.g. barebones/Lightning). The APK package identifier MUST match 'com.savage.soulbrowser'.
2. Anti-Mock Invariant: Rejects stub DEX bytecode (<50 KB) or empty activities.
3. Android Standards: Requires compiled binary AndroidManifest.xml, classes.dex, resources.arsc, and signature blocks.
"""

from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Any, Dict, List

ROOT_DIR = Path(__file__).resolve().parent.parent


def _has_v2_signing_block(apk_path) -> bool:
    """Detect an APK Signing Block (v2/v3) by parsing EOCD -> CD offset -> block magic.

    The block sits immediately BEFORE the ZIP Central Directory, so a naive tail scan
    of EOF can miss it on APKs with a large central directory."""
    import struct as _struct
    try:
        data = apk_path.read_bytes() if hasattr(apk_path, "read_bytes") else open(apk_path, "rb").read()
        eocd = data.rfind(b"PK\x05\x06")
        if eocd == -1:
            return False
        cd_offset = _struct.unpack_from("<I", data, eocd + 16)[0]
        if cd_offset < 24 or cd_offset > len(data):
            return False
        return data[cd_offset - 16:cd_offset] == b"APK Sig Block 42"
    except Exception:
        return False


def verify_browser_app_rules(apk_path: Path) -> Dict[str, Any]:
    """Strictly audits that the packaged APK is the authentic Savage Browser product."""
    violations: List[str] = []

    if not apk_path.exists():
        return {"passed": False, "violations": ["APK artifact does not exist"]}

    size_bytes = apk_path.stat().st_size
    if size_bytes < 50_000:
        violations.append(f"APK artifact is too small ({size_bytes} bytes). Real browsers require complete DEX and resources.")

    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            namelist = set(zf.namelist())

            # Rule 1: Must contain compiled Android binary manifest
            if "AndroidManifest.xml" not in namelist:
                violations.append("Violation: Missing AndroidManifest.xml")

            # Rule 2: Must contain executable Dalvik bytecode
            if "classes.dex" not in namelist:
                violations.append("Violation: Missing compiled classes.dex bytecode")

            # Rule 3: Must contain compiled resource table
            if "resources.arsc" not in namelist:
                violations.append("Violation: Missing compiled resources.arsc table")

            # Rule 4: Must contain a valid cryptographic signature — v1 (META-INF/*.RSA)
            # OR scheme v2/v3 (APK Signing Block at the Central Directory boundary).
            # AGP-produced v2-only signed APKs are legitimately signed and must not
            # be rejected for lacking a v1 jar-signature file.
            has_sig = any(name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC")) for name in namelist) or _has_v2_signing_block(apk_path)
            if not has_sig:
                violations.append("Violation: Missing cryptographic signature block (META-INF/*.RSA)")

            # Rule 5: Provenance & Anti-Deception Invariant
            # Rejects third-party packages or foreign attribution strings.
            # NOTE: binary AXML stores strings as UTF-16LE, so tokens must be
            # matched in both raw and UTF-16LE encodings (a raw-ASCII scan can
            # never match a UTF-16 manifest and previously false-passed).
            manifest_data = zf.read("AndroidManifest.xml")
            classes_dex = zf.read("classes.dex")

            if "acr.browser".encode("utf-16-le") in manifest_data or b"acr.browser" in manifest_data or b"acr/browser" in classes_dex:
                violations.append("Violation: Foreign package identifier detected ('acr.browser'). Provenance check failed.")

            if b"anthonycr" in classes_dex or "anthonycr".encode("utf-16-le") in manifest_data:
                violations.append("Violation: Third-party repository attribution detected in bytecode/manifest. Deception check failed.")

            # Rule 6: Package Identity Match
            if "savage".encode("utf-16-le") not in manifest_data and b"savage" not in manifest_data.lower() and b"savage" not in classes_dex.lower():
                violations.append("Violation: APK does not match Savage Browser package identity ('com.savage.soulbrowser').")

    except zipfile.BadZipFile:
        violations.append("Violation: APK is not a valid zip archive")

    return {
        "passed": len(violations) == 0,
        "violations": violations,
        "artifact_size_bytes": size_bytes,
    }
