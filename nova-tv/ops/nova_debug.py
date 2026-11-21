#!/usr/bin/env python3
"""One debug session over the nova-tv tree: app, tooling and shipped APKs.

Why this exists
---------------
The repo already has importable auditors (`nova.appaudit`, `nova.buttons`) and a
structural Kotlin guard (`ops/kotlin_lint.py`), but nothing tied them to the
artefacts that were committed for sideloading. That gap is where a stale APK
ships: the source moves, CI fails for an unrelated reason, and the .apk in
`artifacts/` still says "verified".

Each check returns a row with a status:

  PASS  it ran and the result is what the commit claims
  FAIL  it ran and the result contradicts the claim (or the artefact is broken)
  WARN  it ran and found something that is worth reading but not fatal
  SKIP  the environment cannot run it (no Android SDK, for example)

Run:  python3 nova-tv/ops/nova_debug.py [--json] [--root PATH]

Exit code is 1 when any check FAILs, so CI can gate on it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

HERE = Path(__file__).resolve().parent
NOVA = HERE.parent                      # nova-tv/
REPO = NOVA.parent                      # repository root
PY_DIR = NOVA / "python"

# package id each flavour must carry; a cross-flavoured build is uninstallable
# side by side with the other one, which is the whole point of two APKs.
EXPECTED = {
    "nova-tv": {"package": "dev.nova.tv", "version_suffix": ""},
    "nova-mobile": {"package": "dev.nova.mobile", "version_suffix": "-mobile"},
}

# Behaviour added in the 2026-11-19/20 session. A dex string is the cheapest
# proof that the committed binary was built from that source: R8 renames
# symbols but never rewrites a user-visible literal. (commit, literal, artifacts)
FEATURE_MARKERS = [
    ("7bba316", "Buffering \u00b7 caching file", None),
    ("a205409", "Lowering quality to keep playing", None),
    ("1913fc4", "cinemeta-catalogs.strem.io", None),
    ("ae0dedf", "Nova download", ("nova-mobile",)),
]

WINDOW = ("ae0dedf", "d322cc2")          # first and last commit of the session


@dataclass
class Row:
    check: str
    status: str
    detail: str
    data: Dict[str, object] = field(default_factory=dict)

    def as_dict(self) -> Dict[str, object]:
        return {"check": self.check, "status": self.status, "detail": self.detail, **({"data": self.data} if self.data else {})}


def _run(cmd: List[str], cwd: Optional[Path] = None, timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=str(cwd or REPO), capture_output=True, text=True, timeout=timeout)


# -- source-level checks -----------------------------------------------------

def check_appaudit() -> Row:
    """Defects that compile cleanly: dead code, write-only state, unguarded IO."""
    try:
        sys.path.insert(0, str(PY_DIR))
        from nova import appaudit  # noqa: WPS433 - deliberate late import
        from nova.config import ROOT  # the root the auditors expect: nova-tv/
    except Exception as exc:                                    # pragma: no cover
        return Row("appaudit", "SKIP", f"could not import nova.appaudit: {exc}")
    findings = appaudit.audit(ROOT)
    summary = appaudit.summarise(findings)
    critical = sum(1 for f in findings if getattr(f, "severity", "") == appaudit.CRITICAL)
    status = "FAIL" if critical else ("WARN" if findings else "PASS")
    detail = f"{len(findings)} finding(s), {critical} critical"
    return Row("appaudit", status, detail, {"summary": summary})


def check_dead_buttons() -> Row:
    """Controls that look live and write state nothing renders."""
    try:
        sys.path.insert(0, str(PY_DIR))
        from nova import buttons
        from nova.config import ROOT
    except Exception as exc:                                    # pragma: no cover
        return Row("dead-buttons", "SKIP", f"could not import nova.buttons: {exc}")
    findings = buttons.audit(ROOT)
    kinds = {}
    for f in findings:
        kinds[f.kind] = kinds.get(f.kind, 0) + 1
    status = "WARN" if findings else "PASS"
    detail = f"{len(findings)} finding(s)" + (f": {kinds}" if kinds else "")
    return Row("dead-buttons", status, detail, {"kinds": kinds})


def check_structure() -> Row:
    """ops/kotlin_lint.py: package/dir mismatch, duplicate symbols, unused imports."""
    script = NOVA / "ops" / "kotlin_lint.py"
    proc = _run([sys.executable, str(script)], cwd=NOVA)
    out = (proc.stdout + proc.stderr).strip()
    problems = [ln.strip() for ln in out.splitlines() if ln.strip().startswith("- ")]
    status = "PASS" if proc.returncode == 0 else "WARN"
    detail = f"{len(problems)} structural problem(s)" if problems else "clean"
    return Row("kotlin-structure", status, detail, {"problems": problems})


def check_python_suite() -> Row:
    """The tooling that audits the app must itself pass."""
    proc = _run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."], cwd=PY_DIR, timeout=600)
    out = proc.stdout + proc.stderr
    m = re.search(r"Ran (\d+) tests?", out)
    ran = int(m.group(1)) if m else 0
    ok = proc.returncode == 0 and "OK" in out
    return Row("python-tests", "PASS" if ok else "FAIL", f"{ran} test(s), exit {proc.returncode}")


# -- artefact checks ---------------------------------------------------------

def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _dex_blob(apk: Path) -> bytes:
    with zipfile.ZipFile(apk) as bundle:
        return b"".join(bundle.read(n) for n in bundle.namelist() if n.endswith(".dex"))


def _host_tool(name: str) -> Optional[str]:
    found = shutil.which(name)
    if found:
        return found
    for base in filter(None, [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]):
        for candidate in sorted(Path(base).glob(f"build-tools/*/{name}"), reverse=True):
            return str(candidate)
    return None


def check_apk(name: str) -> Row:
    """Signature-free facts about one committed APK, cross-checked against the sidecar."""
    apk = REPO / "artifacts" / name / f"{name}-release.apk"
    sidecar = apk.with_suffix(".apk.sha256")
    want = EXPECTED[name]
    if not apk.exists():
        return Row(f"apk:{name}", "FAIL", f"missing {apk.relative_to(REPO)}")

    problems: List[str] = []
    data: Dict[str, object] = {"bytes": apk.stat().st_size}

    actual = _sha256(apk)
    data["sha256"] = actual
    if not sidecar.exists():
        problems.append("no .sha256 sidecar")
    else:
        recorded = sidecar.read_text().split()[0].strip()
        if recorded != actual:
            problems.append(f"sidecar says {recorded[:12]}…, file is {actual[:12]}…")

    aapt2 = _host_tool("aapt2")
    if aapt2:
        badging = _run([aapt2, "dump", "badging", str(apk)]).stdout
        package = re.search(r"package: name='([^']+)'", badging)
        version = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
        if package:
            data["package"] = package.group(1)
            if package.group(1) != want["package"]:
                problems.append(f"package is {package.group(1)}, expected {want['package']}")
        if version:
            data["versionCode"] = int(version.group(1))
            data["versionName"] = version.group(2)
            if not version.group(2).endswith(want["version_suffix"]):
                problems.append(f"versionName {version.group(2)} lacks suffix '{want['version_suffix']}'")
        abis = sorted(set(re.findall(r"native-code: '([^']+)'", badging)))
        if abis:
            data["abis"] = abis
    else:
        problems.append("aapt2 not found: package id not verified")

    apksigner = _host_tool("apksigner")
    if apksigner:
        verify = _run([apksigner, "verify", "--print-certs", str(apk)])
        data["signed"] = verify.returncode == 0
        if verify.returncode != 0:
            problems.append("apksigner verify failed")
        else:
            dn = re.search(r"certificate DN: (.+)", verify.stdout)
            if dn:
                data["cert_dn"] = dn.group(1).strip()
    else:
        problems.append("apksigner not found: signature not verified")

    dex = _dex_blob(apk)
    missing = []
    for commit, literal, only in FEATURE_MARKERS:
        if only and name not in only:
            continue
        if literal.encode() not in dex:
            missing.append(f"{commit}:{literal}")
    data["features_verified"] = len(FEATURE_MARKERS) - len(missing)
    if missing:
        problems.append("missing feature markers: " + ", ".join(missing))

    return Row(f"apk:{name}", "FAIL" if problems else "PASS",
               "; ".join(problems) if problems else f"{data['bytes']:,} bytes, sha256 and markers match",
               data)


def check_parity() -> Row:
    """Two APKs, two packages: they must install side by side."""
    tv = REPO / "artifacts" / "nova-tv" / "nova-tv-release.apk"
    mobile = REPO / "artifacts" / "nova-mobile" / "nova-mobile-release.apk"
    if not (tv.exists() and mobile.exists()):
        return Row("apk:parity", "FAIL", "one of the two APKs is missing")
    aapt2 = _host_tool("aapt2")
    if not aapt2:
        return Row("apk:parity", "SKIP", "aapt2 not found")
    ids = []
    for apk in (tv, mobile):
        out = _run([aapt2, "dump", "badging", str(apk)]).stdout
        ids.append(re.search(r"package: name='([^']+)'", out).group(1))
    ok = len(set(ids)) == 2
    return Row("apk:parity", "PASS" if ok else "FAIL", f"packages: {ids}")


# -- repository checks -------------------------------------------------------

def check_branch() -> Row:
    """Are the session's commits on a remote branch, or only local?"""
    proc = _run(["git", "rev-list", f"{WINDOW[0]}~1..{WINDOW[1]}"])
    if proc.returncode != 0:
        return Row("branch", "SKIP", f"cannot read commit range {WINDOW}")
    commits = proc.stdout.split()
    local_only = []
    for commit in commits:
        containing = _run(["git", "branch", "-r", "--contains", commit]).stdout.split()
        if not containing:
            local_only.append(commit[:7])
    origin_tv = _run(["git", "ls-tree", "-r", "--name-only", "origin/tv", "--", "nova-tv"]).stdout.strip()
    detail = (f"{len(commits)} commit(s) in window, {len(local_only)} not on any remote branch"
              f"; origin/tv has {'nova-tv source' if origin_tv else 'no nova-tv source'}")
    status = "WARN" if local_only else "PASS"
    return Row("branch", status, detail, {"local_only": local_only})


def check_diagnostics() -> Row:
    """The last CI run recorded in docs/build-diagnostics.md: did it pass?"""
    doc = NOVA / "docs" / "build-diagnostics.md"
    if not doc.exists():
        return Row("ci-diagnostics", "SKIP", "no build-diagnostics.md")
    text = doc.read_text(errors="replace")
    run = re.search(r"Run:\s*(\d+)", text)
    failed = "BUILD FAILED" in text
    reason = ""
    if failed:
        codes = sorted(set(re.findall(r"status code (\d+)", text)))
        reason = f", HTTP {'/'.join(codes)} from the dependency mirror" if codes else ""
    return Row("ci-diagnostics", "WARN" if failed else "PASS",
               f"run {run.group(1) if run else '?'} {'failed' if failed else 'passed'}{reason}")


CHECKS = [
    check_appaudit,
    check_dead_buttons,
    check_structure,
    check_python_suite,
    lambda: check_apk("nova-tv"),
    lambda: check_apk("nova-mobile"),
    check_parity,
    check_diagnostics,
    check_branch,
]


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Debug session over nova-tv: app, tooling, shipped APKs.")
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    args = parser.parse_args(argv)

    rows: List[Row] = []
    for check in CHECKS:
        try:
            rows.append(check())
        except Exception as exc:                                # pragma: no cover - defensive
            rows.append(Row(getattr(check, "__name__", "check"), "FAIL", f"crashed: {exc!r}"))

    if args.json:
        print(json.dumps([r.as_dict() for r in rows], indent=2))
    else:
        width = max(len(r.check) for r in rows)
        for row in rows:
            print(f"{row.status:<5} {row.check:<{width}}  {row.detail}")

    return 1 if any(r.status == "FAIL" for r in rows) else 0


if __name__ == "__main__":
    raise SystemExit(main())
