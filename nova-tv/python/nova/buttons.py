"""Find buttons that look active and do nothing.

A dead button is almost never a missing onClick. It is one of five things, and
each has a distinct shape in the source:

1. **No handler at all** -- a Surface/Card drawn without onClick.
2. **Empty handler** -- `onClick = { }`.
3. **Sets state nothing renders** -- handled, but the value it writes is never
   read. This is the `route` bug: the rail works, and the screen never changes.
4. **Navigates to a route with no destination** -- a key with no branch.
5. **Calls a function whose body is empty.**

The checks are structural because a Compose UI cannot be exercised here. Each
finding carries the line and the symbol so it can be confirmed by reading one
place, and the false-positive rules are explicit: a control inside a `LazyRow`
with a shared handler, or one whose lambda delegates to a parameter, counts as
live.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from . import appaudit

# A clickable container. `Surface` and `Card` are only interactive when given
# onClick, which is exactly what check 1 detects.
CLICKABLE = re.compile(
    r"\b(TvSurface|Surface|Card|Button|TvButton|OutlinedButton|TextButton|"
    r"IconButton|Modifier\.clickable|combinedClickable)\s*[({\[]"
)
ON_CLICK = re.compile(r"\bonClick\s*=\s*(\{[^}]*\}|[A-Za-z_][\w.:]*)", re.DOTALL)


@dataclass
class DeadButton:
    kind: str
    file: str
    line: int
    symbol: str
    message: str
    fix: str

    def as_dict(self) -> Dict[str, Any]:
        return {
            "kind": self.kind,
            "file": self.file,
            "line": self.line,
            "symbol": self.symbol,
            "message": self.message,
            "fix": self.fix,
        }

    def __str__(self) -> str:
        return f"[{self.kind}] {self.file}:{self.line} {self.symbol} - {self.message}"


def _ui_files(root: Path) -> List[Path]:
    return [p for p in appaudit.kotlin_files(root) if "/ui/" in str(p).replace("\\", "/")]


def _empty_body(body: str) -> bool:
    stripped = re.sub(r"\s+", "", body)
    return stripped in ("{}", "{;}")


def find_empty_handlers(root: Path) -> List[DeadButton]:
    """`onClick = { }` and handlers whose body does nothing."""
    findings: List[DeadButton] = []
    for path in _ui_files(root):
        text = appaudit._strip_comments(path.read_text(errors="ignore"))
        for match in ON_CLICK.finditer(text):
            handler = match.group(1)
            if handler.startswith("{") and _empty_body(handler):
                line = text[: match.start()].count("\n") + 1
                findings.append(
                    DeadButton(
                        kind="empty-handler",
                        file=appaudit._relative(root, path),
                        line=line,
                        symbol="onClick",
                        message="onClick is present but does nothing",
                        fix="Remove the control, or give it the action its label promises.",
                    )
                )
    return findings


def find_unhandled_clickables(root: Path) -> List[DeadButton]:
    """A Surface/Card with no onClick is decoration that looks pressable."""
    findings: List[DeadButton] = []
    for path in _ui_files(root):
        text = appaudit._strip_comments(path.read_text(errors="ignore"))
        for match in CLICKABLE.finditer(text):
            # Scan the balanced block that follows. `Card(` takes parentheses,
            # `Card {` takes braces, and both can wrap further nesting.
            start = match.end() - 1
            opener = text[start]
            closer = {"(": ")", "{": "}", "[": "]"}[opener]
            depth = 0
            i = start
            while i < len(text):
                if text[i] == opener:
                    depth += 1
                elif text[i] == closer:
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            args = text[start : i + 1]
            if "onClick" in args or "clickable" in match.group(1):
                continue
            # A Surface used purely as a background with no onClick and no
            # label is legitimate decoration; only flag it when it wraps text.
            if "Text(" not in args and "content" not in args:
                continue
            line = text[: match.start()].count("\n") + 1
            findings.append(
                DeadButton(
                    kind="no-handler",
                    file=appaudit._relative(root, path),
                    line=line,
                    symbol=match.group(1),
                    message=f"{match.group(1)} renders content but takes no click",
                    fix="Add an onClick, or render it as plain content so it does not look pressable.",
                )
            )
    return findings


def find_no_op_functions(root: Path) -> List[DeadButton]:
    """A `fun` whose whole body is empty or only a comment."""
    findings: List[DeadButton] = []
    for path in _ui_files(root) + [p for p in appaudit.kotlin_files(root) if "/repo/" in str(p)]:
        text = appaudit._strip_comments(path.read_text(errors="ignore"))
        for match in re.finditer(r"\bfun\s+(\w+)\s*\([^)]*\)\s*(?::\s*[\w<>?.]+)?\s*\{", text):
            start = match.end() - 1
            depth = 0
            i = start
            while i < len(text):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            body = text[start + 1 : i]
            if not _empty_body("{" + body + "}") and body.strip():
                continue
            line = text[: match.start()].count("\n") + 1
            findings.append(
                DeadButton(
                    kind="no-op-function",
                    file=appaudit._relative(root, path),
                    line=line,
                    symbol=match.group(1),
                    message=f"fun {match.group(1)} has an empty body",
                    fix="Implement it or delete the call site that pretends it works.",
                )
            )
    return findings


def find_unrouted_entries(root: Path) -> List[DeadButton]:
    """Routes a rail can select, in a screen that never branches on the route.

    Every one of these renders the same pane, so all of them are dead buttons --
    the worst case, because they look the most alive.
    """
    findings: List[DeadButton] = []
    for path in _ui_files(root):
        text = appaudit._strip_comments(path.read_text(errors="ignore"))
        for var in set(re.findall(r"\bvar\s+(\w+)\s+by\s+remember", text)):
            if re.search(rf"when\s*\(\s*{re.escape(var)}\s*\)", text):
                continue
            enum = re.search(r"\benum\s+class\s+(\w+)[^{]*\{", text)
            if not enum:
                continue
            start = enum.end() - 1
            depth = 0
            i = start
            while i < len(text):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            entries = re.findall(r"\b([A-Z][A-Z_0-9]*)\s*(?:\(|,|\n)", text[start:i])
            if not entries:
                continue
            line = text[: enum.start()].count("\n") + 1
            findings.append(
                DeadButton(
                    kind="unrouted-entries",
                    file=appaudit._relative(root, path),
                    line=line,
                    symbol=var,
                    message=(
                        f"{len(entries)} routes ({', '.join(entries)}) can be selected "
                        f"but nothing branches on '{var}'"
                    ),
                    fix=(
                        "Every rail entry currently shows the same pane. Branch the "
                        "content on the route, or remove the entries."
                    ),
                )
            )
    return findings


def find_dead_navigation(root: Path) -> List[DeadButton]:
    """A `when(route)` missing a branch for a route the UI can set.

    The complement of appaudit's dead-route check: that one catches a route read
    by nothing, this one catches a route read but not fully handled, which
    renders a blank pane when the viewer picks that entry.
    """
    findings: List[DeadButton] = []
    for path in _ui_files(root):
        text = appaudit._strip_comments(path.read_text(errors="ignore"))
        # route variables that are iterated over to build the rail
        for var in set(re.findall(r"\bvar\s+(\w+)\s+by\s+remember", text)):
            for enum in re.finditer(r"\benum\s+class\s+(\w+)[^{]*\{([^}]*)\}", text, re.DOTALL):
                entries = [
                    e.strip()
                    for e in re.findall(r"\b([A-Z][A-Z_0-9]*)\b", enum.group(2))
                ]
                when = re.search(rf"when\s*\(\s*{re.escape(var)}\s*\)\s*\{{", text)
                if not when:
                    continue
                start = when.end() - 1
                depth = 0
                i = start
                while i < len(text):
                    if text[i] == "{":
                        depth += 1
                    elif text[i] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    i += 1
                block = text[start:i]
                handled = set(re.findall(rf"\b{enum.group(1)}\.([A-Z][A-Z_0-9]*)\b", block))
                missing = [e for e in entries if e not in handled and "else" not in block]
                if missing:
                    line = text[: when.start()].count("\n") + 1
                    findings.append(
                        DeadButton(
                            kind="dead-destination",
                            file=appaudit._relative(root, path),
                            line=line,
                            symbol=var,
                            message=f"when({var}) has no branch for {', '.join(missing)}",
                            fix="Handle every entry the rail can select, or the pane renders empty.",
                        )
                    )
    return findings


def find_state_set_but_unrendered(root: Path) -> List[DeadButton]:
    """Reuse the audit's write-only detection, phrased as a dead button.

    If a control sets state and no screen reads it, that control is dead even
    though its handler ran.
    """
    sources = appaudit.collect(root)
    findings: List[DeadButton] = []
    for finding in appaudit.find_write_only_properties(sources):
        findings.append(
            DeadButton(
                kind="unrendered-state",
                file=finding.file,
                line=finding.line,
                symbol=finding.symbol,
                message=f"'{finding.symbol}' is set by the UI and read by nothing",
                fix="Render it, or the control that sets it is a dead button.",
            )
        )
    return findings


def audit(root: Path) -> List[DeadButton]:
    android = root / "android" if (root / "android").is_dir() else root
    findings: List[DeadButton] = []
    findings += find_empty_handlers(android)
    findings += find_unhandled_clickables(android)
    findings += find_no_op_functions(android)
    findings += find_dead_navigation(android)
    findings += find_unrouted_entries(android)
    findings += find_state_set_but_unrendered(android)

    seen: Set[Tuple[str, int, str]] = set()
    unique: List[DeadButton] = []
    for finding in findings:
        key = (finding.file, finding.line, finding.kind)
        if key in seen:
            continue
        seen.add(key)
        unique.append(finding)
    unique.sort(key=lambda f: (f.file, f.line))
    return unique


def summarise(findings: Sequence[DeadButton]) -> Dict[str, Any]:
    counts: Dict[str, int] = {}
    for finding in findings:
        counts[finding.kind] = counts.get(finding.kind, 0) + 1
    return {
        "total": len(findings),
        "by_kind": counts,
        "ok": len(findings) == 0,
    }
