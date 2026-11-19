"""Audit the Android app for defects that compile cleanly but ship broken.

A build proves syntax, not behaviour. Everything this module looks for compiles
happily and still produces a worse app: a player that is never constructed, a
state field written and never read, an async call with no failure path, a
button wired to a lambda that does nothing.

Each finding is evidence a person can check -- a file, a line, and the exact
text -- because an improvement list nobody can verify is just a suggestion.

Deliberately conservative: a false "you should delete this" is worse than a
missed one, so anything referenced from XML, reflection or a string name is
treated as used.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

# A finding's severity drives the order of the improvement plan.
CRITICAL = "critical"   # the app cannot do the thing it appears to offer
HIGH = "high"           # a user will hit this
MEDIUM = "medium"       # a quality problem
LOW = "low"             # tidiness

_SEVERITY_ORDER = {CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3}


@dataclass
class Finding:
    """One problem, with enough evidence to act on without re-reading the code."""

    kind: str
    severity: str
    file: str
    line: int
    symbol: str
    message: str
    suggestion: str

    def as_dict(self) -> Dict[str, object]:
        return {
            "kind": self.kind,
            "severity": self.severity,
            "file": self.file,
            "line": self.line,
            "symbol": self.symbol,
            "message": self.message,
            "suggestion": self.suggestion,
        }

    def __str__(self) -> str:
        return f"[{self.severity}] {self.file}:{self.line} {self.symbol} - {self.message}"


def kotlin_files(root: Path) -> List[Path]:
    """Every .kt file under the app, main and test sources alike.

    Accepts either the nova-tv root or the android root, because both were being
    passed in different places and the mismatch reported "0 scanned" on a project
    with 17 files. One function, either anchor, no silent zero.
    """
    candidates = [root / "android" / "app" / "src", root / "app" / "src"]
    for app in candidates:
        if app.is_dir():
            return sorted(p for p in app.rglob("*.kt") if p.is_file())
    return []


def _scan_kotlin(text: str, keep_strings: bool) -> str:
    """Single pass over Kotlin source that drops comments.

    Strings are scanned *before* comment markers can match, so a literal such as
    "https://host/x" is not mistaken for a `//` comment (the regex approach cut
    the rest of the line, then mis-paired every later quote). With
    keep_strings=False, literal text is blanked to "" but `${...}` template
    expressions are kept, because the identifiers inside them are real uses.
    Nested block comments are handled, as Kotlin allows them.
    """
    out: list[str] = []
    i, n = 0, len(text)

    def template(j: int) -> int:
        # text[j:j+2] == "${"; copy the balanced expression, return index after it.
        depth, k = 0, j + 1
        while k < n:
            ch = text[k]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    if not keep_strings:
                        out.append(" " + _scan_kotlin(text[j + 2:k], keep_strings) + " ")
                    return k + 1
            elif ch == '"':
                # nested string inside the template
                end = text.find('"', k + 1)
                k = end if end != -1 else n
            k += 1
        return n

    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    if text[i] == "\n":
                        out.append("\n")
                    i += 1
            out.append(" ")
        elif text.startswith('"""', i):
            j = i + 3
            start = len(out)
            while j < n and not text.startswith('"""', j):
                if text.startswith("${", j):
                    j = template(j)
                    continue
                if text[j] == "\n" and not keep_strings:
                    out.append("\n")
                j += 1
            while j + 3 < n and text[j + 3] == '"':  # """...""""  -> trailing quote belongs to the string
                j += 1
            end = min(j + 3, n)
            if keep_strings:
                del out[start:]
                out.append(text[i:end])
            else:
                out.insert(start, '""')
            i = end
        elif c == '"':
            j = i + 1
            start = len(out)
            while j < n and text[j] != '"' and text[j] != "\n":
                if text[j] == "\\":
                    j += 2
                    continue
                if text.startswith("${", j):
                    j = template(j)
                    continue
                j += 1
            end = min(j + 1, n)
            if keep_strings:
                del out[start:]
                out.append(text[i:end])
            else:
                out.insert(start, '""')
            i = end
        elif c == "'":
            # char literal: '{', '\n', '\u0041'
            j = text.find("'", i + 1 + (1 if text[i + 1:i + 2] == "\\" else 0))
            if j != -1 and j - i <= 8:
                out.append(text[i:j + 1] if keep_strings else "' '")
                i = j + 1
            else:
                out.append(c)
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _strip_comments(text: str) -> str:
    """Remove // and /* */ comments so commented-out code is not counted as use.

    String literals are kept intact (callers match on their contents).
    """
    return _scan_kotlin(text, keep_strings=True)


def _relative(root: Path, path: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


# ---------------------------------------------------------------------------
# Declarations and references
# ---------------------------------------------------------------------------

# `class Foo`, `object Foo`, `interface Foo`, `enum class Foo`
_DECL = re.compile(
    r"^\s*(?:(?:public|internal|private|abstract|open|sealed|data|enum|inner|value|annotation)\s+)*"
    r"(class|object|interface)\s+([A-Z][A-Za-z0-9_]*)",
    re.MULTILINE,
)
# `fun name(` including extension receivers and generics
_FUNC = re.compile(
    r"^\s*(?:(?:public|internal|private|override|suspend|inline|operator|open|abstract)\s+)*"
    r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?]+\.)?([a-z][A-Za-z0-9_]*)\s*\(",
    re.MULTILINE,
)


def declarations(sources: Dict[str, str]) -> List[Tuple[str, str, int, str]]:
    """(file, name, line, kind) for every top-level type declared."""
    found: List[Tuple[str, str, int, str]] = []
    for path, text in sources.items():
        clean = _strip_comments(text)
        for match in _DECL.finditer(clean):
            line = clean[: match.start()].count("\n") + 1
            found.append((path, match.group(2), line, match.group(1)))
    return found


def find_dead_types(root: Path, sources: Dict[str, str]) -> List[Finding]:
    """Types declared but never named anywhere else.

    The check that would have caught a player class that nothing constructs: it
    compiles, the tests pass, and the app has no playback.
    """
    findings: List[Finding] = []
    clean_all = {p: _strip_comments(t) for p, t in sources.items()}
    # Names mentioned in resources or the manifest count as use (reflection,
    # AndroidManifest android:name, layout references).
    external = ""
    # `root` is the android directory itself (see collect()); appending "android"
    # again silently produced a path that never exists, which made every
    # manifest-declared activity look unreferenced.
    res = root / "app" / "src" / "main"
    for extra in ("AndroidManifest.xml",):
        f = res / extra
        if f.is_file():
            external += f.read_text(errors="ignore")
    for xml in res.rglob("*.xml") if res.is_dir() else []:
        external += xml.read_text(errors="ignore")

    for path, name, line, kind in declarations(clean_all):
        # JUnit discovers test classes by reflection, so nothing names them.
        if "/test/" in path.replace("\\", "/") or name.endswith("Test"):
            continue
        # Count uses, discounting exactly the declaration occurrence.
        #
        # Discounting "one per own file" was wrong: an object whose own body
        # references a sibling (`const val TAP = ImportedSkipSeconds.TAP`) counts
        # as used, and subtracting a blanket 1 hid that. Removing the declared
        # name from the declaration line only is precise.
        uses = 0
        for other, text in clean_all.items():
            occurrences = len(re.findall(rf"\b{re.escape(name)}\b", text))
            if other == path:
                occurrences -= 1  # the `class X` / `object X` line itself
            uses += max(occurrences, 0)
        # AndroidManifest names activities relative to the package (".ui.NovaActivity"),
        # so the declared name appears as a suffix rather than a standalone word.
        if re.search(rf"(?<![A-Za-z0-9_]){re.escape(name)}(?![A-Za-z0-9_])", external):
            uses += 1
        if uses == 0:
            findings.append(
                Finding(
                    kind="dead-type",
                    severity=HIGH if kind in ("class", "object") else MEDIUM,
                    file=path,
                    line=line,
                    symbol=name,
                    message=f"{kind} {name} is declared and never used anywhere",
                    suggestion=(
                        "Wire it into the UI, or delete it. Unused code that looks "
                        "like a feature is how an app appears more complete than it is."
                    ),
                )
            )
    return findings


# ---------------------------------------------------------------------------
# State written but never read
# ---------------------------------------------------------------------------

_ASSIGN = re.compile(r"\b(\w+)\s*=\s*[^=\n]")


def find_write_only_properties(sources: Dict[str, str]) -> List[Finding]:
    """A state property that is assigned and never read.

    The distinguishing case: `val opened` is set by the ViewModel and no screen
    reads it, so the interaction it belongs to is a dead end.

    Counting `.name` at call sites is not enough -- `val id get() = manifest.id`
    reads `manifest` from inside its own file. So the rule is: a name is read if
    it appears anywhere that is not an assignment target. That over-counts rather
    than under-counts, which is the correct direction of error for a check that
    would otherwise tell someone to delete live code.
    """
    findings: List[Finding] = []
    prop_decl = re.compile(r"^\s*(?:val|var)\s+(\w+)\s*:", re.MULTILINE)

    # Names that are too generic to reason about without type information.
    SKIP = {"first", "second", "type", "id", "url", "name", "key", "value", "size"}

    for path, text in sources.items():
        if "/test/" in path.replace("\\", "/"):
            continue
        clean = _strip_comments(text)
        for match in prop_decl.finditer(clean):
            name = match.group(1)
            if name in SKIP:
                continue
            line = clean[: match.start()].count("\n") + 1

            reads = 0
            writes = 0
            for other, other_text in sources.items():
                body = _strip_comments(other_text)
                # Remove the declaration line before counting: `val opened:` is
                # neither a read nor a write, and leaving it in made every
                # property look read once.
                body = re.sub(
                    rf"^\s*(?:val|var)\s+{re.escape(name)}\s*:.*$",
                    "",
                    body,
                    count=1,
                    flags=re.MULTILINE,
                )
                # an assignment target: `name =`, including a named argument
                writes += len(re.findall(rf"(?<![\w.]){re.escape(name)}\s*=(?!=)", body))
                # every other mention is a read (receiver `.name`, interior use)
                reads += len(re.findall(
                    rf"(?<![A-Za-z0-9_])\.?{re.escape(name)}(?![A-Za-z0-9_\s]*=(?!=))",
                    body,
                ))
            if writes > 0 and reads <= 0:
                findings.append(
                    Finding(
                        kind="write-only-state",
                        severity=HIGH,
                        file=path,
                        line=line,
                        symbol=name,
                        message=f"state '{name}' is assigned and never read",
                        suggestion=(
                            "Something sets it and no screen shows it. Either render it, "
                            "or the interaction it belongs to is unfinished."
                        ),
                    )
                )
    return findings


# ---------------------------------------------------------------------------
# Missing failure paths
# ---------------------------------------------------------------------------

_SUSPEND_CALL = re.compile(r"\b(\w+)\s*\([^)]*\)")
_RUN_CATCHING = re.compile(r"\brunCatching\b")
_TRY = re.compile(r"\btry\s*\{")


def nova_scale_calls(name: str) -> str:
    """Matches `someModifier(stateVar)` where the helper turns it into a value.

    Focus scale, alpha and colour helpers read the state to choose an output, so
    they count as reads. A plain component pass-through such as
    `Rail(current = route)` does not, because that is how an unrouted screen
    hides.
    """
    return rf"\w*(?:[Ss]cale|[Aa]lpha|[Cc]olor|[Tt]int|[Ff]ade)\(\s*{name}\b"


def _all_clean(sources: Dict[str, str]) -> List[str]:
    """Comment-free text of every source, for cross-file call-site checks."""
    cached = getattr(_all_clean, "_cache", None)
    key = tuple(sorted(sources))
    if cached is None or getattr(_all_clean, "_key", None) != key:
        _all_clean._cache = [_strip_comments(t) for t in sources.values()]  # type: ignore[attr-defined]
        _all_clean._key = key  # type: ignore[attr-defined]
    return _all_clean._cache  # type: ignore[attr-defined]


def find_unguarded_io(sources: Dict[str, str]) -> List[Finding]:
    """A suspend call in a ViewModel-visible path with no try/runCatching nearby.

    Not a proof of a crash, which is why it is MEDIUM: it flags the places where
    a thrown exception has nowhere to go.
    """
    findings: List[Finding] = []
    for path, text in sources.items():
        if "/test/" in path or "\\test\\" in path:
            continue
        clean = _strip_comments(text)
        if not re.search(r"\bsuspend\s+fun\b", clean):
            continue
        # Find suspend functions and look for a guard inside the body.
        for match in re.finditer(r"\bsuspend\s+fun\s+(\w+)\s*\(", clean):
            name = match.group(1)
            start = match.end()
            depth = 1
            i = start
            while i < len(clean) and depth > 0:
                if clean[i] == "{":
                    depth += 1
                elif clean[i] == "}":
                    depth -= 1
                i += 1
            body = clean[start:i]
            if not body.strip():
                continue
            if _TRY.search(body) or _RUN_CATCHING.search(body):
                continue
            # A function that does not guard itself may still be guarded by every
            # caller. `loadStreams` throws by design and every call site wraps it
            # in runCatching, so reporting it as unprotected was wrong -- it
            # would have pushed a try/catch into a layer that is deliberately
            # transparent and broken the error reporting the caller relies on.
            escaped = re.escape(name)
            call_guarded = any(
                re.search(rf"(runCatching|try)\s*\{{[^}}]*\b{escaped}\s*\(", other)
                for other in _all_clean(sources)
            )
            if call_guarded:
                continue
            line = clean[: match.start()].count("\n") + 1
            findings.append(
                Finding(
                    kind="unguarded-suspend",
                    severity=MEDIUM,
                    file=path,
                    line=line,
                    symbol=name,
                    message=f"suspend fun {name} has no try/runCatching",
                    suggestion=(
                        "A thrown exception here reaches the caller with no message "
                        "for the viewer. Wrap it and surface a retry."
                    ),
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Placeholders that indicate unfinished work
# ---------------------------------------------------------------------------

_TODO = re.compile(r"\b(TODO|FIXME|XXX|HACK)\b")


def find_markers(sources: Dict[str, str]) -> List[Finding]:
    findings: List[Finding] = []
    for path, text in sources.items():
        for i, line in enumerate(text.splitlines(), start=1):
            match = _TODO.search(line)
            if match:
                findings.append(
                    Finding(
                        kind="unfinished-marker",
                        severity=LOW,
                        file=path,
                        line=i,
                        symbol=match.group(1),
                        message=f"{match.group(1)} left in the source",
                        suggestion="Resolve it or record why it is permanent.",
                    )
                )
    return findings


# ---------------------------------------------------------------------------
# UI reachability: a screen that exists but is unreachable
# ---------------------------------------------------------------------------

def find_unrouted_screens(root: Path, sources: Dict[str, str]) -> List[Finding]:
    """A `when` over routes with a branch that cannot change what is drawn.

    The specific bug this targets: `var route by remember` is reassigned by the
    side rail, but the content `when` never reads `route`, so every rail entry
    does the same thing while looking clickable.
    """
    findings: List[Finding] = []
    for path, text in sources.items():
        if "ui" not in path.replace("\\", "/"):
            continue
        clean = _strip_comments(text)
        if not re.search(r"\bvar\s+(\w+)\s+by\s+remember", clean):
            continue
        var_match = re.search(r"\bvar\s+(\w+)\s+by\s+remember", clean)
        var = var_match.group(1)
        # Is the variable ever *read to choose content*?
        #
        # Looking only inside `if (x)` / `when (x)` produced a false critical on
        # `var focused by remember`, which is read by `novaFocusScale(focused)`.
        # Passing a state variable into a modifier is a read; the earlier check
        # simply could not see it, so it reported working focus code as a dead
        # route. Reads now include: conditions, arguments, string interpolation,
        # arithmetic/comparison, and being returned.
        name = re.escape(var)
        reads = [
            # A condition is the strong signal: the variable chooses content.
            rf"(?:if|when)\s*\([^)]*\b{name}\b",
            rf"when\s*\(\s*{name}\s*\)",
            # Being passed to a function is a read, but NOT a decision. This
            # distinction matters: `Rail(current = route)` passes the route to
            # the rail, which is exactly what the old broken app did while
            # rendering the same pane for all six destinations. Counting a bare
            # pass-through as a read would have hidden the very defect this
            # check exists to find -- and did hide it, until a regression test
            # caught the widening.
            rf"=\s*[\w.]+\(\s*[^)]*\b{name}\b[^)]*\)\s*(?:\{{|$)",
            rf"\$\{{?{name}\b",                              # string template
            # Arithmetic and comparison, but never a plain assignment:
            # `route = it` writes the variable, and counting a write as a read
            # is precisely how an unrouted screen looks fine to a checker.
            rf"\b{name}\s*(?:[+\-*/<]|[=!]=|&&|\|\|)",
            rf"(?:[+\-*/<]|[=!]=)\s*{name}\b",
            rf"[<>]\s*{name}\b",
            rf"\b{name}\s*>\s*",
            rf"return\s+{name}\b",                            # returned
            # Consumed by a composable that branches on it: novaFocusScale(focused).
            rf"\b{nova_scale_calls(name)}",
        ]
        read_in_condition = any(re.search(pattern, clean) for pattern in reads)
        if not read_in_condition:
            line = clean[: var_match.start()].count("\n") + 1
            findings.append(
                Finding(
                    kind="dead-route",
                    severity=CRITICAL,
                    file=path,
                    line=line,
                    symbol=var,
                    message=f"'{var}' is changed by the UI but never read to choose content",
                    suggestion=(
                        "Every control that sets it looks active and does nothing. "
                        "Branch the content on it, or remove the controls."
                    ),
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Run everything
# ---------------------------------------------------------------------------

def collect(root: Path) -> Dict[str, str]:
    """Read every .kt file under `root`.

    `root` here is the directory that directly contains `app/src` (the `android`
    directory), which is what `audit()` is given. It is deliberately not the
    same anchor as `kotlin_files`, which expects the nova-tv root; conflating the
    two is what made an earlier version scan nothing and report a clean app.
    """
    src = root / "app" / "src"
    if not src.is_dir():
        return {}
    return {
        _relative(root, p): p.read_text(errors="ignore")
        for p in sorted(src.rglob("*.kt"))
        if p.is_file()
    }


def audit(root: Path) -> List[Finding]:
    """All findings, ordered by severity then file then line."""
    sources = collect(root)
    if not sources:
        return []
    findings: List[Finding] = []
    findings += find_dead_types(root, sources)
    findings += find_write_only_properties(sources)
    findings += find_unrouted_screens(root, sources)
    findings += find_unguarded_io(sources)
    findings += find_markers(sources)
    # A type can be reported as dead and as a dead route; keep the worse one.
    seen: Set[Tuple[str, str]] = set()
    unique: List[Finding] = []
    for f in findings:
        key = (f.file, f.symbol)
        if key in seen:
            continue
        seen.add(key)
        unique.append(f)
    unique.sort(key=lambda f: (_SEVERITY_ORDER.get(f.severity, 9), f.file, f.line))
    return unique


def summarise(findings: Sequence[Finding]) -> Dict[str, object]:
    counts = Counter(f.severity for f in findings)
    by_kind = Counter(f.kind for f in findings)
    return {
        "total": len(findings),
        "by_severity": {s: counts.get(s, 0) for s in (CRITICAL, HIGH, MEDIUM, LOW)},
        "by_kind": dict(by_kind),
        "worst": findings[0].as_dict() if findings else None,
    }


def plan(findings: Sequence[Finding], *, limit: int = 10) -> List[str]:
    """A numbered plan, worst first, each step tied to evidence."""
    steps: List[str] = []
    for i, f in enumerate(findings[:limit], start=1):
        steps.append(
            f"{i}. [{f.severity}] {f.suggestion} -- {f.file}:{f.line} ({f.symbol})"
        )
    return steps
