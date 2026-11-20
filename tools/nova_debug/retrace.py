"""Retrace an obfuscated Nova stack trace with an R8 ``mapping.txt``.

The shipped release APKs contain no mapping file, so a crash on a device looks
like this::

    java.lang.NullPointerException
        at dev.nova.tv.finder.pool.a.b(SourceFile:12)

Without the mapping there is no honest way to say which Kotlin line that is.
Rather than guess, this module decodes the trace *only* when a mapping file is
supplied, and refuses with a clear message otherwise. ``--fetch-mapping``
downloads the artefact that CI uploads for a run, if one exists.

Mapping format (R8/ProGuard)::

    dev.nova.tv.finder.pool.ServerPool -> a:
        java.lang.String baseUrl -> a
        int capacity -> b
        12:34:void add(dev.nova.tv.finder.FinderCandidate) -> a
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

FRAME = re.compile(r"^\s*at\s+([\w.$]+)\.([\w$<>]+)\((?:([\w.]+):(\d+)|Native Method|Unknown Source)\)")


@dataclass
class ClassMap:
    original: str
    obfuscated: str
    methods: Dict[Tuple[str, str], str] = field(default_factory=dict)  # (obf name, obf ret) -> orig name
    lines: List[Tuple[int, int, str]] = field(default_factory=list)    # (start, end, original method)


@dataclass
class Mapping:
    classes: Dict[str, ClassMap] = field(default_factory=dict)  # obfuscated -> ClassMap

    @classmethod
    def load(cls, path: Path) -> "Mapping":
        out = cls()
        current: Optional[ClassMap] = None
        for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if not raw.strip():
                continue
            if " -> " in raw and not raw.startswith(" "):
                original, obf = raw.split(" -> ", 1)
                current = ClassMap(original=original.strip(), obfuscated=obf.rstrip(":").strip())
                out.classes[current.obfuscated] = current
                continue
            if current is None:
                continue
            line = raw.strip()
            m = re.match(r"^(\d+):(\d+):[\w.$\[\]]+\s+([\w$<>]+)\(.*\)\s*->\s*([\w$<>]+)", line)
            if m:
                current.lines.append((int(m.group(1)), int(m.group(2)), m.group(3)))
                current.methods[(m.group(4), "")] = m.group(3)
                continue
            m = re.match(r"^[\w.$\[\]]+\s+([\w$]+)\s*->\s*([\w$]+)", line)
            if m:
                current.methods[(m.group(2), "")] = m.group(1)
        return out

    def resolve(self, obf_class: str, obf_method: str, line: Optional[int]) -> Tuple[str, str, int]:
        cm = self.classes.get(obf_class)
        if cm is None:
            return obf_class, obf_method, line or 0
        method = cm.methods.get((obf_method, ""), obf_method)
        if line is not None:
            for start, end, name in cm.lines:
                if start <= line <= end:
                    method = name
                    break
        return cm.original, method, line or 0


def retrace(trace: str, mapping: Mapping) -> str:
    out: List[str] = []
    for raw in trace.splitlines():
        m = FRAME.match(raw)
        if not m:
            out.append(raw)
            continue
        cls, method, _file, line = m.group(1), m.group(2), m.group(3), m.group(4)
        original, name, resolved = mapping.resolve(cls, method, int(line) if line else None)
        out.append(f"    at {original}.{name}({Path(original.replace('.', '/')).name}.kt:{resolved})")
    return "\n".join(out)


def fetch_mapping(repo: str, artifact_name: str, dest: Path) -> str:
    """Download CI's mapping artefact for the latest successful run.

    Returns a human-readable status. Raises nothing: a missing artefact is a
    fact to report, not a crash.
    """
    try:
        runs = subprocess.run(
            ["gh", "run", "list", "--workflow", "nova-tv Android", "--status", "success",
             "--limit", "1", "--json", "databaseId", "-R", repo],
            capture_output=True, text=True, timeout=60, check=True,
        )
        data = json.loads(runs.stdout or "[]")
        if not data:
            return "no successful 'nova-tv Android' run found"
        run_id = data[0]["databaseId"]
        dest.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["gh", "run", "download", str(run_id), "-R", repo, "-n", artifact_name, "-D", str(dest)],
            capture_output=True, text=True, timeout=180, check=True,
        )
        found = list(dest.rglob("mapping.txt"))
        if found:
            return f"mapping downloaded: {found[0]}"
        return f"artifact {artifact_name!r} did not contain mapping.txt"
    except subprocess.CalledProcessError as error:
        return f"gh failed: {(error.stderr or error.stdout or '').strip()[:200]}"
    except Exception as error:  # network, timeouts, missing gh
        return f"could not fetch mapping ({type(error).__name__}: {error})"
