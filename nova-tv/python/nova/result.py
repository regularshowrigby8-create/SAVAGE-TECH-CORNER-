"""Explicit success semantics for every Nova CLI result.

The old wrapper always printed ``{"ok": true}`` and returned zero whenever a
tool ran, even when ``result.passed`` or ``result.summary.ok`` was false. This
module makes a nested failure visible to the CLI and to CI. It does not invent
success: a tool must return a JSON object without a failed flag, a failed
summary, contradictions, unsupported claims, broken imports or non-empty
failure findings.
"""
from __future__ import annotations

from typing import Any, Dict, List

FAILURE_LIST_KEYS = (
    "contradicted",
    "unsupported",
    "broken",
    "errors",
    "failures",
    "findings",
    "todos",
    "issues",
    "warnings",
)
NESTED_KEYS = ("result", "results")


def result_failures(result: Any, path: str = "result") -> List[str]:
    """Return explicit reasons why a tool result is not a proven success."""
    if result is None:
        return [f"{path}: tool returned no result"]
    if isinstance(result, bool):
        return [] if result else [f"{path}: false"]
    if not isinstance(result, dict):
        return [f"{path}: result is {type(result).__name__}, not a JSON object"]

    failures: List[str] = []
    if result.get("ok") is False:
        failures.append(f"{path}.ok: false")
    if result.get("passed") is False:
        failures.append(f"{path}.passed: false")
    if result.get("failed") is True:
        failures.append(f"{path}.failed: true")
    if result.get("error"):
        failures.append(f"{path}.error: {result['error']!r}")
    summary = result.get("summary")
    if isinstance(summary, dict):
        if summary.get("ok") is False:
            failures.append(f"{path}.summary.ok: false")
        for key in FAILURE_LIST_KEYS:
            if summary.get(key):
                failures.append(f"{path}.summary.{key}: non-empty")
    counts = result.get("counts")
    if isinstance(counts, dict):
        for key in ("contradicted", "unverified", "unsupported", "failed"):
            if isinstance(counts.get(key), (int, float)) and counts[key] > 0:
                failures.append(f"{path}.counts.{key}: {counts[key]}")
    for key in FAILURE_LIST_KEYS:
        value = result.get(key)
        if isinstance(value, list) and value:
            failures.append(f"{path}.{key}: {len(value)} item(s)")
        elif isinstance(value, dict) and value:
            failures.append(f"{path}.{key}: non-empty object")
        elif isinstance(value, str) and value:
            failures.append(f"{path}.{key}: {value!r}")
    for nested in NESTED_KEYS:
        if nested in result:
            child = result[nested]
            if isinstance(child, dict):
                failures.extend(result_failures(child, f"{path}.{nested}"))
            elif isinstance(child, list):
                for index, item in enumerate(child):
                    failures.extend(result_failures(item, f"{path}.{nested}[{index}]"))
    return failures


def result_is_ok(result: Any) -> bool:
    return not result_failures(result)


def payload_is_ok(payload: Dict[str, Any]) -> bool:
    """Top-level wrapper AND nested result must both prove success."""
    if payload.get("ok") is not True:
        return False
    if "result" not in payload:
        return False
    return result_is_ok(payload["result"])
