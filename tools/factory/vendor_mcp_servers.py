#!/usr/bin/env python3
"""Vendors the slices of the MCP tool servers and the AI-focused factories into the sealed set.

Two more groups join the 72 factories inside the seal:

* `mcp-vendor/` - the MCP servers: what each one is, how it is launched, and the tools a real
  handshake observed it publish. Their capability set is implemented natively in
  `sealed-factories/runtime/toolkit.py`, so the component still works where none of them is
  installed - an MCP server here is an extension, never a dependency.
* `vendor-ai/` - the multi-agent frameworks and AI-focused factories, vendored the same way the
  other 72 are: bounded slices, licensed, hash-sealed.

This reuses the factory vendorer's slice logic so there is one definition of "the files that define a
factory", and adds the patterns that only make sense for tool servers (their `src/`, `servers/` and
package trees).

Usage:
    python3 tools/factory/vendor_mcp_servers.py [--kind mcp|ai|both] [--only id,id] [--refresh]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vendor_imported_factories as factories  # noqa: E402  (shared slice logic)

REPO_ROOT = Path(__file__).resolve().parents[2]
MCP_REGISTRY = REPO_ROOT / "tools" / "factory" / "imported_mcp_servers.json"
AI_REGISTRY = REPO_ROOT / "tools" / "factory" / "imported_ai_factories.json"
MCP_OUT = REPO_ROOT / "sealed-factories" / "mcp-vendor"
AI_OUT = REPO_ROOT / "sealed-factories" / "vendor-ai"
CATALOGUE = REPO_ROOT / "sealed-factories" / "mcp-servers.json"

#: Where a tool server keeps the things that make it a server. Added to the shared patterns for
#: this pass only, so the factory vendoring stays exactly as it was.
SERVER_PATTERNS = (
    (10, re.compile(r"(^|/)(src|servers?|packages?|tools?)/[\w.-]+/(index|server|main|cli)\."
                    r"(py|js|ts|mjs|cjs)$", re.I)),
    (9, re.compile(r"(^|/)(src|servers?)/[\w.-]+/pyproject\.toml$", re.I)),
    (8, re.compile(r"(^|/)(package|pyproject|server)\.json$|(^|/)Dockerfile$", re.I)),
    (8, re.compile(r"(^|/)(src|servers?|packages?)/[\w.-]+/README\.md$", re.I)),
)


def registry(kind: str) -> Path:
    return MCP_REGISTRY if kind == "mcp" else AI_REGISTRY


def entries(kind: str) -> list[dict]:
    payload = json.loads(registry(kind).read_text(encoding="utf-8"))
    return payload.get("servers" if kind == "mcp" else "factories", [])


def refresh_launch(kind: str) -> dict:
    """Re-reads the launch block of every MCP record from the registry, without re-cloning.

    The slices do not change when a repository's documented command does, and re-cloning forty
    repositories to record a new command line would put the network in the path of a bookkeeping
    update. Only the fields the registry owns are written here, in the same shape `vendor()` writes
    them, so there is still one definition of what a record's launch block is.
    """
    out = MCP_OUT
    updated = 0
    for entry in entries("mcp"):
        target = out / f'{entry["id"]}.json'
        if not target.is_file():
            continue
        record = json.loads(target.read_text(encoding="utf-8"))
        record["launch"] = {"command": entry.get("command") or [],
                            "requires": entry.get("requires", ""),
                            "verified": entry.get("verified_servers", []),
                            "launch_verified": entry.get("launch_verified", False),
                            "command_source": entry.get("command_source", ""),
                            "launch_note": entry.get("launch_note", "")}
        record["what"] = entry.get("description", "")
        target.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        updated += 1
    return {"kind": "mcp-launch", "records": updated}


def vendor(kind: str, only: set[str], refresh: bool) -> dict:
    out = MCP_OUT if kind == "mcp" else AI_OUT
    out.mkdir(parents=True, exist_ok=True)
    original = list(factories.SLICE_PATTERNS)
    if kind == "mcp":
        factories.SLICE_PATTERNS = list(SERVER_PATTERNS) + original
    try:
        selected = [entry for entry in entries(kind) if not only or entry["id"] in only]
        written = 0
        total_bytes = 0
        with_slice = 0
        for index, entry in enumerate(selected, start=1):
            target = out / f'{entry["id"]}.json'
            if target.is_file() and not refresh:
                stored = json.loads(target.read_text(encoding="utf-8"))
                if stored.get("vendored_files"):
                    print(f"[{index}/{len(selected)}] {entry['id']}: already vendored")
                    with_slice += 1
                    continue
            record = factories.vendor_repo(entry)
            if kind == "mcp":
                # The launch line and the handshake evidence are part of what was imported.
                record["launch"] = {"command": entry.get("command") or [],
                                    "requires": entry.get("requires", ""),
                                    "verified": entry.get("verified_servers", []),
                                    "launch_verified": entry.get("launch_verified", False)}
                record["what"] = entry.get("description", "")
            target.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
            written += 1
            files = len(record["vendored_files"])
            total_bytes += record.get("vendored_bytes", 0)
            with_slice += 1 if files else 0
            print(f'[{index}/{len(selected)}] {entry["id"]}: {files} file(s), '
                  f'{record.get("vendored_bytes", 0)} bytes')
        return {"kind": kind, "repositories": len(selected), "written": written,
                "with_slice": with_slice, "total_bytes": total_bytes}
    finally:
        factories.SLICE_PATTERNS = original


def write_catalogue() -> dict:
    """The sealed copy of the MCP catalogue: what the runtime binds, with its evidence."""
    payload = json.loads(MCP_REGISTRY.read_text(encoding="utf-8"))
    servers = []
    for entry in payload["servers"]:
        servers.append({
            "name": entry["id"], "repository": entry["full_name"], "url": entry["url"],
            "license": entry["license"], "command": entry.get("command") or [],
            "requires": entry.get("requires", ""), "tools": [tool for verified
                                                             in entry.get("verified_servers", [])
                                                             for tool in verified["tools"]],
            "verified": entry.get("launch_verified", False),
            "what": entry.get("description", ""),
        })
    document = {"snapshot": payload["snapshot"], "servers": servers,
                "verified_total_servers": payload.get("verified_total_servers", 0),
                "verified_total_tools": payload.get("verified_total_tools", 0),
                "method": payload["method"]}
    CATALOGUE.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return {"catalogue": str(CATALOGUE.relative_to(REPO_ROOT)), "servers": len(servers),
            "verified": sum(1 for s in servers if s["verified"])}


#: Records and the catalogue are committed files, and CI runs `npm run format:check` over them, so
#: after a pass the committed copy is normalised with `npm run format` before it is committed.
#: Prettier is not available to this script (and must not become a dependency of a tool that has to
#: run on a bare interpreter), so the writer gets as close as it can - two-space indent, trailing
#: newline - and the formatter finishes the job. Prettier collapses short arrays that fit the print
#: width, which no plain `json.dumps` writes.
def main() -> int:
    parser = argparse.ArgumentParser(description="Vendor the MCP servers and AI factories")
    parser.add_argument("--kind", default="both", choices=("mcp", "ai", "both"))
    parser.add_argument("--only", default="")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--launch-only", action="store_true",
                        help="rewrite each MCP record's launch block from the registry and stop")
    args = parser.parse_args()

    if not args.launch_only:
        only = {part.strip() for part in args.only.split(",") if part.strip()}
        kinds = ("mcp", "ai") if args.kind == "both" else (args.kind,)
        summary = {"kinds": []}
        for kind in kinds:
            summary["kinds"].append(vendor(kind, only, args.refresh))
    else:
        summary = {"kinds": []}
    if args.launch_only or args.kind in ("mcp", "both"):
        summary["kinds"].append(refresh_launch("mcp"))
    summary.update(write_catalogue())
    print(json.dumps(summary, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
