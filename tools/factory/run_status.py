"""Fail-closed status vocabulary and release decision helpers."""
from __future__ import annotations

from typing import Iterable

STATES = frozenset({
    "NOT_SELECTED", "AVAILABLE", "BLOCKED", "RUNNING", "EXECUTED_PASS",
    "EXECUTED_FINDINGS", "EXECUTED_FAIL", "SKIPPED_POLICY", "SKIPPED_DUPLICATE",
    "RECHECK_REQUIRED", "VERIFIED",
})

TERMINAL_PASS = frozenset({"EXECUTED_PASS", "VERIFIED"})
TERMINAL_FAILURE = frozenset({"BLOCKED", "EXECUTED_FINDINGS", "EXECUTED_FAIL", "RECHECK_REQUIRED"})


def validate(state: str) -> str:
    if state not in STATES:
        raise ValueError(f"unknown factory state: {state}")
    return state


def release_approved(states: Iterable[str], *, required_count: int | None = None) -> bool:
    """Return true only when every required selected check explicitly passed."""
    values = [validate(state) for state in states]
    if required_count is not None and len(values) != required_count:
        return False
    return bool(values) and all(state in TERMINAL_PASS for state in values)


def classify_job(result: str | None) -> str:
    if result == "success":
        return "EXECUTED_PASS"
    if result in {"failure", "cancelled", "timed_out"}:
        return "EXECUTED_FAIL"
    if result == "skipped":
        return "SKIPPED_POLICY"
    return "RUNNING"
