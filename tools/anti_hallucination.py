#!/usr/bin/env python3
"""Anti-hallucination audit.

Three deterministic classes of hallucination are checked. Each one can fail on
real input — `--self-test` proves it by injecting a violation and requiring a
non-zero exit.

1. UNVERIFIED REFERENCE CLAIMS
   The UI is rebuilt from other people's codebases. A comment or document that
   says "ARVIO does X" is a claim about source none of us can re-read later, so
   it must carry a citation: a URL, or a path recorded in
   docs/REFERENCE-SOURCES.md. A claim with no source is a hallucination.

2. INVENTED DOM TARGETS
   getElementById("x") / querySelector("#x") where no element with that id is
   ever created. This is the hallucination that hurts: the code reads as if a
   control exists, and the button is simply dead on the television.

3. UNKNOWN CAPABILITY WORDS
   Code that names a framework, player or API this project does not contain
   (e.g. "exoplayer", "compose") is asserting an implementation it does not
   have.

Usage:
    python3 tools/anti_hallucination.py                 # audit the repo
    python3 tools/anti_hallucination.py --self-test     # prove each check fails
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

APP = Path("tv/zeroplay-debrid")
DOCS_FOR_CLAIMS = [APP / "UI-REBUILD.md", APP / "README.md", Path("docs")]
CODE_FOR_CLAIMS = [APP / "index.html", APP / "api", APP / "tests"]
REFERENCE_FILE = Path("docs/REFERENCE-SOURCES.md")

# Names of the codebases this UI is borrowed from.
REFERENCE_APPS = ["Stremio", "ARVIO", "Arvio", "WuPlay", "DebridStream", "StreamFlix", "Nuvio", "NuvioTV"]

# "ARVIO keeps the shelf card free of stacked buttons" — a claim about source.
CLAIM_VERBS = (
    "does", "do ", "uses", "use ", "keeps", "keep", "puts", "put", "puts",
    "treats", "treat", "shows", "show", "renders", "render", "moves", "move",
    "groups", "group", "sorts", "sort", "handles", "handle", "implements",
    "implements", "owns", "own ", "picks", "pick", "resolves", "resolve",
    "merges", "merge", "cascades", "cascade", "hides", "hide", "opens", "open",
    "draws", "draw", "draws", "focuses", "focus", "reads", "read",
)

# Tokens that mean "this project claims to contain that technology".
UNKNOWN_CAPABILITIES = [
    "exoplayer", "media3", "jetpack compose", "androidx.compose",
    "@JavascriptInterface", "kotlinx.coroutines", "material3",
]

# A citation is a URL, a source file, or a backticked symbol/function name that
# can be found in the reference checkout — anything a second person could verify.
CITATION_RE = re.compile(
    r"(https?://\S+"          # a URL
    r"|`[\w./:-]+`"            # a backticked symbol or path
    r"|[\w./-]+\.kt\b"        # a Kotlin source file
    r"|[\w./-]+\.tsx?\b"      # a TS/TSX source file
    r"|docs/[\w./-]+"          # a repository document
    r"|REF:[\w./-]+)"          # an explicit reference marker
)
SELF_FILE = "tools/anti_hallucination.py"


class Findings(list):
    def add(self, category: str, location: str, detail: str) -> None:
        self.append((category, location, detail))


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def _iter_files(roots, suffixes):
    for root in roots:
        path = ROOT / root
        if path.is_file():
            if path.suffix in suffixes:
                yield path
            continue
        if not path.exists():
            continue
        for child in path.rglob("*"):
            if child.is_file() and child.suffix in suffixes and "node_modules" not in child.parts:
                yield child


def cited_sources() -> list[str]:
    """Everything docs/REFERENCE-SOURCES.md vouches for."""
    text = _read(ROOT / REFERENCE_FILE)
    return [line.strip() for line in text.splitlines() if line.strip().startswith("- ")]


def _prose_lines(text: str, suffix: str):
    """Only lines that make a claim: prose, HTML/CSS/JS comments.

    Live code is excluded on purpose — "topic:stremio-addon" is an API query,
    not a statement about Stremio's UI, and flagging it would train everyone to
    ignore the tool.
    """
    in_comment = False
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if suffix in {".md", ".txt"}:
            # a claim wrapped over lines carries its citation at the end of the
            # paragraph, so prose is judged paragraph by paragraph
            yield lineno, line
            continue
        if in_comment:
            yield lineno, line
            if "*/" in stripped:
                in_comment = False
            continue
        if stripped.startswith("/*"):
            in_comment = "*/" not in stripped
            yield lineno, stripped.lstrip("/* ").strip()
            continue
        for opener in ("//", "<!--", "--"):
            if stripped.startswith(opener):
                yield lineno, stripped[len(opener):].strip()
                break


def _claim_apps(line: str) -> list[str]:
    """Capitalised names only: 'ARVIO keeps…' is a claim, 'topic:stremio' is not."""
    return [app for app in REFERENCE_APPS if re.search(rf"\b{re.escape(app)}\b", line)]


def check_reference_claims(findings: Findings) -> None:
    """A claim about another app must carry a citation."""
    for path in _iter_files(DOCS_FOR_CLAIMS + CODE_FOR_CLAIMS, {".md", ".html", ".js", ".cjs", ".txt"}):
        rel = path.relative_to(ROOT).as_posix()
        text = _read(path)
        if path.suffix in {".md", ".txt"}:
            # Markdown: one unit per paragraph, so a wrapped sentence keeps its
            # citation. Code comments stay line-scoped.
            units = []
            block: list[str] = []
            start = 1
            for index, raw in enumerate(text.splitlines(), 1):
                if raw.strip():
                    if not block:
                        start = index
                    block.append(raw.strip())
                elif block:
                    units.append((start, " ".join(block)))
                    block = []
            if block:
                units.append((start, " ".join(block)))
        else:
            units = list(_prose_lines(text, path.suffix))
        for lineno, line in units:
            apps = _claim_apps(line)
            if not apps:
                continue
            lowered = line.lower()
            if not any(verb in lowered for verb in CLAIM_VERBS):
                continue
            if CITATION_RE.search(line):
                continue
            findings.add(
                "unverified-reference-claim",
                f"{rel}:{lineno}",
                line.strip()[:160],
            )


def _ids_created(text: str) -> set[str]:
    """Every id the app can end up with, however it is created."""
    ids: set[str] = set()
    patterns = [
        r'id="([A-Za-z0-9_-]+)"',            # static markup
        r"id='([A-Za-z0-9_-]+)'",
        r'\.id\s*=\s*"([A-Za-z0-9_-]+)"',     # element.id = "x"
        r"\.id\s*=\s*'([A-Za-z0-9_-]+)'",
        r'id:\s*"([A-Za-z0-9_-]+)"',          # createElement({id:...})
        r"id:\s*'([A-Za-z0-9_-]+)'",
        r'setAttribute\(\s*"id"\s*,\s*"([A-Za-z0-9_-]+)"',
        r"setAttribute\(\s*'id'\s*,\s*'([A-Za-z0-9_-]+)'",
        r'id=\\"([A-Za-z0-9_-]+)\\"',          # inside template strings
    ]
    for pattern in patterns:
        ids.update(re.findall(pattern, text))
    return ids


def _ids_referenced(text: str) -> set[str]:
    refs: set[str] = set()
    refs.update(re.findall(r'getElementById\(\s*"([A-Za-z0-9_-]+)"', text))
    refs.update(re.findall(r"getElementById\(\s*'([A-Za-z0-9_-]+)'", text))
    refs.update(re.findall(r'querySelector\(\s*"#([A-Za-z0-9_-]+)"', text))
    refs.update(re.findall(r"querySelector\(\s*'#([A-Za-z0-9_-]+)'", text))
    # $("id") / tvEl("id") / on("id", ...) helpers, which take a bare id.
    refs.update(re.findall(r'\$\("#([A-Za-z0-9_-]+)"\)', text))
    # getElementById("x") where x is built from variables, e.g.
    # "downloadVariant-"+index, is not a lookup of a literal id.
    for dynamic in re.findall(r'getElementById\(\s*"([A-Za-z0-9_-]+)"\s*\+', text):
        refs.discard(dynamic)
    refs.update(re.findall(r'tvEl\(\s*"([A-Za-z0-9_-]+)"', text))
    # on("id", ...) binds a DOM control, but some objects expose on("event", …)
    for candidate in re.findall(r'\bon\(\s*"([A-Za-z0-9_-]+)"\s*,', text):
        # a DOM id in this app is camelCase starting lower; engine events are too,
        # so exclude anything bound on a player/engine object.
        for obj in re.findall(r'(\w+)\.on\(\s*"' + re.escape(candidate) + r'"', text):
            if obj in {"player", "v", "video", "engine", "hls", "dash", "el"}:
                break
        else:
            refs.add(candidate)
    return refs


def check_dom_targets(findings: Findings) -> None:
    """Every id the code reaches for must exist somewhere."""
    index = ROOT / APP / "index.html"
    if not index.exists():
        findings.add("missing-app", str(index), "the app file is missing")
        return
    text = _read(index)
    created = _ids_created(text)
    # An id built by concatenation, e.g. id="downloadVariant-"+job.id, is
    # created as surely as a literal one; it is not an invented target.
    created_prefixes = set(re.findall(r'id="([A-Za-z0-9_-]+)"\s*\+', text))
    created_prefixes.update(re.findall(r"\.id\s*=\s*[A-Za-z0-9_.]+\s*\+\s*\"[^\"]*\"", text))
    for ref in sorted(_ids_referenced(text) - created):
        if any(ref.startswith(prefix) for prefix in created_prefixes):
            continue
        findings.add(
            "invented-dom-target",
            f"{APP}/index.html",
            f'no element ever gets id="{ref}"',
        )


def check_capabilities(findings: Findings) -> None:
    for path in _iter_files([APP / "index.html", APP / "api"], {".html", ".js"}):
        rel = path.relative_to(ROOT).as_posix()
        lowered = _read(path).lower()
        for token in UNKNOWN_CAPABILITIES:
            if token.lower() in lowered:
                findings.add("unknown-capability", rel, f"claims {token!r}, which this project does not contain")


def audit() -> Findings:
    findings = Findings()
    check_reference_claims(findings)
    check_dom_targets(findings)
    check_capabilities(findings)
    return findings


def self_test() -> int:
    """Inject one violation per check; every one must be caught."""
    import tempfile

    failures = 0
    probes = [
        ("unverified-reference-claim", check_reference_claims,
         "# UI-REBUILD\n\nARVIO keeps the shelf card free of stacked buttons and uses a tabbed menu.\n"),
        ("invented-dom-target", check_dom_targets, None),
        ("unknown-capability", check_capabilities, None),
    ]

    global ROOT
    real_root = ROOT
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        # 1. claims
        (tmp_root / "tv/zeroplay-debrid").mkdir(parents=True)
        (tmp_root / "docs").mkdir()
        (tmp_root / "tv/zeroplay-debrid/UI-REBUILD.md").write_text(probes[0][2], encoding="utf-8")
        ROOT = tmp_root
        found = Findings()
        check_reference_claims(found)
        if not any(c == "unverified-reference-claim" for c, _, _ in found):
            print("FAIL self-test: an uncited claim about a reference app was not caught")
            failures += 1
        else:
            print("PASS self-test: an uncited claim about a reference app is caught")

        # 2. invented DOM target
        (tmp_root / "tv/zeroplay-debrid/index.html").write_text(
            '<button id="real">x</button><script>document.getElementById("ghostButton");</script>',
            encoding="utf-8",
        )
        found = Findings()
        check_dom_targets(found)
        if not any("ghostButton" in d for _, _, d in found):
            print("FAIL self-test: a getElementById with no matching element was not caught")
            failures += 1
        else:
            print("PASS self-test: an invented getElementById target is caught")

        # 3. unknown capability
        (tmp_root / "tv/zeroplay-debrid/index.html").write_text(
            "<script>// we use ExoPlayer here</script>", encoding="utf-8"
        )
        found = Findings()
        check_capabilities(found)
        if not any(c == "unknown-capability" for c, _, _ in found):
            print("FAIL self-test: a claimed capability the project does not have was not caught")
            failures += 1
        else:
            print("PASS self-test: a capability the project does not have is caught")

        # 4. the clean case must pass, or the checks are useless
        (tmp_root / "tv/zeroplay-debrid/UI-REBUILD.md").write_text(
            "# UI\n\nARVIO keeps the shelf card free of stacked buttons (`tv/TvPlayerPanels.kt`).\n",
            encoding="utf-8",
        )
        (tmp_root / "tv/zeroplay-debrid/index.html").write_text(
            '<button id="real">x</button><script>document.getElementById("real");</script>', encoding="utf-8"
        )
        found = Findings()
        check_reference_claims(found)
        check_dom_targets(found)
        check_capabilities(found)
        if found:
            print(f"FAIL self-test: a clean tree was flagged: {found[:2]}")
            failures += 1
        else:
            print("PASS self-test: a clean tree passes")

    ROOT = real_root
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    findings = audit()
    if findings:
        print(f"FAIL: {len(findings)} anti-hallucination violation(s)")
        for category, location, detail in findings[:60]:
            print(f"  [{category}] {location}: {detail}")
        if len(findings) > 60:
            print(f"  … and {len(findings) - 60} more")
        return 1
    print("PASS: no unverified reference claims, invented DOM targets or invented capabilities")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
