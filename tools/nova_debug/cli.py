"""Command line for the Nova debugger.

Usage::

    python3 tools/nova_debug/cli.py                     # audit the repo as it is
    python3 tools/nova_debug/cli.py --nova /path/to/nova-tv
    python3 tools/nova_debug/cli.py --apk a.apk --apk b.apk
    python3 tools/nova_debug/cli.py --trace crash.txt --mapping mapping.txt

Exit codes: 0 = no errors found, 1 = errors found, 2 = the run itself failed.
Nothing is reported as passing that was not measured; checks that cannot run
here are listed as skipped with the reason.
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Sequence

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from nova_debug import apk_audit, dex, retrace, source_audit
    from nova_debug.findings import ERROR, INFO, SKIP, WARN, Report, ToolEnv
else:  # pragma: no cover - imported as a package
    from . import apk_audit, dex, retrace, source_audit
    from .findings import ERROR, INFO, SKIP, WARN, Report, ToolEnv

DEFAULT_APKS = (
    "artifacts/nova-tv/nova-tv-release.apk",
    "artifacts/nova-mobile/nova-mobile-release.apk",
)


def find_nova_root(root: Path) -> Optional[Path]:
    """Locate the Nova sources: the repo, or a checkout beside it."""
    candidates = [
        root / "nova-tv",
        root.parent / "nova-tv",
        root / "imports" / "nova-tv",
    ]
    for c in candidates:
        if (c / "android" / "app" / "src").is_dir():
            return c
    return None


def environment(root: Path, nova_root: Optional[Path]) -> ToolEnv:
    java = shutil.which("java")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    return ToolEnv(
        root=root,
        nova_root=nova_root,
        java=java,
        android_sdk=sdk if sdk and Path(sdk).is_dir() else None,
        python=f"{platform.python_version()} ({platform.system()})",
    )


def project_class_list(nova_root: Path) -> List[tuple]:
    """Every project class as (fully-qualified name, source file), for drift checks."""
    src = nova_root / "android" / "app" / "src"
    out: List[tuple] = []
    for path in sorted(src.rglob("*.kt")):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        package = ""
        for line in text.splitlines()[:40]:
            line = line.strip()
            if line.startswith("package "):
                package = line[len("package "):].strip()
                break
        for m in __import__("re").finditer(r"^\s*(?:[\w@]+\s+)*(?:data\s+|sealed\s+|enum\s+|abstract\s+|open\s+)?(?:class|object|interface)\s+([A-Z]\w*)", text, __import__("re").M):
            name = f"{package}.{m.group(1)}" if package else m.group(1)
            out.append((name, str(path.relative_to(nova_root))))
    return out


def run(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    nova_root = Path(args.nova).resolve() if args.nova else find_nova_root(root)
    env = environment(root, nova_root)
    report = Report("Nova debug report")
    report.meta.update(env.as_meta())
    report.meta["generated with"] = "tools/nova_debug (static; no JVM needed)"

    # -- sources ------------------------------------------------------------
    project = None
    if nova_root:
        src_root = nova_root / "android" / "app" / "src"
        proguard = nova_root / "android" / "app" / "proguard-rules.pro"
        project = source_audit.load_project(src_root, proguard)
        apks_for_symbols = [root / p for p in DEFAULT_APKS if (root / p).is_file()]
        apks_for_symbols += [Path(a).resolve() for a in (args.apk or [])]
        symbols = set()
        for apk_path in apks_for_symbols:
            try:
                pkg = dex.read_apk(apk_path)
            except Exception:
                continue
            for c in pkg.all_classes:
                internal = c[1:-1] if c.startswith("[") is False and c.startswith("L") else c
                symbols.add(internal.rsplit("/", 1)[-1].split("$")[0])
            for d in pkg.dex:
                for _c, name, _p in d.methods:
                    symbols.add(name)
        if symbols:
            project.library_symbols = symbols
        docs = nova_root / "docs"
        report.extend(source_audit.audit(project, docs if docs.is_dir() else None))
        report.meta["kotlin files scanned"] = len(project.files)
        report.meta["bundled apk symbols"] = len(project.library_symbols)
        class_list = project_class_list(nova_root)
    else:
        report.skip("SRC_NOT_FOUND",
                    "nova-tv/ is not on this branch; run with --nova /path/to/nova-tv "
                    "(any nova-* branch has it) to audit the sources")
        class_list = []

    # -- artifacts ----------------------------------------------------------
    apk_paths = [Path(a).resolve() for a in (args.apk or [])]
    if not apk_paths:
        apk_paths = [root / p for p in DEFAULT_APKS if (root / p).is_file()]
    facts = []
    for path in apk_paths:
        try:
            facts.append(apk_audit.PackageFacts(path=path, apk=dex.read_apk(path)))
        except Exception as error:
            report.skip("APK_UNREADABLE", f"{path}: {error}")
    if facts:
        report.extend(apk_audit.audit(facts, class_list))
        for f in facts:
            # A checksum file that is present must match; a mismatch is an error.
            sha_file = f.path.with_suffix(f.path.suffix + ".sha256")
            if sha_file.is_file():
                expected = sha_file.read_text().split()[0].strip()
                if expected != f.apk.sha256:
                    report.add(__import__("nova_debug.findings", fromlist=["Finding"]).Finding(
                        id="APK_CHECKSUM_MISMATCH", severity=ERROR, file=f"apk:{f.path.name}", line=0,
                        message=f"checksum file says {expected[:16]}…, the APK is {f.apk.sha256[:16]}…",
                        rule="artifact integrity",
                        fix="Rebuild or re-export the checksum; the published hash is wrong.",
                    ))
    else:
        report.skip("APK_NOT_FOUND", "no Nova APK found to inspect")

    # -- things this environment cannot check -------------------------------
    if not env.java:
        report.skip("NO_JVM", "no JDK: Kotlin compilation, unit tests and R8 retracing were not run")
    if not env.android_sdk:
        report.skip("NO_SDK", "no Android SDK: aapt2 badging, apksigner and zipalign were not run")

    # -- output -------------------------------------------------------------
    out = Path(args.out or root / "artifacts" / "nova-debug")
    report.to_json(out / "nova-debug-report.json")
    report.to_markdown(out / "nova-debug-report.md")
    print(f"nova debug: {report.summary()}")
    print(f"  report: {out / 'nova-debug-report.md'}")
    for f in report.errors[:20] if hasattr(report, "errors") else []:
        print(f"  ERROR {f}")
    if args.verbose:
        for f in report.sorted():
            if f.severity != INFO:
                print(f"  {f.severity.upper():7s} {f}")
    return 1 if report.errors else 0


def _parse(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Debug the Nova TV / Nova Mobile app and its APKs")
    p.add_argument("--root", default=str(Path(__file__).resolve().parents[2]), help="repository root")
    p.add_argument("--nova", help="path to a nova-tv checkout (defaults to <root>/nova-tv)")
    p.add_argument("--apk", action="append", help="APK to inspect (repeatable)")
    p.add_argument("--out", help="output directory (default artifacts/nova-debug)")
    p.add_argument("--verbose", action="store_true", help="print every finding")
    return p.parse_args(argv)


def retrace_command(argv: Sequence[str]) -> int:
    p = argparse.ArgumentParser(description="Retrace an obfuscated Nova stack trace")
    p.add_argument("--trace", required=True, help="file containing the stack trace")
    p.add_argument("--mapping", help="R8 mapping.txt for the build that crashed")
    p.add_argument("--fetch-mapping", action="store_true", help="ask GitHub Actions for the mapping artefact")
    p.add_argument("--repo", default="regularshowrigby8-create/SAVAGE-TECH-CORNER-")
    p.add_argument("--artifact", default="nova-mapping")
    args = p.parse_args(argv)
    trace = Path(args.trace).read_text(encoding="utf-8", errors="ignore")
    mapping_path = Path(args.mapping) if args.mapping else None
    if mapping_path is None and args.fetch_mapping:
        status = retrace.fetch_mapping(args.repo, args.artifact, Path("artifacts/mapping"))
        print(status)
        found = list(Path("artifacts/mapping").rglob("mapping.txt"))
        mapping_path = found[0] if found else None
    if mapping_path is None or not mapping_path.is_file():
        print("No mapping.txt supplied, so the frames cannot be resolved honestly.\n"
              "  - the release APKs are R8-minified and carry no mapping inside them;\n"
              "  - supply --mapping, or --fetch-mapping if CI published one.\n"
              "Nothing was guessed. Here is the trace unmodified:\n")
        print(trace)
        return 2
    print(retrace.retrace(trace, retrace.Mapping.load(mapping_path)))
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "retrace":
        return retrace_command(argv[1:])
    return run(_parse(argv))


if __name__ == "__main__":
    raise SystemExit(main())
