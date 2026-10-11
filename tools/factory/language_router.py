"""Detect mixed-language repositories and select existing factory profiles."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "tools/factory/languages.json"


def load_manifest(path: Path = MANIFEST) -> dict:
    value = json.loads(path.read_text())
    if value.get("version") != 1 or not isinstance(value.get("languages"), dict):
        raise ValueError("invalid language manifest")
    return value


def detect(repo: Path, changed: list[str], manifest: dict | None = None) -> dict:
    manifest = manifest or load_manifest()
    detected: dict[str, list[str]] = {}
    project_markers: set[str] = set()
    paths = [Path(item) for item in changed]
    for language, config in manifest["languages"].items():
        matches = []
        for item in paths:
            if item.name in config.get("filenames", []) or item.suffix.lower() in config.get("extensions", []):
                matches.append(item.as_posix())
        for marker in config.get("markers", []):
            if (repo / marker).exists():
                project_markers.add(language)
        if matches:
            detected[language] = sorted(set(matches))
    selected = set(detected) | project_markers
    tools = sorted({tool for language in selected for tool in manifest["languages"][language]["tools"]})
    return {"changed_languages": detected, "project_languages": sorted(project_markers),
            "selected_languages": sorted(selected), "profiles": sorted({manifest["languages"][lang]["profile"] for lang in selected}),
            "tools": tools, "unknown_changes": not changed}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--paths", nargs="+", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/reports/factory/language-router.json"))
    args = parser.parse_args()
    result = detect(args.repo.resolve(), args.paths)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
