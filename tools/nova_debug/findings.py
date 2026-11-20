"""Finding model, severity, and the report writers.

A finding is only useful if a person can check it without re-deriving it, so
each one carries the evidence text (usually the offending source line) as well
as the location. Reports are written in three forms: JSON (machine), Markdown
(human) and a short console summary.

Severity meanings, used consistently by every rule:

  ERROR  -- provably wrong: the app cannot compile, cannot start, or a claim it
            makes is measurably false. Requires exact evidence.
  WARN   -- a defect the static rules can see but cannot prove is fatal, or a
            risk worth a look. Never counted as a pass or a fail.
  INFO   -- a fact worth recording (what is in the APK, what was not checked).
  SKIP   -- a check that could not run here, with the reason. These are listed
            so nobody mistakes "not checked" for "passed".
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

ERROR = "error"
WARN = "warn"
INFO = "info"
SKIP = "skip"

_RANK = {ERROR: 0, WARN: 1, INFO: 2, SKIP: 3}


@dataclass
class Finding:
    id: str                 # stable rule id, e.g. KOTLIN_UNRESOLVED_REF
    severity: str
    file: str               # repo-relative path, or "apk:<name>" for APK facts
    line: int
    message: str
    evidence: str = ""
    rule: str = ""          # human name of the check that produced it
    fix: str = ""

    def key(self) -> tuple:
        return (self.file, self.line, self.id)

    def as_dict(self) -> Dict[str, Any]:
        return {
            "severity": self.severity,
            "id": self.id,
            "rule": self.rule,
            "file": self.file,
            "line": self.line,
            "message": self.message,
            "evidence": self.evidence,
            "fix": self.fix,
        }

    def __str__(self) -> str:
        where = f"{self.file}:{self.line}" if self.line else self.file
        return f"[{self.severity}] {where} {self.id}: {self.message}"


class Report:
    """An ordered bag of findings for one audit run."""

    def __init__(self, title: str) -> None:
        self.title = title
        self.findings: List[Finding] = []
        self.meta: Dict[str, Any] = {}
        self._seen: set = set()

    # -- collection ---------------------------------------------------------
    def add(self, finding: Finding) -> None:
        k = finding.key()
        if k in self._seen:
            return
        self._seen.add(k)
        self.findings.append(finding)

    def extend(self, findings: Iterable[Finding]) -> None:
        for f in findings:
            self.add(f)

    def info(self, id: str, message: str, file: str = "", line: int = 0, evidence: str = "") -> None:
        self.add(Finding(id=id, severity=INFO, file=file, line=line, message=message, evidence=evidence))

    def skip(self, id: str, message: str, file: str = "") -> None:
        self.add(Finding(id=id, severity=SKIP, file=file, line=0, message=message))

    # -- queries ------------------------------------------------------------
    def of_severity(self, severity: str) -> List[Finding]:
        return [f for f in self.findings if f.severity == severity]

    @property
    def errors(self) -> List[Finding]:
        return self.of_severity(ERROR)

    @property
    def warnings(self) -> List[Finding]:
        return self.of_severity(WARN)

    @property
    def skips(self) -> List[Finding]:
        return self.of_severity(SKIP)

    def counts(self) -> Dict[str, int]:
        out = {ERROR: 0, WARN: 0, INFO: 0, SKIP: 0}
        for f in self.findings:
            out[f.severity] = out.get(f.severity, 0) + 1
        return out

    def sorted(self) -> List[Finding]:
        return sorted(self.findings, key=lambda f: (_RANK.get(f.severity, 9), f.file, f.line, f.id))

    # -- writers ------------------------------------------------------------
    def to_json(self, path: Path) -> None:
        payload = {
            "title": self.title,
            "meta": self.meta,
            "counts": self.counts(),
            "findings": [f.as_dict() for f in self.sorted()],
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def to_markdown(self, path: Path, max_per_severity: int = 200) -> None:
        c = self.counts()
        lines = [f"# {self.title}", ""]
        for k, v in self.meta.items():
            lines.append(f"- **{k}**: {v}")
        lines += ["", f"**{c[ERROR]} error(s), {c[WARN]} warning(s), "
                      f"{c.get(INFO, 0)} info, {c.get(SKIP, 0)} skipped**", ""]
        for severity, heading in ((ERROR, "Errors (fix these)"),
                                  (WARN, "Warnings (check these)"),
                                  (INFO, "Facts and observations"),
                                  (SKIP, "Not checked (do not read as a pass)")):
            items = [f for f in self.sorted() if f.severity == severity]
            if not items:
                continue
            lines += [f"## {heading}", ""]
            for f in items[:max_per_severity]:
                where = f"{f.file}:{f.line}" if f.line else f.file
                lines.append(f"- **{f.id}** — `{where}` — {f.message}")
                if f.evidence:
                    lines.append("")
                    lines.append("  ```")
                    for ev in str(f.evidence).splitlines():
                        lines.append(f"  {ev}")
                    lines.append("  ```")
                if f.fix:
                    lines.append(f"  - fix: {f.fix}")
            if len(items) > max_per_severity:
                lines.append(f"- … and {len(items) - max_per_severity} more (see the JSON report)")
            lines.append("")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def summary(self) -> str:
        c = self.counts()
        return (f"{c[ERROR]} error(s), {c[WARN]} warning(s), "
                f"{c.get(INFO, 0)} info, {c.get(SKIP, 0)} skipped")


def parse_keep_rules(path: Path) -> Dict[str, Any]:
    """Read ProGuard/R8 ``-keep`` class rules so dead-code checks honour them.

    Only class-level keeps matter for the dead-code question: a class that R8 is
    told to keep is reachable by definition, so reporting it as dead would be a
    false positive. Rules are matched as plain prefixes (``dev.nova.tv.**``).
    """
    kept: List[str] = []
    if not path.is_file():
        return {"classes": kept, "source": str(path)}
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line.startswith("-keep") or "class " not in line:
            continue
        after = line.split("class ", 1)[1].strip()
        # Drop modifiers such as `allowobfuscation` / `includedescriptorclasses`.
        after = after.lstrip("{}").strip()
        after = after.split()[0] if after else ""
        name = after.lstrip("!").rstrip(";")
        if name:
            kept.append(name)
    return {"classes": kept, "source": str(path)}


def kept_by_proguard(class_name: str, keeps: Sequence[str]) -> bool:
    """Does a ``-keep`` rule cover this class (``.``-separated internal name)?"""
    for rule in keeps:
        if rule == class_name:
            return True
        if rule.endswith(".**"):
            prefix = rule[:-3]
            if class_name == prefix or class_name.startswith(prefix + "."):
                return True
        if rule.endswith(".*"):
            prefix = rule[:-2]
            if class_name.startswith(prefix + ".") and "." not in class_name[len(prefix) + 1:]:
                return True
    return False


@dataclass
class ToolEnv:
    """What this machine can and cannot do, recorded in every report."""

    root: Path
    nova_root: Optional[Path] = None
    java: Optional[str] = None
    android_sdk: Optional[str] = None
    python: str = ""
    extra: Dict[str, Any] = field(default_factory=dict)

    def as_meta(self) -> Dict[str, Any]:
        return {
            "repository root": str(self.root),
            "nova sources": str(self.nova_root) if self.nova_root else "not present",
            "java": self.java or "absent (no JVM compile, no R8 mapping resolution)",
            "android sdk": self.android_sdk or "absent (no aapt/apksigner/zipalign runtime checks)",
            "python": self.python,
            **self.extra,
        }
