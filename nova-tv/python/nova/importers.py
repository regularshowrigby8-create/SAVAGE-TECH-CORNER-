"""Import real logic from repositories instead of re-writing it.

The rule this module exists to enforce: when a proven implementation already
exists, copy its logic across with attribution, rather than inventing a parallel
one. Each importer records exactly which upstream file it took logic from, so the
provenance is auditable and re-runs are reproducible.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from .github import GitHub, GitHubError

# Where each piece of behaviour is taken from. `path` is the file in this repo the
# logic should be lifted out of, so nothing is re-derived.
SOURCES = {
    "player-core": {
        "repo": "regularshowrigby8-create/SAVAGE-TECH-CORNER-",
        "path": "tv/zeroplay-debrid/lib/player-core.js",
        "local": ".python/nova/imported/player_core.json",
        "gives": "idle timings, hold reasons, seek/skip constants",
    },
    "player-sources": {
        "repo": "regularshowrigby8-create/SAVAGE-TECH-CORNER-",
        "path": "tv/zeroplay-debrid/PLAYER-SOURCES.md",
        "local": ".python/nova/imported/player_sources.md",
        "gives": "which upstream project each rule came from",
    },
    "android-shell": {
        "repo": "regularshowrigby8-create/SAVAGE-TECH-CORNER-",
        "path": "shell/android_project",
        "local": ".python/nova/imported/android_shell.json",
        "gives": "existing Android project layout the factory already builds",
    },
}

NUM = r"([0-9]+)"


def freeze_block(js: str, name: str) -> str:
    """Return the body of `const NAME = Object.freeze({ ... });`, or "".

    Scoping the read to the block is what keeps prose out of the result:
    scanning the whole file picks up sentences like "5% per press" as if they
    were constants.
    """
    block = re.search(
        rf"(?:const|var|let)\s+{name}\s*=\s*Object\.freeze\(\s*\{{(.*?)\}}\s*\)",
        js,
        re.DOTALL,
    )
    return block.group(1) if block else ""


def extract_timings(js: str) -> Dict[str, int]:
    """Pull the TIMING constants out of player-core.js.

    Only named constants inside the TIMING block are read; the format is stable
    because this file is authored in this repo, and a missing key is reported
    rather than defaulted, so a rename upstream surfaces instead of drifting.
    """
    body = freeze_block(js, "TIMING")
    if not body:
        return {}
    found: Dict[str, int] = {}
    for match in re.finditer(rf"^\s*(\w+)\s*:\s*{NUM}\b", body, re.MULTILINE):
        found[match.group(1)] = int(match.group(2))
    return found


def extract_skip_seconds(js: str) -> Dict[str, int]:
    body = freeze_block(js, "SKIP_SECONDS")
    if not body:
        return {}
    return {name: int(value) for name, value in re.findall(rf"(\w+)\s*:\s*{NUM}\b", body)}


def extract_hold_reasons(js: str) -> List[str]:
    body = freeze_block(js, "HOLD_REASONS")
    if not body:
        return []
    return re.findall(r"\w+\s*:\s*\"(\w+)\"", body)


KOTLIN_TARGET = "android/app/src/main/java/dev/nova/tv/player/ImportedTimings.kt"

KOTLIN_TEMPLATE = '''package dev.nova.tv.player

/**
 * GENERATED — do not edit by hand.
 *
 * Imported from {source} by `nova/importers.py`.
 * Re-run: `python3 scripts/import_player_core.py`
 *
 * Taking the numbers from the already-proven implementation rather than
 * re-inventing them keeps the Android player and the web player in step.
 */
object ImportedTimings {{
{timings}}}

object ImportedSkipSeconds {{
{skips}}}

enum class ImportedHoldReason {{
{reasons}
}}
'''


def render_kotlin(timings: Dict[str, int], skips: Dict[str, int], reasons: List[str], source: str) -> str:
    timing_lines = "\n".join(f"    const val {name.upper()} = {value}L" for name, value in timings.items())
    skip_lines = "\n".join(f"    const val {name.upper()} = {value}000L" for name, value in skips.items())
    reason_lines = ",\n".join(f"    {name.upper()}" for name in reasons)
    return KOTLIN_TEMPLATE.format(
        source=source, timings=timing_lines, skips=skip_lines, reasons=reason_lines
    )


# `root` in these functions is always the nova-tv directory; sources are read
# from the repository root above it. Keeping the two distinct is what stops
# generated files landing in the wrong tree.
def source_root(root: Path) -> Path:
    return root.parent


@dataclass
class ImportResult:
    key: str
    ok: bool
    detail: str
    written: List[str] = field(default_factory=list)


def read_source(gh: Optional[GitHub], repo: str, path: str, root: Path) -> tuple[Optional[str], str]:
    """Read a file from the working tree when it is there, else from GitHub.

    The repository on disk is the primary source: these files are authored here,
    so a local copy is always at least as new as the pushed one. GitHub is the
    fallback for a fresh clone or a CI run where the tree is sparse.
    """
    local = source_root(root) / path
    if local.is_file():
        try:
            return local.read_text(), f"local:{path}"
        except OSError:
            pass
    if gh is None:
        return None, "no local file and no GitHub client"
    try:
        return gh.file_text(repo, path), f"github:{repo}:{path}"
    except GitHubError as error:
        return None, f"could not fetch: {error}"


def import_player_core(gh: Optional[GitHub], root: Path) -> ImportResult:
    """Generate the Android timings from the web player's core."""
    spec = SOURCES["player-core"]
    source, origin = read_source(gh, spec["repo"], spec["path"], root)
    if source is None:
        return ImportResult("player-core", False, origin)

    timings = extract_timings(source)
    skips = extract_skip_seconds(source)
    reasons = extract_hold_reasons(source)
    if not timings or not skips or not reasons:
        return ImportResult(
            "player-core",
            False,
            f"incomplete extraction: {len(timings)} timings, {len(skips)} skips, {len(reasons)} reasons",
        )

    # Keep the raw source too, so the extraction can be re-checked offline.
    cache = root / "python" / "nova" / "imported"
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "player_core.js").write_text(source, "utf-8")
    (cache / "player_core.json").write_text(
        json.dumps({"timings": timings, "skips": skips, "reasons": reasons}, indent=2), "utf-8"
    )

    kotlin = render_kotlin(timings, skips, reasons, f"{spec['repo']}:{spec['path']}")
    target = root / KOTLIN_TARGET
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(kotlin, "utf-8")

    return ImportResult(
        "player-core",
        True,
        f"{len(timings)} timings, {len(skips)} skips, {len(reasons)} hold reasons (from {origin})",
        written=[str(target.relative_to(root)), str((cache / "player_core.json").relative_to(root))],
    )


def import_android_shell(gh: Optional[GitHub], root: Path) -> ImportResult:
    """Record the factory's existing Android layout so nova does not duplicate it."""
    spec = SOURCES["android-shell"]
    shell: List[str] = []
    local_shell = source_root(root) / "shell" / "android_project"
    if local_shell.is_dir():
        shell = sorted(
            str(p.relative_to(source_root(root))) for p in local_shell.rglob("*") if p.is_file()
        )
    elif gh is not None:
        try:
            tree = gh.tree(spec["repo"])
            shell = [
                str(entry.get("path", ""))
                for entry in tree
                if isinstance(entry, dict) and str(entry.get("path", "")).startswith("shell/android_project/")
            ]
        except GitHubError as error:
            return ImportResult("android-shell", False, f"could not read tree: {error}")
    else:
        return ImportResult("android-shell", False, "no local shell tree and no GitHub client")
    cache = root / "python" / "nova" / "imported"
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / "android_shell.json"
    path.write_text(json.dumps({"note": "built by .github/workflows/android-build.yml", "paths": shell}, indent=2), "utf-8")
    return ImportResult("android-shell", True, f"{len(shell)} paths recorded", written=[str(path.relative_to(root))])


def run_all(gh: Optional[GitHub], root: Path) -> List[ImportResult]:
    return [import_player_core(gh, root), import_android_shell(gh, root)]
