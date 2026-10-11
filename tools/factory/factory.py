"""Plan and execute bounded development checks, never autonomous source repairs."""

import argparse
import atexit
import concurrent.futures
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from tools.factory.factory_map import write_overview
from tools.factory.safety import safe_relative, validate_command, validate_tools

ROOT = Path(__file__).resolve().parents[2]
HOME = ROOT / "build/factory"
MAX_LOG_BYTES = 8 * 1024 * 1024
ACTIVE_PROCESSES: set[subprocess.Popen] = set()
CANCELLED = threading.Event()
PROCESS_LOCK = threading.Lock()


def load_tools() -> list[dict]:
    """Load the reviewable command registry; it is not evidence of availability."""
    return validate_tools(json.loads((ROOT / "tools/factory/tools.json").read_text()))


def source_snapshot(root: Path) -> str:
    """Hash tracked/nonignored source, preserving symlinks without following them."""
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root,
    )
    result = hashlib.sha256()
    for name in sorted(set(paths.decode().split("\0")) - {""}):
        path = root / name
        result.update(name.encode())
        if path.is_symlink():
            result.update(os.readlink(path).encode())
        elif path.is_file():
            result.update(hashlib.sha256(path.read_bytes()).digest())
        else:
            result.update(b"missing")
    return result.hexdigest()


def sdk_binary(name: str) -> str:
    """Find SDK executables; never silently substitute a different tool."""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise FileNotFoundError("Android SDK is not configured")
    sdk = Path(home)
    patterns = {
        "apksigner": "build-tools/*/apksigner",
        "aapt2": "build-tools/*/aapt2",
        "apkanalyzer": "cmdline-tools/*/bin/apkanalyzer",
        "adb": "platform-tools/adb",
    }
    candidates = sorted(sdk.glob(patterns[name]))
    if not candidates:
        raise FileNotFoundError("SDK executable missing: " + name)
    return str(candidates[-1])


def expand_command(tool: dict, out: Path, apk: str | None) -> list[str]:
    """Expand only typed factory placeholders, with no shell interpolation."""
    for prerequisite in tool["requires"]:
        if prerequisite == "browser":
            if not (HOME / "browsers/installed.json").is_file():
                raise FileNotFoundError(
                    "Chromium missing: install.py --ecosystem browser"
                )
        elif prerequisite == "android-sdk":
            if not (
                os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            ):
                raise FileNotFoundError("Android SDK is not configured")
        elif prerequisite == "apk":
            if apk is None:
                raise FileNotFoundError(
                    "Supply an existing repository-relative APK; factory never builds one"
                )
            path = safe_relative(ROOT, apk)
            if path.suffix != ".apk" or not path.is_file():
                raise FileNotFoundError("APK input missing or not an APK")
        elif not shutil.which(prerequisite):
            raise FileNotFoundError("Required executable missing: " + prerequisite)

    def substitute(match: re.Match) -> str:
        """Resolve one reviewed placeholder into a concrete argument fragment."""
        key = match.group(1)
        if key.startswith("pybin:"):
            return str(HOME / "envs" / key.split(":")[1] / "bin")
        if key.startswith("sdk:"):
            return sdk_binary(key.split(":")[1])
        values = {
            "out": str(out),
            "root": str(ROOT),
            "node": str(HOME / "node"),
            "nodebin": str(HOME / "node/node_modules/.bin"),
            "gobin": str(HOME / "go/bin"),
        }
        if key == "apk" and apk is not None:
            return str(safe_relative(ROOT, apk))
        if key not in values:
            raise ValueError("Unknown or missing placeholder: " + key)
        return values[key]

    argv = [re.sub(r"\{([^}]+)\}", substitute, item) for item in tool["command"]]
    validate_command(argv)
    executable = ROOT / argv[0] if argv[0].startswith("./") else Path(argv[0])
    if not (executable.is_file() if "/" in argv[0] else shutil.which(argv[0])):
        raise FileNotFoundError("Tool is not provisioned: " + tool["id"])
    return argv


def tool_environment(tool: dict, out: Path) -> dict[str, str]:
    """Do not give ordinary scanners provider/signing/GitHub credentials."""
    names = {
        "PATH",
        "HOME",
        "LANG",
        "LC_ALL",
        "TMPDIR",
        "JAVA_HOME",
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
    }
    if tool["id"] == "gh":
        names.update({"GH_TOKEN", "GITHUB_TOKEN"})
    env = {name: value for name, value in os.environ.items() if name in names}
    env.update(
        PYTHONPATH=str(ROOT) + os.pathsep + str(ROOT / "backend"),
        NODE_PATH=str(HOME / "node/node_modules"),
        HYPOTHESIS_STORAGE_DIRECTORY=str(out / "hypothesis"),
        PYTHONDONTWRITEBYTECODE="1",
        NO_COLOR="1",
        SEMGREP_SEND_METRICS="off",
        CHECKOV_SKIP_PACKAGE_DOWNLOAD="true",
        SYFT_CHECK_FOR_APP_UPDATE="false",
        RUFF_CACHE_DIR=str(out / "ruff-cache"),
        PLAYWRIGHT_BROWSERS_PATH=str(HOME / "browsers"),
    )
    env["PATH"] = (
        str(HOME / "node/node_modules/.bin") + os.pathsep + env.get("PATH", "")
    )
    return env


def prepare(tool: dict, out: Path) -> Path:
    """Prepare only disposable validation/mutation inputs under ignored output."""
    if tool.get("setup") == "browser":
        write_overview(out / "factory.html")
    if tool.get("setup") == "compose":
        (out / "compose.env").write_text(
            "\n".join(
                name + "=" + secrets.token_hex(24)
                for name in (
                    "POSTGRES_PASSWORD",
                    "SEARCHHH_ACCESS_TOKEN",
                    "SEARXNG_SECRET",
                )
            )
            + "\n"
        )
        (out / "compose.env").chmod(0o600)
    if tool.get("setup") == "mutation":
        scratch = out / "mutation"
        (scratch / "src").mkdir(parents=True)
        (scratch / "tests").mkdir()
        shutil.copyfile(ROOT / "tools/factory/safety.py", scratch / "src/safety.py")
        shutil.copyfile(
            ROOT / "tools/factory/mutation/test_safety.py",
            scratch / "tests/test_safety.py",
        )
        (scratch / "pyproject.toml").write_text(
            '[tool.pytest.ini_options]\npythonpath=["src"]\n'
            '[tool.mutmut]\nsource_paths=["src/safety.py"]\n'
            'pytest_add_cli_args_test_selection=["tests/"]\n'
        )
        return scratch
    return ROOT


def terminate(process: subprocess.Popen) -> None:
    """Stop the whole scanner process group on a bound violation."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        process.wait(
            timeout=3
        )  # Group is gone; reap the parent instead of ignoring it.
        return
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            process.wait(timeout=3)  # Group disappeared; still reap its parent.
        else:
            process.wait(timeout=3)


def stop_children() -> None:
    """Forward factory shutdown to all active process groups."""
    with PROCESS_LOCK:
        children = list(ACTIVE_PROCESSES)
    for process in children:
        terminate(process)


def shutdown(signum, frame) -> None:
    """Cancel scanners rather than letting them survive their orchestrator."""
    del frame
    CANCELLED.set()
    stop_children()
    raise SystemExit(128 + signum)


def supervise(
    process: subprocess.Popen, logs: list[Path], timeout: float
) -> str | None:
    """Enforce bounds while the process context owns wait/descriptor cleanup."""
    start = time.monotonic()
    reason = None
    with PROCESS_LOCK:
        ACTIVE_PROCESSES.add(process)
    try:
        if CANCELLED.is_set():
            terminate(process)
            return "cancelled"
        while process.poll() is None:
            if time.monotonic() - start > timeout:
                reason = "timeout"
                terminate(process)
            elif sum(path.stat().st_size for path in logs) > MAX_LOG_BYTES:
                reason = "output-limit"
                terminate(process)
            else:
                try:
                    process.wait(timeout=0.1)
                except subprocess.TimeoutExpired:
                    continue
    finally:
        terminate(process)  # Also stop descendants after a normal parent exit.
        with PROCESS_LOCK:
            ACTIVE_PROCESSES.discard(process)
    remaining = MAX_LOG_BYTES
    for path in logs:
        size = path.stat().st_size
        if size > remaining:
            reason = "output-limit"
            with path.open("r+b") as stream:
                stream.truncate(remaining)
        remaining -= min(size, remaining)
    return reason


def execute(
    argv: list[str], cwd: Path, env: dict[str, str], log: Path, timeout: float
) -> tuple[int, str | None]:
    """Keep structured stdout separate from stderr, bounding their combined size."""
    if CANCELLED.is_set():
        raise ValueError("Factory cancelled; refusing to start another scanner")
    error_log = log.with_name("stderr.log")
    with log.open("wb") as output, error_log.open("wb") as errors:
        with subprocess.Popen(
            argv,
            cwd=cwd,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=errors,
            start_new_session=True,
        ) as process:
            reason = supervise(process, [log, error_log], timeout)
            return process.returncode, reason


def classify(kind: str, code: int, log: Path, bound: str | None) -> str:
    """Separate report production, clean checks, findings and missing evidence."""
    if bound:
        return "FAIL"
    if kind == "search" and code in {0, 1}:
        return "REPORTED"
    if code != 0:
        return "FAIL"
    if kind == "secret-scan":
        data = json.loads(log.read_text())
        if "results" not in data or not isinstance(data["results"], dict):
            raise ValueError("Secret scanner output schema missing")
        return "FAIL" if any(data["results"].values()) else "PASS"
    return "REPORTED" if kind == "report" else "PASS"


def report_metadata(tool: dict, out: Path) -> list[dict]:
    """Validate fresh report files and retain hashes/counts, never raw secret values."""
    reports = []
    for spec in tool.get("reports", []):
        path = safe_relative(out, spec["path"])
        if not path.is_file() or not 0 < path.stat().st_size <= MAX_LOG_BYTES:
            raise ValueError("Missing, empty or oversized report: " + spec["path"])
        data = path.read_bytes()
        record = {
            "path": spec["path"],
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "format": spec["format"],
        }
        if spec["format"] == "json":
            parsed = json.loads(data)
            if not isinstance(parsed, (dict, list)):
                raise ValueError("Expected structured JSON report: " + spec["path"])
            if "count_field" in spec:
                items = parsed[spec["count_field"]] if spec["count_field"] else parsed
                if not isinstance(items, (list, dict)):
                    raise ValueError("Expected report record collection")
                record["records"] = len(items)
        reports.append(record)
    return reports


def run_tool(tool: dict, directory: Path, apk: str | None) -> dict:
    """Execute one declared integration and retain truthful per-tool evidence."""
    out = directory / tool["id"]
    out.mkdir(parents=True, exist_ok=True)
    before = source_snapshot(ROOT)
    result = {
        "id": tool["id"],
        "kind": tool["kind"],
        "purpose": tool["purpose"],
        "source_before": before,
        "approved_release": False,
        "install": tool["install"],
    }
    start = time.monotonic()
    try:
        argv = expand_command(tool, out, apk)
        cwd = prepare(tool, out)
        result["argv"] = argv
        log = out / "output.log"
        code, bound = execute(
            argv, cwd, tool_environment(tool, out), log, tool["timeout_seconds"]
        )
        result.update(
            returncode=code,
            bound=bound,
            log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
            stderr_sha256=hashlib.sha256(
                log.with_name("stderr.log").read_bytes()
            ).hexdigest(),
            log_bytes=log.stat().st_size,
            log=str(log.relative_to(ROOT)),
        )
        result["status"] = classify(tool["kind"], code, log, bound)
        result["reports"] = report_metadata(tool, out)
    except FileNotFoundError as error:
        result.update(status="BLOCKED", reason=str(error))
    except (
        ValueError,
        KeyError,
        TypeError,
        OSError,
        subprocess.SubprocessError,
    ) as error:
        result.update(status="FAIL", reason=str(error))
    finally:
        if (out / "compose.env").exists():
            (out / "compose.env").unlink()
    after = source_snapshot(ROOT)
    result.update(
        source_after=after, elapsed_seconds=round(time.monotonic() - start, 3)
    )
    if after != before:
        result.update(
            status="FAIL",
            reason="Source changed during read-only job; inspect diff, never auto-reset",
        )
    (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def suggest(inventory: dict) -> dict:
    """Group file/rule observations for review without claiming root-cause equivalence."""
    packets = {}
    routes = {
        "lint": ["android-lint", "ast-grep"],
        "detekt": ["detekt"],
        "ktlint": ["ktlint"],
        "unfinished": ["ripgrep", "semgrep"],
    }
    security = {
        "InsecureBaseConfiguration",
        "DataExtractionRules",
        "SetJavaScriptEnabled",
        "StaticFieldLeak",
    }
    for report in inventory["reports"]:
        for occurrence in report["occurrences"]:
            locations = occurrence.get("locations", [])
            path = locations[0].get("file", "unlocated") if locations else "unlocated"
            key = (report["tool"], occurrence["rule"], path)
            if key not in packets:
                ident = hashlib.sha256(json.dumps(key).encode()).hexdigest()[:16]
                packets[key] = {
                    "packet_id": ident,
                    "tool": key[0],
                    "rule": key[1],
                    "file": key[2],
                    "risk_review_first": key[1] in security,
                    "observations": [],
                    "suggested_tools": routes[key[0]],
                    "state": "REQUIRES_SEMANTIC_REVIEW",
                    "root_cause_confirmed": False,
                    "auto_fix_allowed": False,
                }
            packets[key]["observations"].append(
                {"report": report["id"], "id": occurrence["occurrence_id"]}
            )
    ordered = sorted(
        packets.values(),
        key=lambda p: (not p["risk_review_first"], p["tool"], p["file"], p["rule"]),
    )
    count = sum(len(p["observations"]) for p in ordered)
    return {
        "source": inventory["source"],
        "raw_observations_preserved": count,
        "not_unique_bug_count": True,
        "s00_closed": False,
        "packets": ordered,
    }


def triage() -> int:
    """Validate original evidence and persist provisional review packets."""
    path = ROOT / "docs/quality/recovered-6abf29b/occurrences.json"
    manifest = json.loads(path.with_name("manifest.json").read_text())
    if hashlib.sha256(path.read_bytes()).hexdigest() != manifest["dataset"]["sha256"]:
        raise ValueError("Recovered evidence hash mismatch")
    result = suggest(json.loads(path.read_text()))
    out = ROOT / "build/reports/factory/triage.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(
        f"{len(result['packets'])} provisional file/rule packets; "
        f"{result['raw_observations_preserved']} raw observations preserved. "
        f"S00 remains OPEN.\n{out}"
    )
    return 0


def select_tools(args, parser) -> list[dict]:
    """Reject ambiguous, unknown and empty execution requests."""
    tools = load_tools()
    if args.tools:
        wanted = set(args.tools.split(","))
        if not wanted.issubset({tool["id"] for tool in tools}):
            parser.error("Unknown tool ID")
        tools = [tool for tool in tools if tool["id"] in wanted]
    elif args.profile:
        tools = [tool for tool in tools if args.profile in tool["profiles"]]
    elif args.action == "run" and not args.all:
        parser.error("Choose an explicit profile, tool list or --all")
    if not tools:
        parser.error("Empty selection cannot pass")
    return tools


def doctor(tools: list[dict], apk: str | None) -> int:
    """Report availability without inventing execution evidence."""
    blocked = 0
    for tool in tools:
        try:
            expand_command(tool, ROOT / "build/reports/factory/doctor", apk)
            state = "AVAILABLE (not executed)"
        except (ValueError, OSError) as error:
            state = "BLOCKED: " + str(error)
            blocked += 1
        print(tool["id"] + ": " + state)
    return 2 if blocked else 0


def run_batch(tools: list[dict], args) -> int:
    """Collect independent checks while preserving every failed/blocked result."""
    stamp = (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        + "-"
        + secrets.token_hex(3)
    )
    directory = ROOT / "build/reports/factory" / stamp
    directory.mkdir(parents=True)
    print("Run evidence: " + str(directory), flush=True)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(run_tool, tool, directory, args.apk) for tool in tools]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            print(result["id"] + ": " + result["status"], flush=True)
    summary = {
        "source_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "release_approved": False,
        "counts": dict(Counter(result["status"] for result in results)),
        "results": results,
    }
    (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary["counts"]), flush=True)
    return (
        1 if any(result["status"] in {"FAIL", "BLOCKED"} for result in results) else 0
    )


def main() -> int:
    """Expose only declared diagnostic jobs; no apply or release operation exists."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["list", "doctor", "run", "triage"])
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--profile")
    selection.add_argument(
        "--tools", help="Explicit comma-separated IDs; no arbitrary commands"
    )
    selection.add_argument("--all", action="store_true")
    parser.add_argument(
        "--apk", help="Existing repository-relative APK for inspection only"
    )
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=1)
    args = parser.parse_args()
    if args.action == "triage":
        return triage()
    tools = select_tools(args, parser)
    if args.action == "list":
        for tool in tools:
            print(f"{tool['id']:22} {','.join(tool['profiles']):24} {tool['purpose']}")
        return 0
    if args.action == "doctor":
        return doctor(tools, args.apk)
    if any("android" in tool["profiles"] for tool in tools) and args.workers != 1:
        parser.error("Gradle jobs must run serially to avoid cache/resource contention")
    return run_batch(tools, args)


if __name__ == "__main__":
    atexit.register(stop_children)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    sys.exit(main())
