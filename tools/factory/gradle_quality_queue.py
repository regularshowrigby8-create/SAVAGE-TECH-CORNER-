"""Normalize Gradle KtLint/Detekt XML into bounded repair packets.

The parser is report-only. It never edits source, suppresses findings, or
promotes a failed Gradle task to PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def severity(rule: str, source: str) -> str:
    if source == "detekt" and rule in {"SwallowedException", "TooGenericExceptionCaught", "EmptyCatchBlock"}:
        return "HIGH"
    if source == "detekt" and rule in {"LongMethod", "CyclomaticComplexMethod", "LongParameterList"}:
        return "MEDIUM"
    return "STYLE" if source == "ktlint" else "MEDIUM"


def parse_report(path: Path, source: str) -> list[dict]:
    findings = []
    root = ET.parse(path).getroot()
    for node in root.iter():
        if node.tag not in {"error", "issue"}:
            continue
        file_name = node.attrib.get("filename") or node.attrib.get("file") or node.attrib.get("location")
        rule = node.attrib.get("source") or node.attrib.get("rule") or node.attrib.get("id") or "unclassified"
        line = int(node.attrib.get("line", "0") or 0)
        column = int(node.attrib.get("column", "0") or 0)
        message = node.attrib.get("message", "Unspecified Gradle finding")
        fingerprint = hashlib.sha256(f"{source}|{rule}|{file_name}|{line}|{message}".encode()).hexdigest()[:20]
        findings.append({"id": f"{source}:{fingerprint}", "source": source, "rule": rule,
                         "path": file_name, "line": line, "column": column,
                         "severity": severity(rule, source), "message": message,
                         "status": "OPEN", "auto_fix": source == "ktlint",
                         "requires_agent": source == "detekt", "requires_human": False})
    return findings


def packets(findings: list[dict]) -> list[dict]:
    grouped = {}
    for finding in findings:
        key = (finding["source"], finding.get("rule", "unclassified"))
        grouped.setdefault(key, []).append(finding)
    result = []
    number = 1
    for (source, rule), group in sorted(grouped.items()):
        for start in range(0, len(group), 25):
            batch = group[start:start + 25]
            files = sorted({item.get("path") for item in batch if item.get("path")})
            result.append({"id": f"GRADLE-{number:04d}", "category": source,
                           "scope": files[:10], "finding_ids": [item["id"] for item in batch],
                           "rule": rule, "priority": "P2" if source == "ktlint" else "P1",
                           "max_files": 10, "max_changed_lines": 300,
                           "required_checks": [source, "compile", "unit-tests"],
                           "status": "READY"})
            number += 1
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ktlint", type=Path, nargs="*")
    parser.add_argument("--detekt", type=Path, nargs="*")
    parser.add_argument("--output", type=Path, default=Path("build/reports/factory/gradle-remediation-queue.json"))
    args = parser.parse_args()
    findings = []
    errors = []
    for source, reports in (("ktlint", args.ktlint or []), ("detekt", args.detekt or [])):
        for report in reports:
            try:
                findings.extend(parse_report(report, source))
            except (OSError, ET.ParseError, ValueError) as error:
                errors.append({"source": source, "report": str(report), "error": type(error).__name__})
    result = {"schema_version": 1, "status": "OPEN" if findings or errors else "NO_REPORTS",
              "release_approved": False, "finding_count": len(findings),
              "findings": findings, "packets": packets(findings), "errors": errors}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "findings": len(findings), "packets": len(result["packets"]), "release_approved": False}, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
