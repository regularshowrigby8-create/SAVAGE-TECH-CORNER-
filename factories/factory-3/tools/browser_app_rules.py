#!/usr/bin/env python3
"""Production Browser Application Invariant Enforcement Rules.

STRICT INTEGRITY ENFORCEMENT & ANTI-DECEPTION INVARIANTS (now source-driven):
1. Anti-Foreign / Provenance Invariant: Rejects any third-party app packages, stub
   packages, or unrelated projects (e.g. barebones/Lightning). The application
   package identifier MUST match 'com.savage.soulbrowser'.
2. Anti-Mock Invariant: Rejects stub activities (empty onCreate) and missing
   compiled-resource wiring in the source tree.
3. Android Standards: Requires a complete Gradle source skeleton (manifest,
   Java sources, resources, build scripts).

Per the "delete all APK versions, keep the architecture" requirement, these rules
now audit the Android *source* architecture (``shell/android_project/``) rather than
a tracked APK binary. The previous APK-binary audit entry point is deprecated and
fails closed; CI audits freshly-built APKs separately.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, List

def _find_repo_root(start: Path) -> Path:
    """Walk upward until we find the repo root (identified by shell/android_project)."""
    here = start
    for _ in range(10):
        if (here / "shell" / "android_project").exists():
            return here
        if here.parent == here:
            break
        here = here.parent
    return start


ROOT_DIR = _find_repo_root(Path(__file__).resolve().parent)
ANDROID_PROJECT_DIR = ROOT_DIR / "shell" / "android_project"

APP_PACKAGE = "com.savage.soulbrowser"

# Foreign provenance tokens that must never appear in our own source identity.
FOREIGN_TOKENS = ["acr.browser", "anthonycr", "barebones", "lightning"]

SCANNED_SOURCE_SUFFIXES = {".xml", ".java", ".kt", ".kts", ".gradle", ".properties", ".pro"}


def _iter_source_files(project_dir: Path):
    if not project_dir.exists():
        return
    for path in sorted(project_dir.rglob("*")):
        if path.is_file() and path.suffix.lower() in SCANNED_SOURCE_SUFFIXES:
            yield path


def verify_browser_source_rules(project_dir: Path = ANDROID_PROJECT_DIR) -> Dict[str, Any]:
    """Audit the Savage Browser Android *source* architecture for provenance &
    completeness invariants. Returns a dict with ``passed`` and ``violations``."""
    violations: List[str] = []

    if not project_dir.exists():
        return {"passed": False, "violations": [f"Android source project missing at {project_dir}"]}

    manifest_path = project_dir / "app" / "src" / "main" / "AndroidManifest.xml"
    app_gradle_path = project_dir / "app" / "build.gradle.kts"
    java_root = project_dir / "app" / "src" / "main" / "java"

    if not manifest_path.exists():
        violations.append("Missing app/src/main/AndroidManifest.xml")
    if not app_gradle_path.exists():
        violations.append("Missing app/build.gradle.kts")
    if not java_root.exists():
        violations.append("Missing app/src/main/java source root")
    if violations:
        return {"passed": False, "violations": violations}

    manifest = manifest_path.read_text(encoding="utf-8", errors="ignore")
    app_gradle = app_gradle_path.read_text(encoding="utf-8", errors="ignore")
    java_files = list(java_root.rglob("*.java"))
    java_sources = [p.read_text(encoding="utf-8", errors="ignore") for p in java_files]

    # Rule 1: Package identity.
    if f'applicationId = "{APP_PACKAGE}"' not in app_gradle and f"applicationId = '{APP_PACKAGE}'" not in app_gradle:
        violations.append(f"applicationId must be {APP_PACKAGE} in app/build.gradle.kts")
    if APP_PACKAGE not in manifest and "savage" not in manifest.lower():
        violations.append(f"Package identity ({APP_PACKAGE}) not declared in AndroidManifest.xml")

    # Rule 2: Provenance / anti-foreign invariant across all scanned sources.
    for src_file in _iter_source_files(project_dir):
        text = src_file.read_text(encoding="utf-8", errors="ignore").lower()
        rel = src_file.relative_to(project_dir).as_posix()
        for tok in FOREIGN_TOKENS:
            if tok in text:
                violations.append(f"Foreign provenance token '{tok}' found in source file '{rel}'")

    # Rule 3: Non-stub / completeness — real activity classes with wiring.
    if not java_files:
        violations.append("No Java source classes present under app/src/main/java")
    else:
        has_activity = any(re.search(r"class\s+\w+\s+extends\s+\w*Activity", src) for src in java_sources)
        if not has_activity:
            violations.append("No concrete android.app.Activity subclass found in Java sources")
        # Anti-mock: an activity whose onCreate is empty is a stub.
        for src in java_sources:
            m = re.search(r"class\s+(\w+)\s+extends\s+\w*Activity", src)
            if m:
                oncreate = re.search(r"onCreate\s*\([^)]*\)\s*\{(.*?)\n\s*\}", src, re.DOTALL)
                if oncreate and not re.search(r"setContentView|super\.onCreate", oncreate.group(1)):
                    violations.append(f"Activity {m.group(1)} has an empty/stub onCreate body")

    # Rule 4: Resource & layout wiring.
    res_dir = project_dir / "app" / "src" / "main" / "res"
    if not res_dir.exists():
        violations.append("Missing app/src/main/res resource directory")
    else:
        if not (res_dir / "values" / "strings.xml").exists():
            violations.append("Missing res/values/strings.xml")
        if not list(res_dir.rglob("*.xml")):
            violations.append("No resource XML files present under res/")

    return {"passed": len(violations) == 0, "violations": violations}


def verify_browser_app_rules(apk_path=None) -> Dict[str, Any]:
    """Deprecated APK-binary audit (fail-closed).

    No APK binary is tracked in this repository anymore. Use
    :func:`verify_browser_source_rules` for the source architecture, or audit a
    freshly-built APK in CI. This entry point raises a clear error instead of
    silently passing."""
    raise NotImplementedError(
        "verify_browser_app_rules() is deprecated: repository no longer tracks any APK binary. "
        "Use verify_browser_source_rules() for the source architecture instead."
    )


def main() -> int:
    import json

    res = verify_browser_source_rules(ANDROID_PROJECT_DIR)
    print(json.dumps(res, indent=2))
    return 0 if res["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
