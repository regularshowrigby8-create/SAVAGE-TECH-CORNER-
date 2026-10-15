#!/usr/bin/env python3
"""Which catalogued factories need their slice (re)vendored before the set can be sealed again.

The growth run re-reads the licence text of every catalogued repository. Where that turns a `NOASSERTION`
into a licence this component may carry, the record's content becomes carriable - but the sealer only
carries what the *vendored* record proves, and that record still holds the verdict from when it was
fetched. So a record needs vendoring when it has no slice at all, or when its slice was fetched under a
different licence than the registry now records.

Prints one `kind:id,id,id` line per registry that has work, for `tools/ci/factory_growth.sh` to hand to
the matching vendoring tool. Prints nothing when there is nothing to do, so the growth run is a no-op
rather than a re-fetch of the whole set (which would be thousands of API calls for no change).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

#: (the kind the vendoring tool knows, the registry, the group directory under sealed-factories/)
REGISTRIES = (
    ("imported", REPO_ROOT / "tools" / "factory" / "imported_factories.json", "vendor"),
    ("ai", REPO_ROOT / "tools" / "factory" / "imported_ai_factories.json", "vendor-ai"),
    ("mcp", REPO_ROOT / "tools" / "factory" / "imported_mcp_servers.json", "mcp-vendor"),
)


def entries_of(payload) -> list[dict]:
    """The records a registry holds, whichever shape it uses: a list, or a dict of named lists."""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("factories", "servers", "records", "entries"):
            if isinstance(payload.get(key), list):
                return payload[key]
    return []


def slice_of(directory: Path, identifier: str) -> dict | None:
    """The vendored record for `identifier`, or None when there is no slice on disk."""
    path = directory / f"{identifier}.json"
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def needs_vendoring(entry: dict, record: dict | None) -> bool:
    """No slice yet, or a slice fetched under a different licence than the registry now records."""
    if record is None:
        return True
    return (record.get("license") or "") != (entry.get("license") or "")


def main() -> int:
    sealed = REPO_ROOT / "sealed-factories"
    for kind, registry, directory in REGISTRIES:
        if not registry.is_file():
            print(f"the {kind} registry is missing at {registry.relative_to(REPO_ROOT)}", file=sys.stderr)
            return 1
        payload = json.loads(registry.read_text(encoding="utf-8"))
        group = sealed / directory
        pending = [entry["id"] for entry in entries_of(payload)
                   if entry.get("id") and needs_vendoring(entry, slice_of(group, entry["id"]))]
        if pending:
            print(f"{kind}:{','.join(pending)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
