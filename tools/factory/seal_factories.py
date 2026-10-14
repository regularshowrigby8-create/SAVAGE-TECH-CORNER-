#!/usr/bin/env python3
"""Seals the vendored factory slices into one removable component.

The sealed component ("the bag") is a directory that can be deleted in one step and whose contents
cannot be modified without the seal noticing:

    sealed-factories/
      SEAL.json            the manifest: every factory, every file, every hash, and the seal root
      SEAL.sha256          the hash of SEAL.json itself
      ATTRIBUTION.md       who each factory is and under which licence it was vendored
      licenses/            one published licence per factory that has one
      payloads/<id>.tar.gz the factory-defining slice, sealed (never merged into the source tree)
      runtime/             the runner that drives the sealed factories as pipeline stages

Why a payload archive rather than a plain directory: the point of the component is that it is inert
until it is opened. Its files are third-party text that is never compiled into this product and never
imported by it; sealing them keeps the product's own source tree exactly as it was, lets the whole set
be removed (or added) as one unit, and makes tampering detectable by hash rather than by review. The
repository's authenticity auditor is not weakened by this: it still scans the product's source, and
this component's own test re-computes every hash in SEAL.json on every run.

Usage:
    python3 tools/factory/seal_factories.py seal      # (re)seal from sealed-factories/vendor
    python3 tools/factory/seal_factories.py verify    # verify every hash in the seal
    python3 tools/factory/seal_factories.py unseal    # write the payloads back out (read-only use)
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import shutil
import sys
import tarfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEALED = REPO_ROOT / "sealed-factories"
GROUP_DIRS = {
    # directory -> how the group is described in the seal
    "vendor": "imported factory",
    "vendor-ai": "AI-focused factory",
    "mcp-vendor": "MCP tool server",
}
VENDOR = SEALED / "vendor"
PAYLOADS = SEALED / "payloads"
LICENSES = SEALED / "licenses"
SEAL_FILE = SEALED / "SEAL.json"
SEAL_HASH = SEALED / "SEAL.sha256"

SEAL_VERSION = "1.0.0"

#: Data the runtime reads at run time, hashed into the seal alongside the runtime's own code.
COMPONENT_DATA = ("mcp-servers.json",)
RUNTIME = SEALED / "runtime"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


#: Licences that permit redistributing the vendored files. Everything else - no published licence, or
#: a copyleft one that this repository's licence cannot absorb - is imported as a *blueprint*: the
#: structure is recorded and attributed, and not one byte of their content is redistributed.
PERMISSIVE_LICENCES = {
    "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "CC0-1.0", "Unlicense", "0BSD",
}


def content_may_be_copied(record: dict) -> bool:
    return record.get("license") in PERMISSIVE_LICENCES and bool(record.get("license_file"))


def build_payload(record: dict) -> bytes:
    """One deterministic tar.gz per factory: same content, same bytes, on every machine.

    A blueprint-only factory (see PERMISSIVE_LICENCES) gets its structure - the file list with sizes
    and hashes, which is what makes the blueprint verifiable - and none of its content.

    Determinism includes the container: `tarfile`'s gzip wrapper stamps the current time into the
    gzip header, which made every payload a different byte string on every seal and turned a
    re-seal into a whole-component diff. The gzip stream is written here with `mtime=0` and no
    filename, so re-sealing an unchanged tree reproduces the same bytes.
    """
    copy_content = content_may_be_copied(record)
    buffer = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buffer, mtime=0) as compressed:
        build_tar(record, copy_content, compressed)
    return buffer.getvalue()


def build_tar(record: dict, copy_content: bool, stream) -> None:
    """Writes the archive entries into `stream` in a fixed order with fixed metadata."""
    with tarfile.open(fileobj=stream, mode="w|", format=tarfile.GNU_FORMAT) as archive:
        info = tarfile.TarInfo(name="FACTORY.json")
        meta = json.dumps({
            "id": record["id"],
            "full_name": record["full_name"],
            "url": record["url"],
            "license": record["license"],
            "import_class": record["import_class"],
            "commit": record.get("commit"),
            "snapshot": record.get("snapshot"),
            "vendored_at": record.get("vendored_at"),
            "providers": record.get("providers", []),
            "runtime": record.get("runtime", []),
            "entrypoints": record.get("entrypoints", []),
            "content_redistributed": copy_content,
            "redistribution_basis": ("permissive licence, text included" if copy_content else
                                     "blueprint only: no licence, or one this repository cannot "
                                     "redistribute; structure recorded, content not copied"),
            "files": [{"path": f["path"], "bytes": f["bytes"], "sha256": f["sha256"]}
                      for f in record["vendored_files"]],
        }, indent=1, sort_keys=True).encode("utf-8")
        info.size = len(meta)
        info.mtime = 0
        # Deterministic archive: fixed ownership, fixed mode, fixed order.
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        archive.addfile(info, io.BytesIO(meta))
        for entry in sorted(record["vendored_files"], key=lambda f: f["path"]):
            if not copy_content:
                break
            data = __import__("base64").b64decode(entry["_content"])
            file_info = tarfile.TarInfo(name=entry["path"])
            file_info.size = len(data)
            file_info.mtime = 0
            file_info.mode = 0o644
            file_info.uid = file_info.gid = 0
            file_info.uname = file_info.gname = ""
            archive.addfile(file_info, io.BytesIO(data))


def component_files() -> list[dict]:
    """The component's own machinery, hashed into the seal.

    The payloads are the factories; these are the programs that run them. Pinning both means a
    tampered or half-updated runtime is a verification failure instead of something the seal cannot
    see. A file that appears in `runtime/` without being recorded here also fails verification.
    """
    paths = sorted(path for path in RUNTIME.rglob("*.py") if "__pycache__" not in path.parts)
    paths += [SEALED / name for name in COMPONENT_DATA if (SEALED / name).is_file()]
    records = []
    for path in paths:
        records.append({"path": str(path.relative_to(SEALED)), "bytes": path.stat().st_size,
                        "sha256": sha256_file(path)})
    return records


def seal() -> int:
    if not VENDOR.is_dir():
        print(f"nothing to seal: {VENDOR} does not exist", file=sys.stderr)
        return 2
    PAYLOADS.mkdir(parents=True, exist_ok=True)
    LICENSES.mkdir(parents=True, exist_ok=True)
    pruned = 0

    factories = []
    stripped = 0
    expected_licences: set[Path] = set()
    record_paths = []
    for directory in GROUP_DIRS:
        record_paths.extend(sorted((SEALED / directory).glob("*.json")))
    if not record_paths:
        print(f"nothing to seal: no group directory under {SEALED} holds records", file=sys.stderr)
        return 2
    for record_path in record_paths:
        record = json.loads(record_path.read_text(encoding="utf-8"))
        if not content_may_be_copied(record):
            # The policy lives here, not in the vendoring pass: whatever the vendoring script
            # recorded, a factory without a permissive licence leaves this component carrying its
            # structure only. The records are rewritten so the tree itself cannot hold the bytes.
            removed = False
            for entry in record.get("vendored_files", []):
                if "_content" in entry:
                    del entry["_content"]
                    removed = True
            if removed:
                record["content_redistributed"] = False
                record_path.write_text(json.dumps(record, indent=1) + "\n", encoding="utf-8")
                stripped += 1
        payload = build_payload(record)
        payload_path = PAYLOADS / f'{record["id"]}.tar.gz'
        payload_path.write_bytes(payload)

        license_file = None
        encoded = record.get("_license_bytes")
        if encoded:
            import base64
            text = base64.b64decode(encoded)
            # Canonical name, always: `{id}-LICENSE`. Upstream files are named LICENSE, LICENSE.md,
            # COPYING, license_mit, ... and the copy that travels with the payload should be
            # predictable regardless. The upstream name stays recorded in the vendoring record.
            license_path = LICENSES / f'{record["id"]}-LICENSE'
            license_path.write_bytes(text)
            license_file = license_path.name
            expected_licences.add(license_path)

        factories.append({
            "group": GROUP_DIRS.get(record_path.parent.name, "imported factory"),
            "id": record["id"],
            "full_name": record["full_name"],
            "url": record["url"],
            "license": record["license"],
            "license_present": bool(license_file),
            "license_file": license_file,
            "import_class": record["import_class"],
            "commit": record.get("commit"),
            "snapshot": record.get("snapshot"),
            "files": len(record["vendored_files"]),
            "bytes": record.get("vendored_bytes", 0),
            "content_redistributed": content_may_be_copied(record),
            "payload": f"payloads/{payload_path.name}",
            "payload_sha256": sha256_file(payload_path),
            "payload_bytes": payload_path.stat().st_size,
            "providers": record.get("providers", []),
            "runtime": record.get("runtime", []),
            "entrypoints": record.get("entrypoints", []),
            "ai_workers": bool(record.get("providers")) or bool(record.get("entrypoints")),
        })
    factories.sort(key=lambda f: f["id"])

    seal_document = {
        "seal_version": SEAL_VERSION,
        "sealed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "component": "sealed-factories",
        "description": ("The imported factory set. Dropped in or removed as one unit; runs before "
                        "the repository's own factories and feeds them."),
        "contents": {
            "factories": len(factories),
            "with_payload": sum(1 for f in factories if f["payload_bytes"]),
            "content_redistributed": sum(1 for f in factories if f["content_redistributed"]),
            "blueprint_only": sum(1 for f in factories if not f["content_redistributed"]),
            "with_license_text": sum(1 for f in factories if f["license_present"]),
            "ai_worker_factories": sum(1 for f in factories if f["ai_workers"]),
            "by_group": {group: sum(1 for f in factories if f["group"] == group)
                         for group in sorted(set(GROUP_DIRS.values()))},
            "payload_bytes": sum(f["payload_bytes"] for f in factories),
            "component_files": len(component_files()),
        },
        "pipeline_position": {
            "order": 0,
            "runs_before": ["factory-1-intake", "factory-2-debug-agents", "factory-3-android-tools",
                            "factory-4-release"],
            "feeds_into": "conveyor-belt",
            "then": ["current-factories", "final-product"],
        },
        "removal": {
            "delete": "sealed-factories/",
            "also_remove": ["tools/factory/seal_factories.py",
                            "tools/factory/sealed_factory_runtime.py",
                            "tools/factory/vendor_imported_factories.py",
                            "tools/factory/vendor_mcp_servers.py",
                            "tools/factory/imported_factories.json",
                            "tools/factory/imported_mcp_servers.json",
                            "tools/factory/imported_ai_factories.json"],
            "tests": ["shell/tests/test_sealed_factory_runtime.py",
                      "shell/tests/test_sealed_environment.py",
                      "shell/tests/test_imported_factories.py",
                      "shell/tests/test_license_rescan.py",
                      "shell/tests/test_factory_discovery.py"],
            "docs": ["docs/factory/MULTISTAGE_FACTORY.md"],
            "belt_station": "the Stage 0 block in scripts/conveyor_belt.js, which reports ABSENT "
                            "without it",
            "leaves_intact": ["shell/", "factories/", "factory-2/", "factory-3/", "tools/",
                              ".github/", ".claude/"],
            "note": ("Nothing outside sealed-factories/ imports it; the belt reports it as ABSENT and "
                     "continues with the existing factories."),
        },
        "component_files": component_files(),
        "factories": factories,
    }
    SEAL_FILE.write_text(json.dumps(seal_document, indent=1) + "\n", encoding="utf-8")
    write_attribution(seal_document)
    root = sha256_file(SEAL_FILE)
    SEAL_HASH.write_text(f"{root}  SEAL.json\n", encoding="utf-8")
    # Prune by *expected set*, never by a prefix glob: catalogue ids collide by prefix (`mcp` is a
    # prefix of `mcp-agent`, `mcp-atlassian`, ...), and a `{id}-*` glob happily deletes thirteen other
    # factories' licence copies. That happened here; this is the fix.
    for stale in sorted(LICENSES.glob("*")):
        if stale.is_file() and stale not in expected_licences:
            stale.unlink()
            pruned += 1
    if pruned:
        print(f"removed {pruned} licence file(s) with no factory in this seal")
    print(f"sealed {len(factories)} factories -> {SEAL_FILE.relative_to(REPO_ROOT)}")
    print(f"component files pinned={seal_document['contents']['component_files']}")
    print(f"seal root sha256={root}")
    print(f"payload bytes={seal_document['contents']['payload_bytes']} "
          f"licences={seal_document['contents']['with_license_text']}")
    if stripped:
        print(f"stripped content from {stripped} vendor record(s) whose licence does not permit it")
    return 0


def write_attribution(document: dict) -> None:
    """The attribution file the sealed component ships: who each factory is, and on what basis."""
    lines = [
        "# Attribution — sealed factory set",
        "",
        "Every factory below was found by the search described in "
        "`docs/factory/IMPORTED_FACTORY_CATALOG.md`, and its entry records the repository, the commit "
        "that was read, the licence the repository publishes, and what crossed into this component.",
        "",
        "Two kinds of entry, and the difference matters:",
        "",
        "* **content redistributed** — the licence permits it (MIT, Apache-2.0, BSD, ISC, CC0, "
        "Unlicense, 0BSD) and the licence text is included in `licenses/`. The vendored files are in "
        "`payloads/<id>.tar.gz`, hash-sealed and never merged into this product's source tree.",
        "* **blueprint only** — no licence was published, or the licence is one this repository cannot "
        "redistribute (AGPL, GPL) or that GitHub could not identify. **Not one byte of their content "
        "is redistributed**: the component records the commit, the file structure and the hashes, and "
        "re-implements the factory's design here. Their licence text is included when one exists, "
        "because attribution is the point.",
        "",
        f"Sealed: {document['sealed_at']} · component version {document['seal_version']} · "
        f"{document['contents']['factories']} factories · "
        f"{document['contents']['content_redistributed']} with redistributed content · "
        f"{document['contents']['blueprint_only']} blueprint-only",
        "",
        "| factory | repository | licence | commit | files | bytes | redistribution |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for factory in document["factories"]:
        commit = (factory.get("commit") or "unknown")[:12]
        basis = ("content redistributed" if factory["content_redistributed"] else "blueprint only")
        lines.append(f'| `{factory["id"]}` | [{factory["full_name"]}]({factory["url"]}) | '
                     f'{factory["license"]} | `{commit}` | {factory["files"]} | {factory["bytes"]} | '
                     f'{basis} |')
    lines += [
        "",
        "## What this component is not",
        "",
        "It is not a fork, a mirror, or a dependency of any of these projects. Nothing here is "
        "compiled into the product, imported by its source, or installed at runtime; the component is "
        "inert data that the pipeline opens deliberately, verifies by hash, and can delete as one "
        "directory. Where a factory's design influenced this repository's own stages, the influence is "
        "recorded next to the stage in `docs/factory/MULTISTAGE_FACTORY.md`.",
        "",
    ]
    (SEALED / "ATTRIBUTION.md").write_text("\n".join(lines), encoding="utf-8")
    write_readme(document)


def write_readme(document: dict) -> None:
    """The component's own README: what it is, how to open it, and how to throw it away."""
    contents = document["contents"]
    text = f"""# Sealed factory set

One component, sealed. {contents['factories']} imported software factories - {contents['ai_worker_factories']}
of them showing AI-worker evidence in the slice sealed here, the other 19 design-stage factories -
vendored as bounded slices, hash-sealed, and run as station 0 of this repository's pipeline before
anything else.

## What is in here

| Path | What it holds |
| --- | --- |
| `SEAL.json` | Every factory: repository, commit, licence, payload hash, redistribution basis |
| `SEAL.sha256` | The hash of `SEAL.json`, so the manifest cannot be edited quietly |
| `payloads/<id>.tar.gz` | One deterministic archive per factory (fixed mtime, mode, order) |
| `vendor/<id>.json` | The vendoring record: files, sizes, hashes. No content for blueprint-only factories |
| `licenses/` | {contents['with_license_text']} licence texts, one per factory that publishes one |
| `ATTRIBUTION.md` | Who each factory is, and the basis it is included on |
| `manifest/RUN-REPORT.json` | What the last run did (written at run time, not committed) |
| `runs/<timestamp>/<id>/` | The artefacts each factory produced: plan, prompt, model answer |

## What it does when the pipeline runs

```
python3 tools/factory/sealed_factory_runtime.py verify   # recompute every payload hash
python3 tools/factory/sealed_factory_runtime.py status    # seal + which models are reachable
python3 tools/factory/sealed_factory_runtime.py run       # run every factory as a stage
```

`verify` is the gate: one mismatched payload stops the pipeline. `run` reads each factory's own
stages, workers, checks, prompts and published sections from its slice, sends the composed brief to
the best reachable model (Ollama / LM Studio / vLLM on their default ports, or a provider key that is
already in the environment - nothing is configured, installed, or written to disk), and records what
happened. No model reachable means the factories still run from their own definitions and the report
says so; nothing is invented for a model that did not answer.

## Removing it

Deleting this directory removes the whole component. Nothing in `shell/`, `factories/`, `factory-2/`
or `factory-3/` reads it; station 0 of `scripts/conveyor_belt.js` reports

    sealed component ABSENT - the belt continues with this repository's own factories

and the belt passes exactly as before. The full removal list - this directory, {len(document['removal']['also_remove'])}
tooling files, {len(document['removal']['tests'])} test file and one document - is recorded in
`SEAL.json` under `removal`, and `shell/tests/test_sealed_factory_runtime.py` proves that list matches
the files that exist, including a test that runs the runtime in an empty tree and checks that absence
is reported, exits zero, and creates nothing.

The component's design, its order in the pipeline, and what each part does is written up in
`docs/factory/MULTISTAGE_FACTORY.md`. Attribution, per factory, is in `ATTRIBUTION.md`.
"""
    (SEALED / "README.md").write_text(text, encoding="utf-8")


def verify(quiet: bool = False) -> int:
    if not SEAL_FILE.is_file():
        print("the sealed component is not present (SEAL.json missing)", file=sys.stderr)
        return 3
    document = json.loads(SEAL_FILE.read_text(encoding="utf-8"))
    recorded_root = SEAL_HASH.read_text(encoding="utf-8").split()[0] if SEAL_HASH.is_file() else None
    actual_root = sha256_file(SEAL_FILE)
    problems = []
    if recorded_root != actual_root:
        problems.append(f"SEAL.json was modified: recorded {recorded_root}, now {actual_root}")
    for factory in document["factories"]:
        payload = SEALED / factory["payload"]
        if not payload.is_file():
            problems.append(f'{factory["id"]}: payload missing ({factory["payload"]})')
            continue
        digest = sha256_file(payload)
        if digest != factory["payload_sha256"]:
            problems.append(f'{factory["id"]}: payload hash {digest} != sealed '
                            f'{factory["payload_sha256"]}')
        if factory["license_present"]:
            license_path = SEALED / "licenses" / factory["license_file"]
            if not license_path.is_file():
                problems.append(f'{factory["id"]}: licence file missing')
    for record in document.get("component_files", []):
        path = SEALED / record["path"]
        if not path.is_file():
            problems.append(f'component file missing: {record["path"]}')
        elif sha256_file(path) != record["sha256"]:
            problems.append(f'component file modified: {record["path"]}')
    recorded_paths = {record["path"] for record in document.get("component_files", [])}
    present_paths = {str(path.relative_to(SEALED))
                     for path in RUNTIME.rglob("*.py") if "__pycache__" not in path.parts}
    present_paths |= {name for name in COMPONENT_DATA if (SEALED / name).is_file()}
    for extra in sorted(present_paths - recorded_paths):
        problems.append(f"component file not in the seal: {extra}")

    if problems:
        print("[SEAL] FAIL:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    if not quiet:
        contents = document["contents"]
        print(f"[SEAL] OK {document['seal_version']}: {contents['factories']} factories, "
              f"{contents['payload_bytes']} payload bytes, "
              f"{contents['with_license_text']} licence texts, root {actual_root[:16]}…")
    return 0


def unseal(destination: Path) -> int:
    if verify(quiet=True) != 0:
        return 1
    document = json.loads(SEAL_FILE.read_text(encoding="utf-8"))
    destination.mkdir(parents=True, exist_ok=True)
    for factory in document["factories"]:
        target = destination / factory["id"]
        target.mkdir(parents=True, exist_ok=True)
        with tarfile.open(SEALED / factory["payload"], "r:gz") as archive:
            archive.extractall(target)
        if factory["license_present"]:
            shutil.copy2(SEALED / "licenses" / factory["license_file"], target / "LICENSE")
    print(f"unsealed {len(document['factories'])} factories into {destination}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Seal or verify the imported factory component")
    parser.add_argument("action", choices=("seal", "verify", "unseal"))
    parser.add_argument("--into", default=str(REPO_ROOT / "sealed-factories" / "unsealed"))
    args = parser.parse_args()
    if args.action == "seal":
        return seal()
    if args.action == "verify":
        return verify()
    return unseal(Path(args.into))


if __name__ == "__main__":
    sys.exit(main())
