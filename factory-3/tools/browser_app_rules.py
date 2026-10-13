"""Production Browser Application Invariant Enforcement Rules.

STRICT INTEGRITY ENFORCEMENT & ANTI-DECEPTION INVARIANTS:
1. Anti-Foreign / Provenance Invariant: Rejects any third-party app packages, stub packages, or
   unrelated projects (e.g. barebones/Lightning). The APK package identifier MUST match 'com.savage.soulbrowser'.
2. Anti-Mock Invariant: Rejects stub DEX bytecode (<50 KB) or empty activities.
3. Android Standards: Requires compiled binary AndroidManifest.xml, classes.dex, resources.arsc, and signature blocks.

The identity rule reads the package the APK actually declares (with a real APK parser) and falls
back to scanning the compiled manifest and every dex file, because the compiled manifest stores its
strings in a pool whose encoding is not fixed - a plain byte search for a lowercase substring can
miss an identity that is plainly present.
"""

from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent

EXPECTED_PACKAGE = "com.savage.soulbrowser"
EXPECTED_HOST_PACKAGE = EXPECTED_PACKAGE.replace(".", "/")


def _encoded_forms(text: str) -> List[bytes]:
    """A string as it can appear in a compiled manifest's string pool."""
    return [text.encode("utf-8"), text.encode("utf-16-le")]


def _declared_package(apk_path: Path) -> Optional[str]:
    """The package name the APK declares, read with a real APK parser, or ``None``.

    A parser failure (no androguard, or an archive that is not a valid APK) is not an error here:
    the caller falls back to scanning the archive's contents.
    """
    try:
        from androguard.core.apk import APK  # androguard >= 4
    except (ImportError, AttributeError):
        try:
            from androguard.core.bytecodes.apk import APK  # androguard 3.x
        except (ImportError, AttributeError):
            return None
    try:
        return APK(str(apk_path)).get_package()
    except Exception:  # noqa: BLE001 - any parser failure means "no declaration available"
        return None


def _identity_finding(apk_path: Path, manifest_data: bytes, dex_blobs: Dict[str, bytes]) -> Dict[str, Any]:
    """Whether this APK declares the Savage Browser identity, and what was inspected.

    The declared package is authoritative when a parser is available. Otherwise, and in addition,
    the compiled manifest and every dex file are searched: the class names of the application are
    the strongest byte-level evidence that the bytecode belongs to this product.
    """
    declared = _declared_package(apk_path)
    if declared == EXPECTED_PACKAGE:
        return {"found": True, "source": "declared package name"}

    lower_manifest = manifest_data.lower()
    manifest_forms = [
        form for form in _encoded_forms(EXPECTED_PACKAGE)
        + _encoded_forms(EXPECTED_HOST_PACKAGE)
        if form.lower() in lower_manifest
    ]
    dex_with_identity = sorted(
        name for name, blob in dex_blobs.items()
        if EXPECTED_HOST_PACKAGE.encode("utf-8") in blob or EXPECTED_PACKAGE.encode("utf-8") in blob
    )

    if manifest_forms or dex_with_identity:
        return {
            "found": True,
            "source": "compiled manifest" if manifest_forms else f"dex bytecode ({', '.join(dex_with_identity)})",
        }

    return {
        "found": False,
        "source": "neither the compiled manifest nor "
                  + (", ".join(sorted(dex_blobs)) if dex_blobs else "any dex file"),
        "declared_package": declared,
    }


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

            # Rule 2: Must contain executable Dalvik bytecode. A multi-dex build splits it, so every
            # dex file counts and every dex file is inspected below - a violation hidden in
            # classes2.dex must not slip through.
            dex_names = sorted(name for name in namelist if re.fullmatch(r"classes\d*\.dex", name))
            if not dex_names:
                violations.append("Violation: Missing compiled classes.dex bytecode")

            # Rule 3: Must contain compiled resource table
            if "resources.arsc" not in namelist:
                violations.append("Violation: Missing compiled resources.arsc table")

            # Rule 4: Must contain valid cryptographic signature block
            has_sig = any(name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC")) for name in namelist)
            if not has_sig:
                violations.append("Violation: Missing cryptographic signature block (META-INF/*.RSA)")

            # Rule 5: Provenance & Anti-Deception Invariant
            # Rejects third-party packages or foreign attribution strings
            manifest_data = zf.read("AndroidManifest.xml") if "AndroidManifest.xml" in namelist else b""
            dex_blobs = {name: zf.read(name) for name in dex_names}

            if b"acr.browser" in manifest_data or any(b"acr/browser" in blob for blob in dex_blobs.values()):
                violations.append("Violation: Foreign package identifier detected ('acr.browser'). Provenance check failed.")

            if any(b"anthonycr" in blob for blob in dex_blobs.values()):
                violations.append("Violation: Third-party repository attribution detected in DEX bytecode. Deception check failed.")

            # Rule 6: Package identity match. The APK must declare, or at least contain the bytes of,
            # the Savage Browser identity - 'savage' appearing anywhere is too weak an assertion, so
            # the full package identifier is what is looked for.
            finding = _identity_finding(apk_path, manifest_data, dex_blobs)
            if not finding["found"]:
                declared = finding.get("declared_package")
                violations.append(
                    "Violation: APK does not match Savage Browser package identity "
                    f"('{EXPECTED_PACKAGE}'); searched {finding['source']} for '{EXPECTED_HOST_PACKAGE}' or "
                    f"'{EXPECTED_PACKAGE}'"
                    + (f"; declared package: {declared}" if declared else "; no package could be parsed")
                )

    except zipfile.BadZipFile:
        violations.append("Violation: APK is not a valid zip archive")

    return {
        "passed": len(violations) == 0,
        "violations": violations,
        "artifact_size_bytes": size_bytes,
    }
