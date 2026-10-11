"""Explicit, isolated dev-tool provisioning; normal factory runs never install tools."""

import argparse
import concurrent.futures
import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from collections.abc import Callable
from functools import partial
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HOME = ROOT / "build/factory"


def uv_binary():
    """Verify the pinned wheel before extracting only its executable."""
    spec = json.loads((ROOT / "tools/factory/bootstrap.lock.json").read_text())
    if sys.platform != "linux" or platform.machine() not in {"x86_64", "AMD64"}:
        raise ValueError(
            "Bootstrap supports Linux x86_64 only; use a compatible hosted runner"
        )
    target = HOME / "bin/uv"
    wheel = HOME / "downloads" / spec["filename"]
    wheel.parent.mkdir(parents=True, exist_ok=True)
    if not wheel.exists():
        if not spec["url"].startswith("https://files.pythonhosted.org/"):
            raise ValueError("Unexpected bootstrap download host")
        with urllib.request.urlopen(spec["url"], timeout=60) as response:
            wheel.write_bytes(response.read(64 * 1024 * 1024))
    data = wheel.read_bytes()
    if hashlib.sha256(data).hexdigest() != spec["sha256"]:
        raise ValueError("uv wheel checksum mismatch; refusing execution")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members = [name for name in archive.namelist() if name.endswith("/uv")]
        if len(members) != 1:
            raise ValueError("Ambiguous uv binary in wheel")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.read(members[0]))
        target.chmod(0o755)
    return target


def command(argv, log, env):
    """Run provisioners with no shell, no credentials in argv, and finite timeout."""
    with log.open("ab") as output:
        result = subprocess.run(
            [str(a) for a in argv],
            cwd=ROOT,
            env=env,
            stdout=output,
            stderr=subprocess.STDOUT,
            timeout=900,
            check=False,
        )
    if result.returncode:
        raise ValueError(
            f"Provisioning command failed ({result.returncode}); inspect {log.relative_to(ROOT)}"
        )


def provision_python(name, uv, lock, env):
    """Resolve only on explicit --lock; otherwise use hash-locked packages."""
    log = HOME / "install-logs" / (name + ".log")
    lockfile = ROOT / "tools/factory/locks" / (name + ".txt")
    if lock:
        command(
            [
                uv,
                "pip",
                "compile",
                "--python-version",
                "3.11",
                "--generate-hashes",
                "tools/factory/requirements/" + name + ".in",
                "--output-file",
                lockfile,
            ],
            log,
            env,
        )
    if not lockfile.exists():
        raise ValueError(
            f"No reviewed lock for {name}; explicitly resolve with --lock first"
        )
    python = HOME / "envs" / name / "bin/python"
    if not python.exists():
        command(
            [uv, "venv", "--python", sys.executable, python.parent.parent], log, env
        )
    command(
        [uv, "pip", "sync", "--python", python, "--require-hashes", lockfile], log, env
    )
    return {
        "id": name,
        "status": "installed",
        "lock_sha256": hashlib.sha256(lockfile.read_bytes()).hexdigest(),
    }


def provision_node(lock, env):
    """Use npm integrity lock and disable lifecycle scripts."""
    log = HOME / "install-logs/node.log"
    source = ROOT / "tools/factory/npm"
    if lock:
        command(
            [
                "npm",
                "install",
                "--prefix",
                source,
                "--package-lock-only",
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
            ],
            log,
            env,
        )
    if not (source / "package-lock.json").exists():
        raise ValueError("No reviewed npm lock")
    target = HOME / "node"
    target.mkdir(parents=True, exist_ok=True)
    for name in ("package.json", "package-lock.json"):
        shutil.copyfile(source / name, target / name)
    command(
        [
            "npm",
            "ci",
            "--prefix",
            target,
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ],
        log,
        env,
    )
    # Knip resolves the real workspace's declared Jest dependency via node_modules;
    # NODE_PATH alone is insufficient. Reuse the locked cache, never replace an
    # existing developer installation or alter js-tests/package.json.
    workspace_modules = ROOT / "js-tests/node_modules"
    if not workspace_modules.exists() and not workspace_modules.is_symlink():
        workspace_modules.symlink_to(target / "node_modules", target_is_directory=True)
    return {"id": "node", "status": "installed"}


def provision_browser(env):
    """Explicitly install the Chromium revision selected by the locked Playwright."""
    cli = HOME / "node/node_modules/playwright/cli.js"
    if not cli.is_file():
        raise ValueError(
            "Install the locked node ecosystem before browser provisioning"
        )
    browser_env = dict(env, PLAYWRIGHT_BROWSERS_PATH=str(HOME / "browsers"))
    command(
        ["node", cli, "install", "chromium"],
        HOME / "install-logs/browser.log",
        browser_env,
    )
    package = json.loads((cli.parent / "package.json").read_text())
    (HOME / "browsers/installed.json").write_text(
        json.dumps({"playwright": package["version"]}) + "\n"
    )
    return {"id": "browser", "status": "installed", "playwright": package["version"]}


def provision_binary(tool, env):
    """Install only a digest-pinned upstream Linux asset, without archive extraction."""
    del env  # Public HTTPS asset fetch needs no provider or signing credentials.
    spec = tool["install"]
    if not re.fullmatch(r"[a-z0-9-]+", tool["id"]):
        raise ValueError("Unsafe binary identity")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", spec["asset"]) or spec["asset"].startswith(
        "."
    ):
        raise ValueError("Unsafe asset filename")
    if spec["archive"] not in {"tar.gz", "raw"}:
        raise ValueError("Unsupported binary archive format")
    if sys.platform != "linux" or platform.machine() not in {"x86_64", "AMD64"}:
        raise ValueError("Binary lock supports Linux x86_64 only")
    if not spec["url"].startswith("https://github.com/"):
        raise ValueError("Unexpected upstream binary host")
    target = HOME / "downloads" / spec["asset"]
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        with urllib.request.urlopen(spec["url"], timeout=90) as response:
            target.write_bytes(response.read(256 * 1024 * 1024))
    data = target.read_bytes()
    if hashlib.sha256(data).hexdigest() != spec["sha256"]:
        raise ValueError("Upstream asset digest mismatch; refusing execution")
    if spec["archive"] == "tar.gz":
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            members = [
                item
                for item in archive.getmembers()
                if item.isfile() and Path(item.name).name == tool["id"]
            ]
            if len(members) != 1:
                raise ValueError("Ambiguous binary in upstream archive")
            stream = archive.extractfile(members[0])
            if stream is None:
                raise ValueError("Upstream binary unreadable")
            data = stream.read(256 * 1024 * 1024)
    destination = HOME / "go/bin" / tool["id"]
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    destination.chmod(0o755)
    return {"id": tool["id"], "status": "installed", "asset_sha256": spec["sha256"]}


def provision_plan(args, parser):
    """Build explicit install jobs without exposing provider/signing credentials."""
    public_keys = {"PATH", "HOME", "LANG", "LC_ALL", "TMPDIR"}
    safe_env = {
        key: value
        for key, value in os.environ.items()
        if key
        in public_keys
        | {
            "SSL_CERT_FILE",
            "REQUESTS_CA_BUNDLE",
            "HTTPS_PROXY",
            "HTTP_PROXY",
            "NO_PROXY",
        }
    }
    env = dict(
        safe_env,
        UV_CACHE_DIR=str(HOME / "cache/uv"),
        UV_PYTHON_DOWNLOADS="never",
        GOBIN=str(HOME / "go/bin"),
        GOMODCACHE=str(HOME / "cache/go"),
        GOMAXPROCS="2",
    )
    jobs: list[tuple[str, Callable[[], dict]]] = []
    if args.ecosystem in {"python", "all"}:
        uv = uv_binary()
        names = sorted(
            path.stem for path in (ROOT / "tools/factory/requirements").glob("*.in")
        )
        if args.only:
            requested = set(args.only.split(","))
            if not requested.issubset(names):
                parser.error("Unknown Python environment")
            names = [name for name in names if name in requested]
        jobs.extend(
            (name, partial(provision_python, name, uv, args.lock, env))
            for name in names
        )
    if args.ecosystem == "browser":
        jobs.append(("browser", partial(provision_browser, env)))
    if args.ecosystem in {"node", "all"}:
        jobs.append(("node", partial(provision_node, args.lock, env)))
    if args.ecosystem in {"binary", "all"}:
        for tool in json.loads((ROOT / "tools/factory/tools.json").read_text())[
            "tools"
        ]:
            if tool["install"]["type"] == "github-binary":
                jobs.append((tool["id"], partial(provision_binary, tool, env)))
    return jobs


def main():
    """Provision only requested ecosystems and persist all blocked attempts."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--lock",
        action="store_true",
        help="Explicitly create/update reviewed dev-only locks",
    )
    parser.add_argument(
        "--ecosystem",
        choices=["python", "node", "binary", "browser", "all"],
        default="all",
    )
    parser.add_argument(
        "--only", help="Comma-separated Python environment IDs, for targeted retries"
    )
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=3)
    args = parser.parse_args()
    (HOME / "install-logs").mkdir(parents=True, exist_ok=True)
    jobs = provision_plan(args, parser)
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(action): name for name, action in jobs}
        for future in concurrent.futures.as_completed(futures):
            name = futures[future]
            try:
                result = future.result()
            except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                result = {"id": name, "status": "blocked", "reason": str(error)}
            results.append(result)
            print(json.dumps(result), flush=True)
    (HOME / ("install-" + args.ecosystem + ".json")).write_text(
        json.dumps(results, indent=2) + "\n"
    )
    return 1 if any(item["status"] != "installed" for item in results) else 0


if __name__ == "__main__":
    sys.exit(main())
