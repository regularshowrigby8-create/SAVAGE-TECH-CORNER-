"""The self-improvement loop.

A proposal pipeline, not an auto-committer. Autonomy is bounded on purpose:

  1. it may only *write* to files under a declared sandbox set
  2. it may never touch secrets, CI config, or its own guards
  3. every change must survive the test suite before it can be proposed
  4. nothing is ever pushed without an explicit ``--apply`` and a clean tree
  5. a rollback point is written before anything changes

Step 5 matters most: an autonomous loop that cannot undo itself is a liability,
so ``Snapshot`` captures every file it is about to touch and can restore it.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

# Paths the loop is never allowed to modify, even with --apply.
FORBIDDEN = (
    "nova/selfimprove.py",   # its own guard
    "nova/config.py",        # secret handling
    ".git/",
    ".github/",
    "nova.toml",
    ".env",
    "models.json",           # endpoint configuration
)

ALLOWED_SUFFIXES = (".py", ".md", ".json", ".kt", ".kts")


class GuardError(RuntimeError):
    """A proposed change violated a safety rule."""


FORBIDDEN_PREFIXES = (".github/", ".git/")


def normalize(path: str) -> str:
    """Canonicalise a repo-relative path without destroying leading dots.

    ``str.lstrip("./")`` removes *any* leading character in that set, so
    ``.github/x`` became ``github/x`` and escaped the guard entirely. This
    resolves the path properly instead, and rejects anything that climbs out of
    the repository.
    """
    raw = path.replace("\\", "/").strip()
    if not raw:
        raise GuardError("empty path")
    if raw.startswith("/"):
        raise GuardError(f"absolute path rejected: {path}")
    parts = []
    for part in raw.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            raise GuardError(f"path traversal rejected: {path}")
        parts.append(part)
    normalized = "/".join(parts)
    if not normalized:
        raise GuardError(f"path resolves to nothing: {path}")
    return normalized


def is_forbidden(path: str) -> bool:
    """True when the path must never be written by the loop."""
    try:
        normalized = normalize(path)
    except GuardError:
        return True  # unusable paths are treated as forbidden, never as allowed
    if any(normalized.startswith(prefix) for prefix in FORBIDDEN_PREFIXES):
        return True
    return normalized in FORBIDDEN or any(normalized.startswith(bad) for bad in FORBIDDEN if bad.endswith("/"))


def is_allowed(path: str) -> bool:
    if is_forbidden(path):
        return False
    return path.endswith(ALLOWED_SUFFIXES)


@dataclass
class Snapshot:
    """Every byte the loop is about to touch, so it can always go back."""

    root: Path
    stamp: str = field(default_factory=lambda: time.strftime("%Y%m%d-%H%M%S"))
    files: Dict[str, str] = field(default_factory=dict)

    @property
    def directory(self) -> Path:
        return self.root / ".nova-snapshots" / self.stamp

    def capture(self, relative: str) -> None:
        if is_forbidden(relative):
            raise GuardError(f"refusing to snapshot forbidden path {relative}")
        source = self.root / relative
        if not source.exists():
            return
        destination = self.directory / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        self.files[relative] = hashlib.sha256(source.read_bytes()).hexdigest()

    def restore(self) -> List[str]:
        restored = []
        for relative in self.files:
            source = self.directory / relative
            if source.exists():
                target = self.root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                restored.append(relative)
        return restored

    def manifest(self) -> Path:
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self.directory / "manifest.json"
        path.write_text(json.dumps({"stamp": self.stamp, "files": self.files}, indent=2), "utf-8")
        return path


@dataclass
class Proposal:
    path: str
    original: str
    proposed: str
    reason: str
    tests_passed: bool = False
    review: str = ""

    def diff(self) -> str:
        """Unified diff, produced without shelling out to anything."""
        import difflib

        return "".join(
            difflib.unified_diff(
                self.original.splitlines(keepends=True),
                self.proposed.splitlines(keepends=True),
                fromfile=f"a/{self.path}",
                tofile=f"b/{self.path}",
            )
        )

    def validate(self) -> None:
        if not is_allowed(self.path):
            raise GuardError(f"{self.path} is outside the writable sandbox")
        if not self.proposed.strip():
            raise GuardError("refusing to replace a file with empty content")
        if len(self.proposed) > 2_000_000:
            raise GuardError("proposed content is implausibly large")
        if self.proposed == self.original:
            raise GuardError("proposal is a no-op")


def run_tests(root: Path, extra_args: Sequence[str] = ("-b", "quiet")) -> tuple[bool, str]:
    """Run the project's unittest suite in a subprocess."""
    command = [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."]
    # The Python project lives at nova-tv/python, while config.ROOT is the
    # nova-tv checkout. Running `unittest discover` from ROOT used to fail with
    # "Start directory is not importable: 'tests'" and qa_tests reported
    # passed=false while the CLI still returned zero. Resolve the real project
    # directory instead of guessing a working directory.
    project = root
    if not (root / "tests").is_dir() and (root / "python" / "tests").is_dir():
        project = root / "python"
    try:
        result = subprocess.run(
            command,
            cwd=str(project),
            capture_output=True,
            text=True,
            timeout=600,
        )
    except subprocess.TimeoutExpired:
        return False, "test run timed out"
    output = (result.stdout or "") + (result.stderr or "")
    return result.returncode == 0, output[-4000:]


def git_is_clean(root: Path) -> bool:
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(root), capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0 and not result.stdout.strip()


def git_head(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=str(root), capture_output=True, text=True, timeout=60
        )
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    return result.stdout.strip() or "unknown"


def summarise(proposals: Sequence[Proposal]) -> Dict[str, Any]:
    return {
        "count": len(proposals),
        "valid": [p.path for p in proposals if p.tests_passed],
        "blocked": [p.path for p in proposals if not p.tests_passed],
        "total_diff_lines": sum(len(p.diff().splitlines()) for p in proposals),
    }


class Loop:
    """One improvement pass, with rollback always available."""

    def __init__(self, root: Path, *, allow_apply: bool = False):
        self.root = root
        self.allow_apply = allow_apply
        self.snapshot = Snapshot(root=root)
        self.log: List[str] = []

    def record(self, message: str) -> None:
        self.log.append(message)

    def propose(self, path: str, proposed: str, reason: str) -> Proposal:
        target = self.root / path
        original = target.read_text("utf-8") if target.exists() else ""
        proposal = Proposal(path=path, original=original, proposed=proposed, reason=reason)
        proposal.validate()
        self.snapshot.capture(path)
        return proposal

    def apply(self, proposals: Sequence[Proposal]) -> List[str]:
        """Write the proposals that passed tests. Requires --apply."""
        if not self.allow_apply:
            raise GuardError("apply was not requested; re-run with --apply")
        # Decide what would actually be written first. Checking tree cleanliness
        # up front made a run with nothing to write fail for no reason, which is
        # both wrong and confusing.
        ready = [p for p in proposals if p.tests_passed]
        for proposal in proposals:
            if not proposal.tests_passed:
                self.record(f"skipped (tests not green): {proposal.path}")
        if not ready:
            self.record("nothing to write; tree state is irrelevant")
            return []
        if not git_is_clean(self.root):
            raise GuardError("working tree is dirty; commit or stash before applying")
        written = []
        for proposal in ready:
            target = self.root / proposal.path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(proposal.proposed, "utf-8")
            written.append(proposal.path)
            self.record(f"wrote {proposal.path}")
        self.snapshot.manifest()
        return written

    def rollback(self) -> List[str]:
        restored = self.snapshot.restore()
        self.record(f"restored {len(restored)} file(s)")
        return restored

    def report(self) -> Dict[str, Any]:
        return {
            "head": git_head(self.root),
            "clean": git_is_clean(self.root),
            "apply_allowed": self.allow_apply,
            "snapshot": str(self.snapshot.directory),
            "log": self.log,
        }
