#!/usr/bin/env python3
"""Vendors a bounded, factory-defining slice of each catalogued repository.

The sealed component is meant to be dropped in or removed as one unit, so what it carries has to be
small enough to live in this repository and complete enough to describe how each factory works. This
script therefore takes a *slice* - the files that define a factory (workers, agents, stages, pipeline
entry points, prompts, roles, gates, declarative config, and the published licence) - and never the
whole checkout: the 72 catalogued repositories are about 4.2 GB in total, which is neither portable
nor reviewable.

Usage:
    python3 tools/factory/vendor_imported_factories.py [--registry ...] [--out ...] [--only id,id]

Every file it writes is recorded with its SHA-256 and its source commit, so the sealed component can
be verified without network access afterwards.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REGISTRY = REPO_ROOT / "tools" / "factory" / "imported_factories.json"
DEFAULT_OUT = REPO_ROOT / "sealed-factories" / "vendor"

#: Files that make a repository a *factory*. Ordered by how much they explain: definition files
#: first, then the declarative configuration, then orientation material.
SLICE_PATTERNS = [
    (10, re.compile(r"(^|/)(prompts?|prompt_templates?|instructions?)/", re.I)),
    (9, re.compile(r"(^|/)(workers?|agents?|roles?|personas?|crews?|swarms?)/", re.I)),
    (9, re.compile(r"(^|/)(stages?|steps?|phases?|pipeline|pipelines|orchestrat\w*|conductors?|"
                   r"coordinators?)/", re.I)),
    # Agent skill trees: `.agent/`, `skills/`, `capabilities/` are where several of the catalogued
    # factories keep their workers, and two repositories vendored nothing at all until these were
    # matched (AshishYesale7/LGFlutterStarterKit, whose 33 skills are the factory).
    (9, re.compile(r"(^|/)(\.agent|skills?|capabilities?|rules?|standards?)/", re.I)),
    (8, re.compile(r"(^|/)(factory|factory_definition|fabric|foundry)\.(py|js|ts|mjs|cjs|json|ya?ml|"
                   r"toml)$", re.I)),
    (8, re.compile(r"(^|/)(pipeline|orchestrator|conductor|coordinator|runner|main|cli|worker|agent)"
                   r"\.(py|js|ts|mjs|cjs)$", re.I)),
    (7, re.compile(r"(^|/)(factories|configs?|config|settings|manifests?|definitions?)/.*"
                   r"\.(json|ya?ml|toml|ini)$", re.I)),
    (7, re.compile(r"(^|/)(gate|gates|verify\w*|review\w*|critic\w*|check\w*)\.(py|js|ts|mjs|cjs)$",
                   re.I)),
    (7, re.compile(r"(^|/)(buildspecs?|ci|cd|delivery|deploy\w*)/.*\.(ya?ml|json|sh)$", re.I)),
    (6, re.compile(r"(^|/)(agents?|workers?|stages?|pipeline|factory|skill|workflow|rules?|gemini|"
                   r"claude)\.(md|ya?ml|json|toml)$", re.I)),
    (4, re.compile(r"^README\.(md|rst)$", re.I)),
]

#: Never part of a slice: dependency trees, build output, binaries, fixtures, lock files.
SKIP_PATTERNS = [
    re.compile(r"(^|/)(node_modules|\.git|dist|build|out|target|vendor|third_party|__pycache__|"
               r"\.venv|venv|site-packages)/", re.I),
    re.compile(r"(^|/)package-lock\.json$|(^|/)(poetry\.lock|yarn\.lock|pnpm-lock\.yaml)$", re.I),
    re.compile(r"\.(png|jpe?g|gif|webp|ico|svg|pdf|zip|tar|gz|whl|jar|jar|so|dylib|dll|exe|bin|mp4|"
               r"mp3|wav|parquet|csv|db|sqlite|ipynb|onnx|pt|safetensors|lock)$", re.I),
    re.compile(r"(^|/)(test|tests|spec|specs|fixtures?|__tests__)/", re.I),
    re.compile(r"(^|/)\.(github|vscode|idea|devcontainer)/", re.I),
]

LICENSE_NAMES = ("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENSE-MIT", "LICENSE-APACHE",
                 "COPYING", "COPYING.md", "NOTICE")

#: The repository's own authenticity auditor treats these as foreign identifiers, and vendored
#: third-party text must not smuggle one into the tree it scans.
FORBIDDEN_TOKENS = (b"barebones", b"lightning", b"acr.browser", b"anthonycr", b"client=lightning")

MAX_FILES = 25
MAX_TOTAL_BYTES = 150_000
MAX_FILE_BYTES = 60_000

PROVIDER_HINTS = {
    "openai": re.compile(r"openai|gpt-4|gpt-3|chat\.completions", re.I),
    "anthropic": re.compile(r"anthropic|claude", re.I),
    "ollama": re.compile(r"ollama", re.I),
    "local-llama": re.compile(r"llama[-_ ]?cpp|gguf|transformers", re.I),
    "google": re.compile(r"generativeai|gemini", re.I),
    "azure-openai": re.compile(r"azure.*openai|azure_openai", re.I),
    "bedrock": re.compile(r"bedrock", re.I),
    "mistral": re.compile(r"mistral", re.I),
    "groq": re.compile(r"groq", re.I),
    "vllm": re.compile(r"vllm", re.I),
}


def gh_json(path: str, attempts: int = 3):
    for attempt in range(attempts):
        done = subprocess.run(["gh", "api", path], capture_output=True, text=True)
        if done.returncode == 0:
            return json.loads(done.stdout)
        if "rate limit" in done.stderr.lower() or "secondary" in done.stderr.lower():
            time.sleep(20 * (attempt + 1))
            continue
        if attempt == attempts - 1:
            return None
        time.sleep(2)
    return None


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_skipped(path: str) -> bool:
    return any(pattern.search(path) for pattern in SKIP_PATTERNS)


def slice_score(path: str) -> int:
    for weight, pattern in SLICE_PATTERNS:
        if pattern.search(path):
            return weight
    return 0


def vendor_repo(entry: dict) -> dict:
    """Fetches the factory-defining slice of one repository. Never raises for a single repository."""
    full_name = entry["full_name"]
    result = {
        "id": entry["id"],
        "full_name": full_name,
        "url": entry["url"],
        "license": entry["license"],
        "license_file": None,
        "license_text_sha256": None,
        "import_class": entry["import_class"],
        "snapshot": entry["snapshot"],
        "commit": None,
        "vendored_files": [],
        "skipped": {"budget": False, "forbidden_token": [], "skipped_path": []},
        "providers": [],
        "entrypoints": [],
        "runtime": [],
        "notes": [],
    }

    repo = gh_json(f"repos/{full_name}")
    if not repo:
        result["notes"].append("repository metadata could not be read")
        return result
    branch = repo.get("default_branch") or "main"
    result["commit"] = None

    commit = gh_json(f"repos/{full_name}/commits/{branch}")
    if commit:
        result["commit"] = commit.get("sha")

    tree = gh_json(f"repos/{full_name}/git/trees/{branch}?recursive=1") or {}
    entries = [e for e in tree.get("tree", []) if e.get("type") == "blob"]
    if tree.get("truncated"):
        result["notes"].append("the repository tree was truncated by the API; the slice is partial")
    result["skipped"]["tree_entries"] = len(entries)

    # The licence is part of every slice: a vendored file without its licence is not usable.
    for name in LICENSE_NAMES:
        candidate = next((e for e in entries
                          if Path(e["path"]).name.upper() == name.upper()
                          and "/" not in e["path"]), None)
        if candidate:
            blob = gh_json(f'repos/{full_name}/contents/{candidate["path"]}')
            if blob and blob.get("content"):
                text = base64.b64decode(blob["content"])
                result["license_file"] = Path(candidate["path"]).name
                result["license_text_sha256"] = sha256_bytes(text)
                result["_license_bytes"] = base64.b64encode(text).decode()
            break

    scored = []
    for item in entries:
        path = item["path"]
        if is_skipped(path):
            continue
        score = slice_score(path)
        if score == 0:
            continue
        if item.get("size", 0) > MAX_FILE_BYTES:
            continue
        scored.append((score, -item.get("size", 0), path))
    scored.sort(reverse=True)

    total = 0
    for _score, _neg, path in scored:
        if len(result["vendored_files"]) >= MAX_FILES:
            result["skipped"]["budget"] = True
            break
        if total >= MAX_TOTAL_BYTES:
            result["skipped"]["budget"] = True
            break
        blob = gh_json(f"repos/{full_name}/contents/{path}")
        if not blob or blob.get("encoding") != "base64" or not blob.get("content"):
            continue
        data = base64.b64decode(blob["content"])
        if len(data) + total > MAX_TOTAL_BYTES:
            result["skipped"]["budget"] = True
            continue
        lowered = data.lower()
        if any(token in lowered for token in FORBIDDEN_TOKENS):
            result["skipped"]["forbidden_token"].append(path)
            continue
        text = ""
        if path.lower().endswith((".py", ".js", ".ts", ".mjs", ".cjs", ".json", ".yaml", ".yml",
                                  ".toml", ".ini", ".md", ".rst", ".txt", ".cfg")):
            text = data.decode("utf-8", "replace")
        total += len(data)
        result["vendored_files"].append({
            "path": path,
            "bytes": len(data),
            "sha256": sha256_bytes(data),
            "_content": base64.b64encode(data).decode(),
        })
        if text:
            for provider, pattern in PROVIDER_HINTS.items():
                if pattern.search(text) and provider not in result["providers"]:
                    result["providers"].append(provider)
        if re.search(r"(^|/)(main|cli|worker|agent|pipeline|orchestrator|factory)\.(py|js|ts|mjs|cjs)$",
                     path, re.I):
            result["entrypoints"].append(path)
        suffix = Path(path).suffix.lower()
        runtime = {".py": "python", ".js": "node", ".mjs": "node", ".cjs": "node", ".ts": "node",
                   ".sh": "shell"}.get(suffix)
        if runtime and runtime not in result["runtime"]:
            result["runtime"].append(runtime)

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Vendor factory-defining slices")
    parser.add_argument("--registry", default=str(DEFAULT_REGISTRY))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--only", default="")
    parser.add_argument("--refresh", action="store_true",
                        help="re-take every slice instead of skipping records that already exist; "
                             "use after the slice patterns change")
    args = parser.parse_args()

    registry = json.loads(Path(args.registry).read_text(encoding="utf-8"))
    if args.only:
        wanted = {part.strip() for part in args.only.split(",") if part.strip()}
        registry = [entry for entry in registry if entry["id"] in wanted]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    results = []
    for index, entry in enumerate(registry, start=1):
        target = out / f'{entry["id"]}.json'
        if target.is_file() and not args.refresh:
            stored = json.loads(target.read_text(encoding="utf-8"))
            if stored.get("vendored_files"):
                print(f"[{index}/{len(registry)}] {entry['id']}: already vendored, skipping")
                results.append(stored)
                continue
        print(f"[{index}/{len(registry)}] {entry['id']} ({entry['full_name']})", flush=True)
        started = time.time()
        try:
            record = vendor_repo(entry)
        except Exception as error:  # one repository must never stop the sweep
            record = {"id": entry["id"], "full_name": entry["full_name"], "url": entry["url"],
                      "license": entry["license"], "import_class": entry["import_class"],
                      "snapshot": entry["snapshot"], "vendored_files": [],
                      "skipped": {"forbidden_token": [], "skipped_path": []}, "providers": [],
                      "entrypoints": [], "runtime": [], "notes": [f"failed: {error}"]}
        record["vendored_at"] = time.strftime("%Y-%m-%d", time.gmtime())
        record["vendored_files_count"] = len(record["vendored_files"])
        record["vendored_bytes"] = sum(f["bytes"] for f in record["vendored_files"])
        target.write_text(json.dumps(record, indent=1), encoding="utf-8")
        results.append(record)
        print(f"    {record['vendored_files_count']} files, {record['vendored_bytes']} bytes, "
              f"providers={record['providers']}, runtime={record['runtime']} "
              f"({time.time() - started:.1f}s)", flush=True)

    summary = {
        "repositories": len(results),
        "with_slice": sum(1 for r in results if r["vendored_files"]),
        "total_bytes": sum(r["vendored_bytes"] for r in results),
        "with_license": sum(1 for r in results if r.get("license_file")),
    }
    print(json.dumps(summary, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
