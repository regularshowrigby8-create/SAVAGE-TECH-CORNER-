"""The tool layer: every tool is implemented here, in the standard library, and works offline.

Imported MCP servers are useful but they are *dependencies*: a package install, a network fetch, a
runtime that may not exist on the machine this component is carried to. This module takes the
capability set those servers provide - filesystem, fetch, search, memory, thinking, time, sqlite,
archives, code analysis, process execution, model access - and implements each capability directly,
with no third-party import and no install step. What cannot work offline (HTTP fetching, remote
model calls) fails with a reason rather than a fake answer.

Every tool declares a JSON schema so the same registry serves two callers: agents in this process,
and any external AI client through the MCP server in `toolserver.py`.

Contract for a tool:
  * it returns a JSON-serialisable value, or raises ToolError with a human reason;
  * it never returns a fabricated result: unavailable inputs raise, they do not apologise;
  * writes stay inside the workspace root it is given - paths that escape it raise.
"""

from __future__ import annotations

import csv
import difflib
import hashlib
import html.parser
import io
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import tarfile
import time
import unicodedata
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

MAX_READ_BYTES = 400_000
MAX_HTTP_BYTES = 1_000_000
DEFAULT_TIMEOUT = 120
TEXT_SUFFIXES = (".py", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".java", ".kt", ".kts", ".go",
                 ".rs", ".c", ".h", ".cpp", ".hpp", ".cs", ".rb", ".php", ".swift", ".sh", ".sql",
                 ".json", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".md", ".rst", ".txt", ".xml",
                 ".html", ".css", ".gradle", ".properties", ".env", ".tf", ".dockerfile")
SECRET_PATTERNS = (
    ("openai-key", re.compile(r"sk-[A-Za-z0-9]{20,}")),
    ("anthropic-key", re.compile(r"sk-ant-[A-Za-z0-9\-_]{20,}")),
    ("google-key", re.compile(r"AIza[0-9A-Za-z\-_]{30,}")),
    ("github-token", re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}")),
    ("aws-key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("slack-token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("generic-secret", re.compile(r"(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*['\"]"
                                  r"[A-Za-z0-9\-_/+]{16,}['\"]")),
)


def port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    """Is something accepting connections on this port? Sub-second, and never raises."""
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except (OSError, ValueError):
        return False


class ToolError(RuntimeError):
    """A tool refusing to pretend: the call could not be carried out, and why."""


# --------------------------------------------------------------------------- workspace


class Workspace:
    """The one directory a tool may touch. Everything else is out of bounds."""

    def __init__(self, root: str | Path):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.state = self.root / ".sealed"
        self.state.mkdir(exist_ok=True)

    def resolve(self, path: str, *, must_exist: bool = False) -> Path:
        if path is None or path == "":
            raise ToolError("a path is required")
        candidate = Path(path)
        resolved = (candidate if candidate.is_absolute() else self.root / candidate).resolve()
        if resolved != self.root and self.root not in resolved.parents:
            raise ToolError(f"path escapes the workspace root: {path}")
        if must_exist and not resolved.exists():
            raise ToolError(f"no such path in the workspace: {path}")
        return resolved

    def relative(self, path: Path) -> str:
        try:
            return str(path.relative_to(self.root))
        except ValueError:
            return str(path)


def _text(path: Path, limit: int = MAX_READ_BYTES) -> str:
    data = path.read_bytes()[:limit]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("utf-8", "replace")


# --------------------------------------------------------------------------- filesystem


def fs_list(workspace: Workspace, path: str = ".", depth: int = 2, limit: int = 400) -> dict:
    base = workspace.resolve(path, must_exist=True)
    if base.is_file():
        return {"path": workspace.relative(base), "type": "file", "bytes": base.stat().st_size}
    entries = []
    base_depth = len(base.parts)
    for current, dirs, files in os.walk(base):
        current_path = Path(current)
        level = len(current_path.parts) - base_depth
        dirs[:] = [d for d in sorted(dirs) if d not in {".git", "node_modules", "__pycache__",
                                                         ".sealed", ".gradle"}]
        if level >= depth:
            dirs[:] = []
            continue
        for name in sorted(dirs):
            entries.append({"path": workspace.relative(current_path / name), "type": "dir"})
        for name in sorted(files):
            target = current_path / name
            try:
                size = target.stat().st_size
            except OSError:
                size = -1
            entries.append({"path": workspace.relative(target), "type": "file", "bytes": size})
        if len(entries) >= limit:
            break
    return {"path": workspace.relative(base), "entries": entries[:limit],
            "truncated": len(entries) > limit}


def fs_read(workspace: Workspace, path: str, start: int = 1, lines: int = 0) -> dict:
    target = workspace.resolve(path, must_exist=True)
    if target.is_dir():
        raise ToolError(f"{path} is a directory")
    data = _text(target)
    selected = data.splitlines()
    if start > 1 or lines:
        selected = selected[max(start - 1, 0): (start - 1 + lines) if lines else None]
    return {"path": workspace.relative(target), "bytes": target.stat().st_size,
            "lines": len(selected), "text": "\n".join(selected)}


def fs_write(workspace: Workspace, path: str, text: str, append: bool = False) -> dict:
    target = workspace.resolve(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "a" if append else "w", encoding="utf-8") as handle:
        handle.write(text)
    return {"path": workspace.relative(target), "bytes": target.stat().st_size, "append": append}


def fs_mkdir(workspace: Workspace, path: str) -> dict:
    target = workspace.resolve(path)
    target.mkdir(parents=True, exist_ok=True)
    return {"path": workspace.relative(target), "created": True}


def fs_search(workspace: Workspace, pattern: str, path: str = ".", glob: str = "", limit: int = 100,
              ignore_case: bool = False) -> dict:
    base = workspace.resolve(path, must_exist=True)
    flags = re.I if ignore_case else 0
    matcher = re.compile(pattern, flags)
    matches = []
    for target in sorted(base.rglob(glob or "*")):
        if not target.is_file() or target.name in {"SEAL.json", "SEAL.sha256"}:
            continue
        if any(part in {".git", "node_modules", "__pycache__", ".sealed"} for part in target.parts):
            continue
        if target.suffix.lower() not in TEXT_SUFFIXES and target.suffix != "":
            continue
        try:
            text = _text(target, 200_000)
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            if matcher.search(line):
                matches.append({"path": workspace.relative(target), "line": number,
                                "text": line.strip()[:240]})
                if len(matches) >= limit:
                    return {"matches": matches, "truncated": True}
    return {"matches": matches, "truncated": False}


def fs_stat(workspace: Workspace, path: str = ".") -> dict:
    target = workspace.resolve(path)
    if not target.exists():
        return {"path": workspace.relative(target), "exists": False}
    stat = target.stat()
    return {"path": workspace.relative(target), "exists": True, "type":
            "dir" if target.is_dir() else "file", "bytes": stat.st_size,
            "modified": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(timespec="seconds")}


def fs_hash(workspace: Workspace, path: str, algorithm: str = "sha256") -> dict:
    target = workspace.resolve(path, must_exist=True)
    digest = hashlib.new(algorithm)
    with open(target, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return {"path": workspace.relative(target), "algorithm": algorithm, "digest": digest.hexdigest(),
            "bytes": target.stat().st_size}


def fs_delete(workspace: Workspace, path: str) -> dict:
    target = workspace.resolve(path, must_exist=True)
    if target == workspace.root:
        raise ToolError("refusing to delete the workspace root")
    if target.is_dir():
        shutil.rmtree(target)
    else:
        target.unlink()
    return {"path": workspace.relative(target), "deleted": True}


def fs_copy(workspace: Workspace, source: str, destination: str) -> dict:
    src = workspace.resolve(source, must_exist=True)
    dst = workspace.resolve(destination)
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
    return {"source": workspace.relative(src), "destination": workspace.relative(dst)}


def fs_move(workspace: Workspace, source: str, destination: str) -> dict:
    src = workspace.resolve(source, must_exist=True)
    dst = workspace.resolve(destination)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(src), str(dst))
    return {"source": workspace.relative(src), "destination": workspace.relative(dst)}


# --------------------------------------------------------------------------- text and data


def text_diff(workspace: Workspace, left: str, right: str, context: int = 3) -> dict:
    a = _text(workspace.resolve(left, must_exist=True)).splitlines(keepends=True)
    b = _text(workspace.resolve(right, must_exist=True)).splitlines(keepends=True)
    diff = list(difflib.unified_diff(a, b, fromfile=left, tofile=right, n=context))
    return {"left": left, "right": right, "changed": bool(diff), "diff": "".join(diff)}


def text_patch(workspace: Workspace, path: str, diff: str) -> dict:
    """Applies a unified diff produced by text.diff, refusing anything it cannot apply exactly."""
    target = workspace.resolve(path, must_exist=True)
    original = _text(target).splitlines(keepends=True)
    try:
        patched = _apply_unified(original, diff)
    except ValueError as problem:
        raise ToolError(f"the patch does not apply cleanly: {problem}") from problem
    target.write_text("".join(patched), encoding="utf-8")
    return {"path": workspace.relative(target), "applied": True, "lines": len(patched)}


def _apply_unified(original: list[str], diff: str) -> list[str]:
    lines = diff.splitlines(keepends=True)
    hunks = []
    current = None
    for line in lines:
        if line.startswith("@@"):
            match = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            if not match:
                raise ValueError(f"unreadable hunk header: {line.strip()}")
            current = {"start": int(match.group(1)), "lines": []}
            hunks.append(current)
        elif current is not None and line[:1] in (" ", "-", "+"):
            current["lines"].append(line)
    if not hunks:
        raise ValueError("the diff carries no hunks")

    result = list(original)
    offset = 0
    for hunk in hunks:
        index = hunk["start"] - 1 + offset
        for line in hunk["lines"]:
            marker, body = line[0], line[1:]
            if marker == " ":
                if index >= len(result) or result[index] != body:
                    raise ValueError(f"context mismatch at line {index + 1}")
                index += 1
            elif marker == "-":
                if index >= len(result) or result[index] != body:
                    raise ValueError(f"removal mismatch at line {index + 1}")
                del result[index]
                offset -= 1
            else:
                result.insert(index, body)
                index += 1
                offset += 1
    return result


def text_json(workspace: Workspace, path: str = "", text: str = "", query: str = "") -> dict:
    raw = text or (_text(workspace.resolve(path, must_exist=True)) if path else "")
    if not raw.strip():
        raise ToolError("give either a path or text to read as JSON")
    try:
        loaded = json.loads(raw)
    except ValueError as problem:
        return {"valid": False, "error": str(problem)}
    if query:
        value = loaded
        for step in query.split("."):
            if not step:
                continue
            key, _, index = step.partition("[")
            if key:
                if not isinstance(value, dict) or key not in value:
                    raise ToolError(f"query step {key!r} is not a key of the value")
                value = value[key]
            if index:
                position = int(index.rstrip("]"))
                if not isinstance(value, list) or position >= len(value):
                    raise ToolError(f"query index {position} is out of range")
                value = value[position]
        return {"valid": True, "query": query, "value": value}
    return {"valid": True, "formatted": json.dumps(loaded, indent=2, sort_keys=True)[:MAX_READ_BYTES],
            "keys": sorted(loaded)[:50] if isinstance(loaded, dict) else None,
            "type": type(loaded).__name__}


def text_toml(workspace: Workspace, path: str) -> dict:
    import tomllib
    raw = _text(workspace.resolve(path, must_exist=True))
    try:
        return {"path": path, "valid": True, "value": tomllib.loads(raw)}
    except tomllib.TOMLDecodeError as problem:
        return {"path": path, "valid": False, "error": str(problem)}


def text_csv(workspace: Workspace, path: str, limit: int = 50) -> dict:
    raw = _text(workspace.resolve(path, must_exist=True))
    reader = csv.DictReader(io.StringIO(raw))
    rows = []
    for index, row in enumerate(reader):
        if index >= limit:
            break
        rows.append(row)
    return {"path": path, "columns": reader.fieldnames or [], "rows": rows}


def text_regex(text: str, pattern: str, group: int = 0, limit: int = 200) -> dict:
    matcher = re.compile(pattern)
    found = []
    for match in matcher.finditer(text):
        try:
            found.append(match.group(group))
        except IndexError:
            found.append(match.group(0))
        if len(found) >= limit:
            break
    return {"pattern": pattern, "matches": len(found), "values": found}


class _TextExtractor(html.parser.HTMLParser):
    SKIP = {"script", "style", "noscript", "template", "svg"}

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self._skip += 1
        elif tag in {"p", "div", "li", "br", "h1", "h2", "h3", "h4", "tr"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in self.SKIP and self._skip:
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)

    def text(self) -> str:
        merged = "".join(self.parts)
        merged = re.sub(r"[ \t\r\f\v]+", " ", merged)
        merged = re.sub(r"\n\s*\n\s*\n+", "\n\n", merged)
        return merged.strip()


def text_html(workspace: Workspace, path: str = "", text: str = "", limit: int = 60_000) -> dict:
    raw = text or (_text(workspace.resolve(path, must_exist=True)) if path else "")
    if not raw.strip():
        raise ToolError("give either a path or text to read as HTML")
    extractor = _TextExtractor()
    extractor.feed(raw)
    return {"title": (re.search(r"<title[^>]*>(.*?)</title>", raw, re.I | re.S).group(1).strip()
                      if re.search(r"<title[^>]*>(.*?)</title>", raw, re.I | re.S) else None),
            "text": extractor.text()[:limit]}


def text_normalise(text: str) -> dict:
    return {"value": unicodedata.normalize("NFKC", text).strip(),
            "words": len(re.findall(r"\w+", text))}


# --------------------------------------------------------------------------- code


def code_metrics(workspace: Workspace, path: str = ".", limit: int = 200) -> dict:
    base = workspace.resolve(path, must_exist=True)
    files = [base] if base.is_file() else [p for p in sorted(base.rglob("*")) if p.is_file()]
    per_file = []
    total_lines = total_code = 0
    for target in files[:limit]:
        if target.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            text = _text(target, 200_000)
        except OSError:
            continue
        lines = text.splitlines()
        code = [line for line in lines if line.strip() and not line.strip().startswith(("#", "//"))]
        functions = len(re.findall(r"^\s*(?:def|function|func|fn|public|private|protected)\s+\w+",
                                   text, re.M)) + len(re.findall(r"^\s*\w+\s*\([^)]*\)\s*\{", text, re.M))
        classes = len(re.findall(r"^\s*(?:class|struct|interface|enum)\s+\w+", text, re.M))
        per_file.append({"path": workspace.relative(target), "lines": len(lines), "code": len(code),
                         "functions": functions, "classes": classes})
        total_lines += len(lines)
        total_code += len(code)
    return {"files": per_file, "totals": {"files": len(per_file), "lines": total_lines,
                                          "code": total_code}}


def code_todos(workspace: Workspace, path: str = ".", limit: int = 100) -> dict:
    marker = re.compile(r"\b(TODO|FIXME|XXX|HACK|NotImplementedError|pass\s*#\s*stub)\b")
    return _scan_workspace(workspace, path, marker, limit,
                           description="unfinished-work markers in source and tests")


def code_secrets(workspace: Workspace, path: str = ".", limit: int = 100) -> dict:
    findings = []
    base = workspace.resolve(path, must_exist=True)
    files = [base] if base.is_file() else [p for p in sorted(base.rglob("*")) if p.is_file()]
    for target in files:
        if target.stat().st_size > MAX_READ_BYTES:
            continue
        if any(part in {".git", "node_modules", "__pycache__"} for part in target.parts):
            continue
        try:
            text = _text(target)
        except OSError:
            continue
        for name, pattern in SECRET_PATTERNS:
            for match in pattern.finditer(text):
                line = text[:match.start()].count("\n") + 1
                findings.append({"path": workspace.relative(target), "line": line, "kind": name})
                if len(findings) >= limit:
                    return {"findings": findings, "truncated": True, "clean": False}
    return {"findings": findings, "truncated": False, "clean": not findings}


def _scan_workspace(workspace: Workspace, path: str, pattern: re.Pattern, limit: int,
                    description: str) -> dict:
    base = workspace.resolve(path, must_exist=True)
    files = [base] if base.is_file() else [p for p in sorted(base.rglob("*")) if p.is_file()]
    findings = []
    for target in files:
        if target.suffix.lower() not in TEXT_SUFFIXES or target.stat().st_size > MAX_READ_BYTES:
            continue
        try:
            text = _text(target)
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            if pattern.search(line):
                findings.append({"path": workspace.relative(target), "line": number,
                                 "text": line.strip()[:200]})
                if len(findings) >= limit:
                    return {"description": description, "findings": findings, "truncated": True}
    return {"description": description, "findings": findings, "truncated": False}


def code_structure(workspace: Workspace, path: str) -> dict:
    """Symbols in a file: real parse for Python, a documented regex parse for everything else."""
    target = workspace.resolve(path, must_exist=True)
    text = _text(target)
    if target.suffix == ".py":
        import ast
        try:
            tree = ast.parse(text)
        except SyntaxError as problem:
            return {"path": path, "parser": "ast", "valid": False, "error": str(problem)}
        symbols = []
        for node in tree.body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                symbols.append({"kind": "function", "name": node.name, "line": node.lineno,
                                "args": [a.arg for a in node.args.args]})
            elif isinstance(node, ast.ClassDef):
                methods = [n.name for n in node.body if isinstance(n, (ast.FunctionDef,
                                                                      ast.AsyncFunctionDef))]
                symbols.append({"kind": "class", "name": node.name, "line": node.lineno,
                                "methods": methods})
        imports = sorted({alias.name.split(".")[0] for node in ast.walk(tree)
                          if isinstance(node, (ast.Import, ast.ImportFrom))
                          for alias in getattr(node, "names", [])})
        return {"path": path, "parser": "ast", "valid": True, "symbols": symbols, "imports": imports,
                "lines": len(text.splitlines())}
    symbols = [{"kind": "definition", "name": match.group(2), "line": text[:match.start()].count("\n") + 1}
               for match in re.finditer(r"^\s*(class|function|func|fn|void|int|def|interface|struct)\s+"
                                        r"(\w+)", text, re.M)]
    return {"path": path, "parser": "regex", "valid": True, "symbols": symbols,
            "imports": sorted(set(re.findall(r"^\s*(?:import|from|require\(|use )\s*([\w./@-]+)",
                                             text, re.M))),
            "lines": len(text.splitlines())}


# --------------------------------------------------------------------------- processes and languages


def proc_run(workspace: Workspace, command: str, cwd: str = ".", timeout: int = DEFAULT_TIMEOUT,
             env: dict | None = None, allow_failure: bool = False) -> dict:
    """Runs a command inside the workspace and returns exactly what it produced.

    The command runs with a timeout and its exit code is reported, never interpreted: a caller that
    wants a build to have succeeded must look at the code. `allow_failure` only silences the raise.
    """
    where = workspace.resolve(cwd, must_exist=True)
    environment = dict(os.environ)
    environment.update(env or {})
    started = time.time()
    try:
        done = subprocess.run(command, shell=True, cwd=where, capture_output=True, text=True,
                              timeout=timeout, env=environment)
    except subprocess.TimeoutExpired as expired:
        raise ToolError(f"the command did not finish within {timeout}s: {command}") from expired
    result = {"command": command, "cwd": workspace.relative(where), "exit_code": done.returncode,
              "seconds": round(time.time() - started, 2), "stdout": done.stdout[-40_000:],
              "stderr": done.stderr[-20_000:]}
    if done.returncode != 0 and not allow_failure:
        raise ToolError(f"the command exited {done.returncode}: {command}\n"
                        f"--- stderr ---\n{result['stderr'][-2000:]}")
    return result


def git_command(workspace: Workspace, arguments: str, timeout: int = 60) -> dict:
    if shutil.which("git") is None:
        raise ToolError("git is not installed on this machine")
    if any(flag in arguments for flag in ("--exec", "filter-branch")):
        raise ToolError("that git subcommand is refused by this tool")
    return proc_run(workspace, f"git {arguments}", timeout=timeout, allow_failure=True)


# --------------------------------------------------------------------------- network


def net_fetch(url: str, timeout: int = 30, as_text: bool = True, limit: int = MAX_HTTP_BYTES) -> dict:
    if not re.match(r"^https?://", url, re.I):
        raise ToolError("only http(s) URLs can be fetched")
    request = urllib.request.Request(url, headers={"User-Agent": "sealed-factory-runtime/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(limit)
            status = response.status
            content_type = response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as problem:
        raise ToolError(f"the server answered HTTP {problem.code} for {url}") from problem
    except urllib.error.URLError as problem:
        raise ToolError(f"the network is not reachable from here: {problem.reason}") from problem
    if not as_text:
        return {"url": url, "status": status, "content_type": content_type, "bytes": len(raw),
                "base64": __import__("base64").b64encode(raw).decode()}
    body = raw.decode("utf-8", "replace")
    return {"url": url, "status": status, "content_type": content_type, "bytes": len(raw),
            "text": body}


def net_http(url: str, method: str = "GET", headers: dict | None = None, body: str = "",
             timeout: int = 30) -> dict:
    request = urllib.request.Request(url, data=body.encode() if body else None, method=method.upper(),
                                     headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(MAX_HTTP_BYTES)
            return {"status": response.status, "headers": dict(response.headers), "bytes": len(raw),
                    "text": raw.decode("utf-8", "replace")}
    except urllib.error.HTTPError as problem:
        raw = problem.read(MAX_HTTP_BYTES)
        return {"status": problem.code, "error": True, "text": raw.decode("utf-8", "replace")}
    except urllib.error.URLError as problem:
        raise ToolError(f"the network is not reachable from here: {problem.reason}") from problem


# --------------------------------------------------------------------------- state and data stores


def memory_store(workspace: Workspace, key: str, value: str, tags: str = "") -> dict:
    path = workspace.state / "memory.jsonl"
    record = {"key": key, "value": value, "tags": [t for t in tags.split(",") if t],
              "at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(record) + "\n")
    return {"stored": True, "key": key, "bytes": len(value)}


def memory_recall(workspace: Workspace, key: str = "", query: str = "", limit: int = 20) -> dict:
    path = workspace.state / "memory.jsonl"
    if not path.exists():
        return {"records": [], "total": 0}
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            records.append(json.loads(line))
        except ValueError:
            continue
    if key:
        selected = [r for r in records if r.get("key") == key]
    elif query:
        words = {w.lower() for w in re.findall(r"\w+", query)}
        scored = []
        for record in records:
            haystack = f'{record.get("key", "")} {record.get("value", "")}'.lower()
            score = sum(1 for word in words if word in haystack)
            if score:
                scored.append((score, record))
        scored.sort(key=lambda pair: -pair[0])
        selected = [record for _score, record in scored]
    else:
        selected = records
    return {"records": selected[-limit:][::-1], "total": len(records)}


def todo_write(workspace: Workspace, items: str) -> dict:
    path = workspace.state / "todos.json"
    entries = [item.strip() for item in items.split("|") if item.strip()]
    if not entries:
        raise ToolError("give at least one item, separated by |")
    existing = json.loads(path.read_text(encoding="utf-8")) if path.exists() else []
    for entry in entries:
        existing.append({"item": entry, "done": False, "at": datetime.now(timezone.utc)
                         .isoformat(timespec="seconds")})
    path.write_text(json.dumps(existing, indent=1), encoding="utf-8")
    return {"items": existing, "open": sum(1 for e in existing if not e["done"])}


def todo_update(workspace: Workspace, index: int, done: bool = True) -> dict:
    path = workspace.state / "todos.json"
    if not path.exists():
        raise ToolError("there is no todo list yet")
    entries = json.loads(path.read_text(encoding="utf-8"))
    if index < 0 or index >= len(entries):
        raise ToolError(f"index {index} is outside the list of {len(entries)} items")
    entries[index]["done"] = done
    path.write_text(json.dumps(entries, indent=1), encoding="utf-8")
    return {"items": entries, "open": sum(1 for e in entries if not e["done"])}


def sqlite_query(workspace: Workspace, database: str, sql: str, limit: int = 200) -> dict:
    target = workspace.resolve(database)
    read_only = sql.strip().lower().startswith(("select", "pragma", "explain"))
    connection = sqlite3.connect(str(target))
    try:
        connection.row_factory = sqlite3.Row
        cursor = connection.execute(sql)
        if read_only:
            rows = [dict(row) for row in cursor.fetchmany(limit)]
            return {"database": workspace.relative(target), "rows": rows,
                    "columns": [d[0] for d in (cursor.description or [])], "read_only": True}
        connection.commit()
        return {"database": workspace.relative(target), "rowcount": cursor.rowcount,
                "read_only": False}
    finally:
        connection.close()


def archive_list(workspace: Workspace, path: str, limit: int = 400) -> dict:
    target = workspace.resolve(path, must_exist=True)
    if zipfile.is_zipfile(target):
        with zipfile.ZipFile(target) as archive:
            entries = [{"name": info.filename, "bytes": info.file_size}
                       for info in archive.infolist()[:limit]]
            return {"path": path, "kind": "zip", "entries": entries,
                    "count": len(archive.infolist())}
    if tarfile.is_tarfile(target):
        with tarfile.open(target) as archive:
            members = archive.getmembers()
            return {"path": path, "kind": "tar", "count": len(members),
                    "entries": [{"name": m.name, "bytes": m.size} for m in members[:limit]]}
    raise ToolError(f"{path} is neither a zip nor a tar archive")


# --------------------------------------------------------------------------- models


def model_ask(gateway_url: str, prompt: str, model: str = "", timeout: int = 120,
              system: str = "") -> dict:
    """One call to the unified gateway. If nothing is reachable, this raises - it never improvises."""
    if not gateway_url:
        raise ToolError("no gateway is configured; start it with `cli.py gateway` or set "
                        "SEALED_GATEWAY_URL")
    payload = {"model": model or "auto",
               "messages": ([{"role": "system", "content": system}] if system else [])
                           + [{"role": "user", "content": prompt}], "stream": False}
    request = urllib.request.Request(_chat_url(gateway_url),
                                     data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json",
                                              "Authorization": f"Bearer {os.environ.get('SEALED_GATEWAY_TOKEN', '')}"},
                                     method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as problem:
        detail = problem.read().decode("utf-8", "replace")[:400]
        raise ToolError(f"the gateway answered HTTP {problem.code}: {detail}") from problem
    except (urllib.error.URLError, OSError) as problem:
        raise ToolError(f"the gateway is not reachable at {gateway_url}: {problem}") from problem
    text = (body.get("choices") or [{}])[0].get("message", {}).get("content", "")
    if not text:
        raise ToolError("the gateway answered without any content")
    return {"model": body.get("model"), "provider": body.get("provider"), "text": text,
            "usage": body.get("usage", {})}


def _chat_url(gateway_url: str) -> str:
    base = gateway_url.rstrip("/")
    return base if base.endswith("/chat/completions") else f"{base}/v1/chat/completions"


def archive_create(workspace: Workspace, path: str, members: str = ".", kind: str = "zip") -> dict:
    """Pack workspace files into a zip or tar. Paths are taken relative to the workspace root."""
    target = workspace.resolve(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    base = workspace.resolve(members, must_exist=True)
    chosen = [base] if base.is_file() else [p for p in sorted(base.rglob("*")) if p.is_file()]
    if not chosen:
        raise ToolError(f"there is nothing to pack under {members}")
    if kind == "zip":
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in chosen:
                archive.write(item, item.relative_to(workspace.root))
    elif kind in ("tar", "tar.gz"):
        mode = "w:gz" if kind == "tar.gz" else "w"
        with tarfile.open(target, mode) as archive:
            for item in chosen:
                archive.add(item, arcname=str(item.relative_to(workspace.root)))
    else:
        raise ToolError("kind must be zip, tar or tar.gz")
    return {"path": workspace.relative(target), "kind": kind, "members": len(chosen),
            "bytes": target.stat().st_size, "sha256": fs_hash(workspace, path)["digest"]}


def data_stats(values: str, label: str = "") -> dict:
    """Descriptive statistics over a list of numbers, without a statistics library import."""
    numbers = []
    for token in re.split(r"[,\s]+", values.strip()):
        if not token:
            continue
        try:
            numbers.append(float(token))
        except ValueError as problem:
            raise ToolError(f"{token!r} is not a number") from problem
    if not numbers:
        raise ToolError("give at least one number")
    ordered = sorted(numbers)
    count = len(ordered)
    mean = sum(ordered) / count
    middle = count // 2
    median = ordered[middle] if count % 2 else (ordered[middle - 1] + ordered[middle]) / 2
    variance = sum((value - mean) ** 2 for value in ordered) / count
    return {"label": label, "count": count, "sum": round(sum(ordered), 6), "min": ordered[0],
            "max": ordered[-1], "mean": round(mean, 6), "median": median,
            "stdev": round(variance ** 0.5, 6),
            "p90": ordered[min(count - 1, int(round(0.9 * (count - 1))))]}


def environment_info() -> dict:
    """What this machine is: interpreter, platform, toolchains. Agents plan better with facts."""
    import platform
    toolchains = {}
    for binary in ("python3", "node", "npm", "git", "cc", "c++", "go", "cargo", "javac", "dotnet",
                   "ruby", "php", "sqlite3", "docker", "uv", "npx"):
        path = shutil.which(binary)
        toolchains[binary] = path or None
    return {"python": sys.version.split()[0], "platform": platform.platform(),
            "machine": platform.machine(), "cpu_count": os.cpu_count(),
            "toolchains": toolchains,
            "present": sorted(name for name, path in toolchains.items() if path),
            "missing": sorted(name for name, path in toolchains.items() if not path)}


def rand_stream(count: int = 8, seed: str = "sealed", low: int = 0, high: int = 1000) -> dict:
    """Deterministic pseudo-random integers: the same seed gives the same stream, on every machine.

    Not for cryptography - it is a reproducible sampler for fuzzing and test data.
    """
    if count < 1 or count > 10_000:
        raise ToolError("count must be between 1 and 10000")
    if high <= low:
        raise ToolError("high must be greater than low")
    values = []
    counter = 0
    span = high - low
    while len(values) < count:
        digest = hashlib.sha256(f"{seed}:{counter}".encode()).digest()
        values.append(low + int.from_bytes(digest[:8], "big") % span)
        counter += 1
    return {"seed": seed, "count": count, "low": low, "high": high, "values": values}


def test_discover(workspace: Workspace, path: str = ".", limit: int = 400) -> dict:
    """Find the test files in a workspace, by the naming conventions of the languages present."""
    base = workspace.resolve(path, must_exist=True)
    patterns = ((r"(^|/)tests?/.*\.(py|js|ts|java|kt|rs|go|rb)$", "directory-named-test"),
                (r"test_.*\.py$|.*_test\.py$", "python"),
                (r"\.(test|spec)\.(js|ts|tsx|jsx)$", "javascript"),
                (r"Test\.java$|Spec\.kt$|Test\.kt$", "jvm"),
                (r"_test\.go$", "go"),
                (r"test_.*\.rb$|_spec\.rb$", "ruby"))
    found = []
    for target in sorted(base.rglob("*")):
        if not target.is_file():
            continue
        if any(part in {".git", "node_modules", "__pycache__", ".sealed"} for part in target.parts):
            continue
        relative = workspace.relative(target)
        for pattern, kind in patterns:
            if re.search(pattern, relative, re.I):
                found.append({"path": relative, "kind": kind, "bytes": target.stat().st_size})
                break
        if len(found) >= limit:
            break
    return {"tests": found, "count": len(found), "truncated": len(found) >= limit}


def port_check(host: str = "127.0.0.1", port: int = 80, timeout: float = 0.5) -> dict:
    return {"host": host, "port": port, "open": port_open(host, port, timeout)}


def time_now(timezone_name: str = "UTC") -> dict:
    if timezone_name == "UTC":
        moment = datetime.now(timezone.utc)
    else:
        moment = datetime.now()
    return {"iso": moment.isoformat(timespec="seconds"), "epoch": int(moment.timestamp()),
            "weekday": moment.strftime("%A"), "timezone": timezone_name}


# --------------------------------------------------------------------------- registry


def _schema(properties: dict, required: list[str] | None = None) -> dict:
    return {"type": "object", "properties": properties, "required": required or []}


_STR = {"type": "string"}
_INT = {"type": "integer"}
_BOOL = {"type": "boolean"}


def build_registry(workspace: Workspace, gateway_url: str = "") -> dict:
    """Every tool, bound to one workspace. Names are stable: agents and the MCP server share them."""
    gateway = gateway_url or os.environ.get("SEALED_GATEWAY_URL", "")
    registry = {
        "fs.list": {"fn": lambda path=".", depth=2: fs_list(workspace, path, depth),
                    "description": "List a directory tree inside the workspace.",
                    "schema": _schema({"path": _STR, "depth": _INT})},
        "fs.read": {"fn": lambda path, start=1, lines=0: fs_read(workspace, path, start, lines),
                    "description": "Read a text file from the workspace.",
                    "schema": _schema({"path": _STR, "start": _INT, "lines": _INT}, ["path"])},
        "fs.write": {"fn": lambda path, text, append=False: fs_write(workspace, path, text, append),
                     "description": "Write or append a text file inside the workspace.",
                     "schema": _schema({"path": _STR, "text": _STR, "append": _BOOL}, ["path", "text"])},
        "fs.mkdir": {"fn": lambda path: fs_mkdir(workspace, path),
                     "description": "Create a directory inside the workspace.",
                     "schema": _schema({"path": _STR}, ["path"])},
        "fs.search": {"fn": lambda pattern, path=".", glob="", limit=100, ignore_case=False:
                      fs_search(workspace, pattern, path, glob, limit, ignore_case),
                      "description": "Regex search across the workspace's text files.",
                      "schema": _schema({"pattern": _STR, "path": _STR, "glob": _STR, "limit": _INT,
                                         "ignore_case": _BOOL}, ["pattern"])},
        "fs.stat": {"fn": lambda path=".": fs_stat(workspace, path),
                    "description": "Size, type and modification time of a path.",
                    "schema": _schema({"path": _STR})},
        "fs.hash": {"fn": lambda path, algorithm="sha256": fs_hash(workspace, path, algorithm),
                    "description": "Hash a file (sha256 by default).",
                    "schema": _schema({"path": _STR, "algorithm": _STR}, ["path"])},
        "fs.copy": {"fn": lambda source, destination: fs_copy(workspace, source, destination),
                    "description": "Copy a file or directory inside the workspace.",
                    "schema": _schema({"source": _STR, "destination": _STR},
                                      ["source", "destination"])},
        "fs.move": {"fn": lambda source, destination: fs_move(workspace, source, destination),
                    "description": "Move a file or directory inside the workspace.",
                    "schema": _schema({"source": _STR, "destination": _STR},
                                      ["source", "destination"])},
        "fs.delete": {"fn": lambda path: fs_delete(workspace, path),
                      "description": "Delete a file or directory inside the workspace.",
                      "schema": _schema({"path": _STR}, ["path"])},
        "text.diff": {"fn": lambda left, right, context=3: text_diff(workspace, left, right, context),
                      "description": "Unified diff between two workspace files.",
                      "schema": _schema({"left": _STR, "right": _STR, "context": _INT},
                                        ["left", "right"])},
        "text.patch": {"fn": lambda path, diff: text_patch(workspace, path, diff),
                       "description": "Apply a unified diff to a workspace file; refuses a partial "
                                      "match.",
                       "schema": _schema({"path": _STR, "diff": _STR}, ["path", "diff"])},
        "text.json": {"fn": lambda path="", text="", query="": text_json(workspace, path, text, query),
                      "description": "Validate, format or query JSON from a file or from text.",
                      "schema": _schema({"path": _STR, "text": _STR, "query": _STR})},
        "text.toml": {"fn": lambda path: text_toml(workspace, path),
                      "description": "Parse a TOML file.",
                      "schema": _schema({"path": _STR}, ["path"])},
        "text.csv": {"fn": lambda path, limit=50: text_csv(workspace, path, limit),
                     "description": "Read a CSV file's columns and rows.",
                     "schema": _schema({"path": _STR, "limit": _INT}, ["path"])},
        "text.regex": {"fn": lambda text, pattern, group=0, limit=200:
                       text_regex(text, pattern, group, limit),
                       "description": "Find regex matches in text.",
                       "schema": _schema({"text": _STR, "pattern": _STR, "group": _INT,
                                          "limit": _INT}, ["text", "pattern"])},
        "text.html": {"fn": lambda path="", text="": text_html(workspace, path, text),
                      "description": "Extract the readable text of an HTML document.",
                      "schema": _schema({"path": _STR, "text": _STR})},
        "text.normalise": {"fn": lambda text: text_normalise(text),
                           "description": "Unicode-normalise text and count its words.",
                           "schema": _schema({"text": _STR}, ["text"])},
        "code.metrics": {"fn": lambda path=".", limit=200: code_metrics(workspace, path, limit),
                         "description": "Line, code, function and class counts per file.",
                         "schema": _schema({"path": _STR, "limit": _INT})},
        "code.todos": {"fn": lambda path=".", limit=100: code_todos(workspace, path, limit),
                       "description": "Find unfinished-work markers (stubs, TODOs, FIXMEs).",
                       "schema": _schema({"path": _STR, "limit": _INT})},
        "code.secrets": {"fn": lambda path=".": code_secrets(workspace, path),
                         "description": "Scan for credential-shaped strings; returns line numbers.",
                         "schema": _schema({"path": _STR})},
        "code.structure": {"fn": lambda path: code_structure(workspace, path),
                           "description": "Symbols and imports of a source file (AST for Python).",
                           "schema": _schema({"path": _STR}, ["path"])},
        "proc.run": {"fn": lambda command, cwd=".", timeout=DEFAULT_TIMEOUT, allow_failure=False:
                     proc_run(workspace, command, cwd, timeout, None, allow_failure),
                     "description": "Run a shell command inside the workspace and report its exit "
                                    "code and output.",
                     "schema": _schema({"command": _STR, "cwd": _STR, "timeout": _INT,
                                        "allow_failure": _BOOL}, ["command"])},
        "git.command": {"fn": lambda arguments, timeout=60: git_command(workspace, arguments, timeout),
                        "description": "Run a git command in the workspace and report its output.",
                        "schema": _schema({"arguments": _STR, "timeout": _INT}, ["arguments"])},
        "net.fetch": {"fn": lambda url, timeout=30: net_fetch(url, timeout),
                      "description": "Fetch a URL. Offline this fails with the reason, never a fake "
                                     "body.",
                      "schema": _schema({"url": _STR, "timeout": _INT}, ["url"])},
        "net.http": {"fn": lambda url, method="GET", headers=None, body="", timeout=30:
                     net_http(url, method, headers, body, timeout),
                     "description": "Make an HTTP request and return status, headers and body.",
                     "schema": _schema({"url": _STR, "method": _STR, "headers": {"type": "object"},
                                        "body": _STR, "timeout": _INT}, ["url"])},
        "memory.store": {"fn": lambda key, value, tags="": memory_store(workspace, key, value, tags),
                         "description": "Persist a fact for later recall.",
                         "schema": _schema({"key": _STR, "value": _STR, "tags": _STR},
                                           ["key", "value"])},
        "memory.recall": {"fn": lambda key="", query="", limit=20:
                          memory_recall(workspace, key, query, limit),
                          "description": "Recall stored facts by key or by keyword.",
                          "schema": _schema({"key": _STR, "query": _STR, "limit": _INT})},
        "plan.write": {"fn": lambda items: todo_write(workspace, items),
                       "description": "Add items to the workspace's task list (separated by |).",
                       "schema": _schema({"items": _STR}, ["items"])},
        "plan.update": {"fn": lambda index, done=True: todo_update(workspace, index, done),
                        "description": "Mark a task list item done or open.",
                        "schema": _schema({"index": _INT, "done": _BOOL}, ["index"])},
        "sqlite.query": {"fn": lambda database, sql, limit=200:
                         sqlite_query(workspace, database, sql, limit),
                         "description": "Run SQL against a SQLite database file in the workspace.",
                         "schema": _schema({"database": _STR, "sql": _STR, "limit": _INT},
                                           ["database", "sql"])},
        "archive.list": {"fn": lambda path, limit=400: archive_list(workspace, path, limit),
                         "description": "List the members of a zip or tar archive.",
                         "schema": _schema({"path": _STR, "limit": _INT}, ["path"])},
        "archive.create": {"fn": lambda path, members=".", kind="zip":
                           archive_create(workspace, path, members, kind),
                           "description": "Pack workspace files into a zip or tar archive.",
                           "schema": _schema({"path": _STR, "members": _STR, "kind": _STR},
                                             ["path"])},
        "data.stats": {"fn": lambda values, label="": data_stats(values, label),
                       "description": "Min, max, mean, median, stdev and p90 of a list of numbers.",
                       "schema": _schema({"values": _STR, "label": _STR}, ["values"])},
        "env.info": {"fn": lambda: environment_info(),
                     "description": "Interpreter, platform and which toolchains are installed.",
                     "schema": _schema({})},
        "rand.stream": {"fn": lambda count=8, seed="sealed", low=0, high=1000:
                        rand_stream(count, seed, low, high),
                        "description": "Deterministic pseudo-random integers from a seed "
                                       "(reproducible test data, not cryptography).",
                        "schema": _schema({"count": _INT, "seed": _STR, "low": _INT, "high": _INT})},
        "test.discover": {"fn": lambda path=".", limit=400: test_discover(workspace, path, limit),
                          "description": "Find test files by the naming conventions of each language.",
                          "schema": _schema({"path": _STR, "limit": _INT})},
        "port.check": {"fn": lambda host="127.0.0.1", port=80: port_check(host, port),
                       "description": "Is a TCP port answering? Used to find local model servers.",
                       "schema": _schema({"host": _STR, "port": _INT})},
        "time.now": {"fn": lambda timezone_name="UTC": time_now(timezone_name),
                     "description": "The current time, ISO-8601 and epoch.",
                     "schema": _schema({"timezone_name": _STR})},
        "model.ask": {"fn": lambda prompt, model="", system="", timeout=120:
                      model_ask(gateway, prompt, model, timeout, system),
                      "description": "Ask a model through the unified gateway. Raises when no model "
                                     "is reachable - it never invents an answer.",
                      "schema": _schema({"prompt": _STR, "model": _STR, "system": _STR,
                                         "timeout": _INT}, ["prompt"])},
    }
    return registry


def tool_names(registry: dict) -> list[str]:
    return sorted(registry)
