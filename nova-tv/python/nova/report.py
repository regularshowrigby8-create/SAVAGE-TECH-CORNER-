"""Output helpers shared by the reporting scripts."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterable, List

from .config import ROOT

REPORTS = ROOT / "reports"


def ensure_dir() -> Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    return REPORTS


def write_json(name: str, payload: Any) -> Path:
    directory = ensure_dir()
    path = directory / name
    path.write_text(json.dumps(payload, indent=2, sort_keys=True, default=str), "utf-8")
    return path


def read_json(name: str, default: Any = None) -> Any:
    path = REPORTS / name
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, ValueError):
        return default


def table(rows: Iterable[Iterable[Any]], headers: List[str]) -> str:
    """Fixed-width ASCII table — readable in a terminal and in CI logs."""
    materialised = [[str(cell) for cell in row] for row in rows]
    widths = [len(header) for header in headers]
    for row in materialised:
        for index, cell in enumerate(row):
            if index < len(widths):
                widths[index] = max(widths[index], len(cell))
    line = "  ".join(header.ljust(widths[i]) for i, header in enumerate(headers))
    rule = "  ".join("-" * width for width in widths)
    body = "\n".join(
        "  ".join((row[i] if i < len(row) else "").ljust(widths[i]) for i in range(len(headers)))
        for row in materialised
    )
    return "\n".join(part for part in (line, rule, body) if part)


def status_glyph(ok: bool) -> str:
    return "PASS" if ok else "FAIL"


def summary(payload: Dict[str, Any]) -> str:
    checks = payload.get("checks") or []
    if not checks:
        return "no checks ran"
    passed = sum(1 for check in checks if check.get("ok"))
    return f"{passed}/{len(checks)} checks passed"
