#!/usr/bin/env python3
"""The sealed component verifying itself, from inside itself.

`cli.py verify` calls this. It reads nothing but the component's own files, so a copy of
`sealed-factories/` dropped into any other repository can prove its own integrity with the
interpreter it already has - no repository tooling, no imports from outside the directory, no
network. `tools/factory/seal_factories.py` remains the sealer, and its `verify()` is kept in step
with this one by `shell/tests/test_sealed_environment.py`, which runs both against the same
component and against a tampered copy.

    python3 sealed-factories/runtime/cli.py verify
    python3 -m sealcheck          # when run from inside this directory

Exit codes: 0 verified, 1 the component does not match its seal, 3 no seal to verify.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

#: The component directory, found from this file's own location - never from the working directory.
SEALED = Path(__file__).resolve().parent.parent
SEAL_FILE = SEALED / "SEAL.json"
SEAL_HASH = SEALED / "SEAL.sha256"
_CHUNK = 1 << 20

#: Data the runtime reads at run time, hashed into the seal alongside the runtime's own code. Kept
#: identical to `COMPONENT_DATA` in tools/factory/seal_factories.py; the agreement test pins that.
COMPONENT_DATA = ("mcp-servers.json",)


def sha256_file(path: Path) -> str:
    """The file's hash, read in chunks so a large payload does not have to fit in memory."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(_CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def read_document() -> dict | None:
    """The seal itself, or None when the component is not sealed at all."""
    if not SEAL_FILE.is_file():
        return None
    return json.loads(SEAL_FILE.read_text(encoding="utf-8"))


def problems(document: dict) -> list[str]:
    """Every way this component fails to match its own seal. Empty means verified."""
    found: list[str] = []
    recorded_root = SEAL_HASH.read_text(encoding="utf-8").split()[0] if SEAL_HASH.is_file() else None
    actual_root = sha256_file(SEAL_FILE)
    if recorded_root != actual_root:
        found.append(f"SEAL.json was modified: recorded {recorded_root}, now {actual_root}")

    factories = document.get("factories", [])
    for factory in factories:
        payload = SEALED / factory["payload"]
        if not payload.is_file():
            found.append(f'{factory["id"]}: payload missing ({factory["payload"]})')
        else:
            digest = sha256_file(payload)
            if digest != factory["payload_sha256"]:
                found.append(f'{factory["id"]}: payload hash {digest} != sealed '
                             f'{factory["payload_sha256"]}')
            if payload.stat().st_size != factory["payload_bytes"]:
                found.append(f'{factory["id"]}: payload is {payload.stat().st_size} bytes, the seal '
                             f'says {factory["payload_bytes"]}')
        if factory["license_present"] and not (SEALED / "licenses" / factory["license_file"]).is_file():
            found.append(f'{factory["id"]}: licence file missing')
        if factory["content_redistributed"] and not factory["payload_bytes"]:
            found.append(f'{factory["id"]}: the seal carries content but no payload bytes')

    # The counts a reader trusts without re-deriving them. They are recomputed here, so a manifest
    # that contradicts its own records is a failure rather than a footnote.
    # The component's own machinery: the payloads are the factories, these are the programs that run
    # them. A file that appears in runtime/ without being recorded is as much a failure as a changed
    # one, so a half-updated copy cannot pass by adding a module.
    records = document.get("component_files", [])
    for record in records:
        path = SEALED / record["path"]
        if not path.is_file():
            found.append(f'component file missing: {record["path"]}')
        else:
            digest = sha256_file(path)
            if digest != record["sha256"]:
                found.append(f'component file modified: {record["path"]} ({digest[:16]}… != '
                             f'{record["sha256"][:16]}…)')
    recorded_paths = {record["path"] for record in records}
    present_paths = {str(path.relative_to(SEALED))
                     for path in (SEALED / "runtime").rglob("*.py")
                     if "__pycache__" not in path.parts}
    present_paths |= {name for name in COMPONENT_DATA if (SEALED / name).is_file()}
    for extra in sorted(present_paths - recorded_paths):
        found.append(f"component file not in the seal: {extra}")

    contents = document.get("contents", {})
    derived = {
        "factories": len(factories),
        "with_payload": sum(1 for f in factories if f["payload_bytes"]),
        "content_redistributed": sum(1 for f in factories if f["content_redistributed"]),
        "blueprint_only": sum(1 for f in factories if not f["content_redistributed"]),
        "with_license_text": sum(1 for f in factories if f["license_present"]),
        "ai_worker_factories": sum(1 for f in factories if f.get("ai_workers")),
        "payload_bytes": sum(f["payload_bytes"] for f in factories),
        "by_group": {group: sum(1 for f in factories if f.get("group") == group)
                     for group in sorted({f.get("group", "") for f in factories})},
        "component_files": len(records),
    }
    for key, value in derived.items():
        if key in contents and contents[key] != value:
            found.append(f'contents["{key}"] is {contents[key]!r}, the records say {value!r}')
    return found


def verify(quiet: bool = False) -> int:
    document = read_document()
    if document is None:
        print("the sealed component is not present (SEAL.json missing)", file=sys.stderr)
        return 3
    found = problems(document)
    if found:
        print("[SEAL] FAIL:")
        for problem in found:
            print(f"  - {problem}")
        return 1
    if not quiet:
        contents = document["contents"]
        root = sha256_file(SEAL_FILE)
        print(f"[SEAL] OK {document['seal_version']}: {contents['factories']} factories, "
              f"{contents['payload_bytes']} payload bytes, "
              f"{contents['with_license_text']} licence texts, root {root[:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(verify())
