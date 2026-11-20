#!/usr/bin/env python3
"""One command to debug Nova: the mobile APK, the TV APK, and the Kotlin behind them.

This is the entry point. It does four things and refuses to pretend about any of
them:

  1. **Finds** the nova sources and the APKs. Sources are looked for in this
     repository and in a checkout passed with ``--nova``; APKs in ``artifacts/``
     and anywhere passed with ``--apk``. What was found, and where, is printed.
  2. **Audits** the Kotlin statically: unresolved identifiers, missing type
     members, unbalanced braces, dead buttons, disabled controls, failures
     collapsed into empty results, main-thread I/O, non-atomic saves, duplicate
     declarations, and doc claims that disagree with the code.
  3. **Audits** both APKs: identity (package, application, activity), the classes
     the manifest names (a launch-fatal check -- a missing application class is a
     crash on every start), native libraries, debuggability, whether the finder
     and the pool survived R8, and which hosts the harvester actually asks.
  4. **Writes a report** and exits non-zero if there is an error.

What it cannot do here, and says so instead of passing: there is no JDK, no
Android SDK and no device in this environment, so it does not compile, does not
run the app, and does not capture a real crash. A runtime crash has to come from
a logcat trace, which ``--trace`` can retrace when a mapping file exists.

Usage::

    python3 tools/debug_nova.py                     # everything it can find
    python3 tools/debug_nova.py --nova /path/nova-tv --apk tv.apk --apk mobile.apk
    python3 tools/debug_nova.py --trace crash.txt --mapping mapping.txt
    python3 tools/debug_nova.py --verbose

Exit codes: 0 no errors, 1 errors found, 2 the run itself failed.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLI = HERE / "nova_debug" / "cli.py"
REPO = HERE.parent


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Debug Nova: TV and mobile APKs plus the sources behind them")
    ap.add_argument("--nova", help="path to a nova-tv checkout (contains android/ and tools/)")
    ap.add_argument("--apk", action="append", default=[], help="APK to audit (repeatable)")
    ap.add_argument("--root", help="repository root to scan (default: this repository)")
    ap.add_argument("--out", default="artifacts/nova-debug", help="where to write the report")
    ap.add_argument("--trace", help="a crash logcat/stack trace to retrace")
    ap.add_argument("--mapping", help="mapping.txt for --trace")
    ap.add_argument("--verbose", action="store_true", help="print every finding as it is found")
    args = ap.parse_args(argv)

    if not CLI.is_file():
        print(f"error: {CLI} is missing; the debugger package is incomplete", file=sys.stderr)
        return 2

    command = [sys.executable, str(CLI)]
    command += ["--root", str(Path(args.root).expanduser() if args.root else REPO)]
    if args.nova:
        command += ["--nova", str(Path(args.nova).expanduser())]
    for apk in args.apk:
        command += ["--apk", str(Path(apk).expanduser())]
    command += ["--out", args.out]
    if args.trace:
        command += ["--trace", args.trace]
    if args.mapping:
        command += ["--mapping", args.mapping]
    if args.verbose:
        command += ["--verbose"]

    print("nova debug")
    print("  " + " ".join(command[1:]))
    print()
    completed = subprocess.run(command)
    if completed.returncode not in (0, 1):
        print(f"error: the audit itself failed (exit {completed.returncode})", file=sys.stderr)
        return 2
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
