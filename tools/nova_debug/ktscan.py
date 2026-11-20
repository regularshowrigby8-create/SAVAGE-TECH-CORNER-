"""A small Kotlin scanner: enough structure to audit, not a compiler.

It exists because every "dead code / dead button / false status" rule needs the
same three things and nothing more:

  1. a token stream that is not fooled by strings, raw strings, string
     templates, char literals, nested block comments or generics;
  2. bracket pairs, so a function or class body is a real span rather than a
     regex guess;
  3. declarations (class / object / fun / val / var / import / package) with
     line numbers and modifiers.

What it deliberately does not do: type inference, expression analysis, generics
resolution. Rules built on it must therefore stay structural -- "this lambda is
empty", "this identifier is never declared" -- and must never claim to know a
type. Anything it cannot decide is reported as a warning, never as an error.

The scanner is tested against planted defects and against the Nova corpus in
``verify_no_false_green.py``; a rule that fires on correct code is a bug in the
rule, not a finding.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

KIND_ID = "id"
KIND_STR = "str"
KIND_NUM = "num"
KIND_PUNC = "punc"

OPEN = {"(": ")", "[": "]", "{": "}"}
CLOSE = {v: k for k, v in OPEN.items()}

MODIFIERS = {
    "public", "private", "internal", "protected", "open", "final", "abstract",
    "sealed", "data", "inner", "enum", "annotation", "value", "inline", "noinline",
    "crossinline", "suspend", "tailrec", "operator", "infix", "external", "override",
    "lateinit", "const", "vararg", "reified", "companion", "expect", "actual",
    "fun", "val", "var",
}

# Kotlin / Android names a bare identifier may legitimately be without a
# declaration in the file. Kept tight on purpose: a false "undeclared" on a
# language keyword would make the rule useless.
BUILTIN_IDENTIFIERS = {
    "true", "false", "null", "this", "super", "it", "return", "if", "else", "when",
    "for", "while", "do", "break", "continue", "try", "catch", "finally", "throw",
    "in", "is", "as", "by", "object", "class", "fun", "val", "var", "typealias",
    "package", "import", "where", "out", "vararg", "companion", "constructor", "init",
    "get", "set", "field", "value", "name", "index",
}


@dataclass
class Token:
    kind: str
    value: str
    line: int
    start: int
    end: int

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"Token({self.kind},{self.value!r},L{self.line})"


@dataclass
class Decl:
    kind: str                  # class | object | interface | fun | val | var | enum
    name: str
    line: int
    modifiers: Set[str] = field(default_factory=set)
    annotations: List[str] = field(default_factory=list)
    body_start: int = -1       # token index of "{", -1 when absent
    body_end: int = -1         # token index of "}", -1 when absent
    params: List[str] = field(default_factory=list)
    supertypes: List[str] = field(default_factory=list)


@dataclass
class KtFile:
    path: str
    text: str
    tokens: List[Token]
    pairs: Dict[int, int]                 # token index -> matching bracket index
    package: str
    imports: List[Tuple[str, str, int]]   # (path, alias-or-empty, line)
    classes: List[Decl]
    functions: List[Decl]
    properties: List[Decl]
    brace_balance: int
    header_end: int = 0                   # first token index after package/imports

    # -- convenience --------------------------------------------------------
    def line_text(self, line: int) -> str:
        lines = self.text.splitlines()
        return lines[line - 1].strip() if 0 < line <= len(lines) else ""

    def body_text(self, decl: Decl) -> str:
        if decl.body_start < 0 or decl.body_end < 0:
            return ""
        start = self.tokens[decl.body_start].start
        end = self.tokens[decl.body_end].end
        return self.text[start:end]

    def span_tokens(self, start: int, end: int) -> List[Token]:
        return self.tokens[start:end + 1]

    def imported_names(self) -> Set[str]:
        out: Set[str] = set()
        for path, alias, _line in self.imports:
            out.add(alias or path.rsplit(".", 1)[-1])
        return out

    def class_names(self) -> Set[str]:
        return {c.name for c in self.classes if c.name}

    def identifiers(self) -> List[Token]:
        return [t for t in self.tokens if t.kind == KIND_ID]


def _scan(text: str) -> List[Token]:
    """Tokenise Kotlin, dropping comments but keeping every string as one token."""
    tokens: List[Token] = []
    i = 0
    n = len(text)
    line = 1
    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch in " \t\r\f\v":
            i += 1
            continue
        # comments
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            depth = 1
            j = i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    if text[j] == "\n":
                        line += 1
                    j += 1
            i = j
            continue
        # raw string.
        #
        # Kotlin ends a raw string at a run of three or more quotes and takes
        # the *whole run* as the delimiter, so `"""...([^"]*)""""` is a legal
        # pattern ending in a quote (the last three quotes close it). Treating
        # the first `"""` as the terminator made the scanner swallow the rest of
        # the file and report a phantom brace imbalance -- a rule firing on
        # correct code, which is the one thing a checker must never do.
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0:
                j = n
            else:
                j += 3
                while j < n and text[j] == '"':
                    j += 1
            tokens.append(Token(KIND_STR, text[i:j], line, i, j))
            line += text[i:j].count("\n")
            i = j
            continue
        if ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    line += 1
                j += 1
            tokens.append(Token(KIND_STR, text[i:j], line, i, j))
            i = j
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            tokens.append(Token(KIND_STR, text[i:j], line, i, j))
            i = j
            continue
        if ch == "`":
            j = text.find("`", i + 1)
            j = n if j < 0 else j + 1
            tokens.append(Token(KIND_ID, text[i:j].strip("`"), line, i, j))
            i = j
            continue
        if ch.isdigit():
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] in "._"):
                j += 1
            tokens.append(Token(KIND_NUM, text[i:j], line, i, j))
            i = j
            continue
        if ch.isalpha() or ch == "_":
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] == "_"):
                j += 1
            tokens.append(Token(KIND_ID, text[i:j], line, i, j))
            i = j
            continue
        # multi-character punctuation that matters for structure
        for op in ("->", "::", "?.", "?:", "!!", "..", "+=", "-=", "==", "!=", "&&", "||"):
            if text.startswith(op, i):
                tokens.append(Token(KIND_PUNC, op, line, i, i + len(op)))
                i += len(op)
                break
        else:
            tokens.append(Token(KIND_PUNC, ch, line, i, i + 1))
            i += 1
    return tokens


def _match_brackets(tokens: Sequence[Token]) -> Tuple[Dict[int, int], int]:
    pairs: Dict[int, int] = {}
    stack: List[Tuple[int, str]] = []
    balance = 0
    for idx, tok in enumerate(tokens):
        if tok.kind != KIND_PUNC:
            continue
        if tok.value in OPEN:
            stack.append((idx, tok.value))
            if tok.value == "{":
                balance += 1
        elif tok.value in CLOSE:
            if tok.value == "}":
                balance -= 1
            # Pop to the nearest opener of the right kind; tolerate stray closers.
            for pos in range(len(stack) - 1, -1, -1):
                if stack[pos][1] == CLOSE[tok.value]:
                    opener_idx, _ = stack.pop(pos)
                    pairs[opener_idx] = idx
                    pairs[idx] = opener_idx
                    break
    return pairs, balance


def _collect_annotations(tokens: Sequence[Token], idx: int) -> List[str]:
    """Annotations immediately preceding ``idx`` (``@Composable``, ``@JvmStatic``)."""
    out: List[str] = []
    j = idx - 1
    while j >= 0:
        tok = tokens[j]
        if tok.kind == KIND_PUNC and tok.value in (")", ","):
            # An annotation with arguments: skip back over its parentheses.
            depth = 0
            k = j
            while k >= 0:
                if tokens[k].kind == KIND_PUNC and tokens[k].value == ")":
                    depth += 1
                elif tokens[k].kind == KIND_PUNC and tokens[k].value == "(":
                    depth -= 1
                    if depth == 0:
                        break
                k -= 1
            j = k - 1
            continue
        if tok.kind == KIND_ID and j > 0 and tokens[j - 1].kind == KIND_PUNC and tokens[j - 1].value == "@":
            out.append(tok.value)
            j -= 2
            continue
        if tok.kind == KIND_ID and tok.value in MODIFIERS:
            j -= 1
            continue
        break
    return list(reversed(out))


def _modifiers_before(tokens: Sequence[Token], idx: int) -> Set[str]:
    out: Set[str] = set()
    j = idx - 1
    while j >= 0:
        tok = tokens[j]
        if tok.kind == KIND_PUNC and tok.value == ")":
            depth = 0
            k = j
            while k >= 0:
                if tokens[k].kind == KIND_PUNC and tokens[k].value == ")":
                    depth += 1
                elif tokens[k].kind == KIND_PUNC and tokens[k].value == "(":
                    depth -= 1
                    if depth == 0:
                        break
                k -= 1
            j = k - 1
            continue
        if tok.kind == KIND_ID and tok.value in MODIFIERS:
            out.add(tok.value)
            j -= 1
            continue
        if tok.kind == KIND_ID and j > 0 and tokens[j - 1].value == "@":
            j -= 2
            continue
        break
    return out



def _parse_header(tokens: Sequence[Token], pairs: Dict[int, int]
                  ) -> Tuple[str, List[Tuple[str, str, int]], int]:
    """Read ``package`` and every ``import`` before the first declaration.

    Kept separate from [parse] because the header has its own grammar and got
    the aliasing case wrong twice: `import a.b.C as D` is one statement, and a
    file-level `@file:...` annotation legally precedes `package`.
    """
    package = ""
    imports: List[Tuple[str, str, int]] = []
    i = 0
    while i < len(tokens):
        tok = tokens[i]
        # @file:foo.Bar(...) / @Suppress(...) before the package declaration
        if tok.kind == KIND_PUNC and tok.value == "@":
            j = i + 1
            while j < len(tokens) and tokens[j].kind == KIND_ID:
                nxt = tokens[j + 1] if j + 1 < len(tokens) else None
                if nxt is not None and nxt.kind == KIND_PUNC and nxt.value in (".", ":"):
                    j += 2
                    continue
                j += 1
                break
            if j < len(tokens) and tokens[j].kind == KIND_PUNC and tokens[j].value == "(":
                close = pairs.get(j)
                j = (close + 1) if close is not None else j + 1
            i = max(j, i + 1)
            continue
        if tok.kind == KIND_ID and tok.value == "package":
            j = i + 1
            parts: List[str] = []
            while j + 1 < len(tokens) and tokens[j].kind == KIND_ID and tokens[j + 1].value == ".":
                parts.append(tokens[j].value)
                parts.append(".")
                j += 2
            if j < len(tokens) and tokens[j].kind == KIND_ID:
                parts.append(tokens[j].value)
                j += 1
            package = "".join(parts)
            i = j
            continue
        if tok.kind == KIND_ID and tok.value == "import":
            j = i + 1
            parts: List[str] = []
            alias = ""
            # A dotted path first: this.that.Other
            while j < len(tokens) and tokens[j].kind == KIND_ID:
                parts.append(tokens[j].value)
                j += 1
                if j < len(tokens) and tokens[j].kind == KIND_PUNC and tokens[j].value == ".":
                    parts.append(".")
                    j += 1
                    continue
                break
            # `.*` for star imports.
            if j < len(tokens) and tokens[j].kind == KIND_PUNC and tokens[j].value == "*":
                parts.append("*")
                j += 1
            # Then the optional alias: `import a.b.C as D`.
            if j < len(tokens) and tokens[j].kind == KIND_ID and tokens[j].value == "as":
                if j + 1 < len(tokens) and tokens[j + 1].kind == KIND_ID:
                    alias = tokens[j + 1].value
                j += 2
            imports.append(("".join(parts), alias, tok.line))
            i = j
            continue
        # anything else is the first declaration: the header is over
        break
    return package, imports, i

def parse(text: str, path: str = "") -> KtFile:
    tokens = _scan(text)
    pairs, balance = _match_brackets(tokens)
    package = ""
    imports: List[Tuple[str, str, int]] = []
    classes: List[Decl] = []
    functions: List[Decl] = []
    properties: List[Decl] = []

    package, imports, header_end = _parse_header(tokens, pairs)

    # -- declarations -------------------------------------------------------
    for i, tok in enumerate(tokens):
        if tok.kind != KIND_ID:
            continue
        prev = tokens[i - 1] if i else None
        if prev is not None and prev.kind == KIND_PUNC and prev.value in (".", "::"):
            continue  # a member access, not a declaration
        if tok.value in ("class", "interface", "object", "enum"):
            name_idx = i + 1
            while name_idx < len(tokens) and tokens[name_idx].kind == KIND_ID and tokens[name_idx].value in MODIFIERS:
                name_idx += 1
            if name_idx >= len(tokens) or tokens[name_idx].kind != KIND_ID:
                continue
            name = tokens[name_idx].value
            # `object : Runnable {}` is an anonymous object: no name to record.
            body = None
            supertypes: List[str] = []
            j = name_idx + 1
            depth = 0
            while j < len(tokens):
                t = tokens[j]
                if t.kind == KIND_PUNC and t.value == "(":
                    depth += 1
                elif t.kind == KIND_PUNC and t.value == ")":
                    depth -= 1
                elif t.kind == KIND_PUNC and t.value == "{" and depth <= 0 and j in pairs:
                    body = j
                    break
                elif t.kind == KIND_ID and t.value in ("class", "fun", "val", "var") and depth <= 0:
                    break
                elif t.kind == KIND_ID and j + 1 < len(tokens) and tokens[j + 1].value in ("(", ",", "{") and depth <= 0:
                    supertypes.append(t.value)
                j += 1
            kind = "class" if tok.value == "class" else ("interface" if tok.value == "interface" else "object")
            if "enum" in _modifiers_before(tokens, i) or (prev and prev.value == "enum"):
                kind = "enum"
            decl = Decl(
                kind=kind, name=name, line=tok.line,
                modifiers=_modifiers_before(tokens, i),
                annotations=_collect_annotations(tokens, i),
                body_start=body if body is not None else -1,
                body_end=pairs.get(body, -1) if body is not None else -1,
                supertypes=supertypes,
            )
            if "enum" in decl.modifiers:
                decl.kind = "enum"
            classes.append(decl)
        elif tok.value == "fun":
            # `fun` may be preceded by `interface`/`class` detection; skip those.
            j = i + 1
            # generic parameter list
            if j < len(tokens) and tokens[j].value == "<":
                depth = 0
                while j < len(tokens):
                    if tokens[j].value == "<":
                        depth += 1
                    elif tokens[j].value == ">":
                        depth -= 1
                        if depth == 0:
                            j += 1
                            break
                    j += 1
            # receiver: `fun Foo.bar(`, `fun Foo<String>.bar(`, `fun Foo<T>?.bar(`
            #
            # The receiver's type arguments belong to the receiver, not the name.
            # Stopping the scan at `<` recorded `ProducerScope<SourceSnapshot>
            # .verifyInto` as a function called `ProducerScope`, which made two
            # correct extensions on one receiver look like a duplicate.
            def _skip_angle(j: int) -> int:
                """Move past one balanced `<...>` group (nested generics included)."""
                depth = 0
                while j < len(tokens):
                    if tokens[j].value == "<":
                        depth += 1
                    elif tokens[j].value == ">":
                        depth -= 1
                        if depth == 0:
                            return j + 1
                    j += 1
                return j

            name = ""
            # Walk the receiver/name path. A `.` starts a new segment (so the
            # name is the segment after the last dot), and a `<...>` after a
            # segment is that segment's type arguments, skipped whole.
            while j < len(tokens):
                t = tokens[j]
                if t.value == "<":
                    j = _skip_angle(j)
                    continue
                if t.value == "?":
                    j += 1
                    continue
                if t.kind == KIND_ID:
                    name = t.value
                    j += 1
                    continue
                if t.value == ".":
                    name = ""
                    j += 1
                    continue
                break
            if not name:
                continue
            body = None
            params: List[str] = []
            if j < len(tokens) and tokens[j].kind == KIND_PUNC and tokens[j].value == "(" and j in pairs:
                close = pairs[j]
                for k in range(j + 1, close):
                    if tokens[k].kind == KIND_ID and k + 1 < close and tokens[k + 1].value == ":":
                        params.append(tokens[k].value)
                j = close + 1
            # return type, then body or `= expr`
            depth = 0
            while j < len(tokens):
                t = tokens[j]
                if t.kind == KIND_PUNC and t.value == "{" and depth == 0 and j in pairs:
                    body = j
                    break
                if t.kind == KIND_PUNC and t.value == "=" and depth == 0:
                    break
                if t.kind == KIND_ID and t.value in ("class", "fun", "object", "interface"):
                    break
                if t.kind == KIND_PUNC and t.value in ("?", "->", "<", ">", "&"):
                    if t.value == "<":
                        depth += 1
                    elif t.value == ">":
                        depth -= 1
                j += 1
            decl = Decl(
                kind="fun", name=name, line=tok.line,
                modifiers=_modifiers_before(tokens, i),
                annotations=_collect_annotations(tokens, i),
                body_start=body if body is not None else -1,
                body_end=pairs.get(body, -1) if body is not None else -1,
                params=params,
            )
            functions.append(decl)
        elif tok.value in ("val", "var"):
            # skip parameter declarations inside a parameter list
            if prev is not None and prev.kind == KIND_PUNC and prev.value in ("(", ","):
                continue
            name_idx = i + 1
            if name_idx < len(tokens) and tokens[name_idx].kind == KIND_ID:
                name = tokens[name_idx].value
                # has a declared/default value?
                j = name_idx + 1
                has_default = False
                while j < len(tokens) and j < name_idx + 40:
                    t = tokens[j]
                    if t.kind == KIND_PUNC and t.value in (")", "}", ";"):
                        break
                    if t.kind == KIND_PUNC and t.value == "=":
                        has_default = True
                        break
                    if t.kind == KIND_ID and t.value in ("fun", "class", "object") :
                        break
                    j += 1
                properties.append(Decl(
                    kind=tok.value, name=name, line=tok.line,
                    modifiers=_modifiers_before(tokens, i),
                    annotations=_collect_annotations(tokens, i),
                    params=["default"] if has_default else [],
                ))

    return KtFile(
        path=path, text=text, tokens=tokens, pairs=pairs, package=package,
        header_end=header_end, imports=imports, classes=classes, functions=functions,
        properties=properties, brace_balance=balance,
    )


def parse_file(path: Path, root: Optional[Path] = None) -> KtFile:
    text = path.read_text(encoding="utf-8", errors="replace")
    label = str(path.relative_to(root)) if root and path.is_relative_to(root) else str(path)
    return parse(text, label)


# ---------------------------------------------------------------------------
# Token-level helpers used by rules
# ---------------------------------------------------------------------------

def call_sites(kt: KtFile, name: str) -> List[int]:
    """Token indexes where ``name`` is directly followed by ``(``."""
    out = []
    toks = kt.tokens
    for i, t in enumerate(toks):
        if t.kind == KIND_ID and t.value == name and i + 1 < len(toks) and toks[i + 1].value == "(":
            out.append(i)
    return out


def member_accesses(kt: KtFile, name: str) -> List[int]:
    """Token indexes of ``.name`` member reads or writes."""
    out = []
    toks = kt.tokens
    for i, t in enumerate(toks):
        if t.kind == KIND_ID and t.value == name and i > 0 and toks[i - 1].kind == KIND_PUNC \
                and toks[i - 1].value in (".", "?."):
            out.append(i)
    return out


def lambda_body_is_empty(kt: KtFile, open_idx: int) -> bool:
    """True when the ``{`` at ``open_idx`` closes with nothing but whitespace inside."""
    close = kt.pairs.get(open_idx)
    if close is None or close == open_idx + 1:
        return close == open_idx + 1
    for t in kt.tokens[open_idx + 1:close]:
        if t.kind in (KIND_ID, KIND_STR, KIND_NUM) or (t.kind == KIND_PUNC and t.value not in (";",)):
            return False
    return True


def enclosing_function(kt: KtFile, index: int) -> Optional[Decl]:
    """The innermost function whose body span covers ``index``."""
    best: Optional[Decl] = None
    for fn in kt.functions:
        if fn.body_start < 0 or fn.body_end < 0:
            continue
        if fn.body_start <= index <= fn.body_end:
            if best is None or fn.body_start > best.body_start:
                best = fn
    return best


def enclosing_class(kt: KtFile, index: int) -> Optional[Decl]:
    best: Optional[Decl] = None
    for cls in kt.classes:
        if cls.body_start < 0 or cls.body_end < 0:
            continue
        if cls.body_start <= index <= cls.body_end:
            if best is None or cls.body_start > best.body_start:
                best = cls
    return best


def string_literals(kt: KtFile) -> List[Token]:
    return [t for t in kt.tokens if t.kind == KIND_STR]


def strip_string_quotes(value: str) -> str:
    v = value
    if v.startswith('"""') and v.endswith('"""') and len(v) >= 6:
        return v[3:-3]
    if v.startswith('"') and v.endswith('"') and len(v) >= 2:
        return v[1:-1]
    if v.startswith("'") and v.endswith("'") and len(v) >= 2:
        return v[1:-1]
    return v
