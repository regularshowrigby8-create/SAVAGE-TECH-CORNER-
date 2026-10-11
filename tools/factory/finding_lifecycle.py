"""Fail-closed finding lifecycle used by factory repair and recheck packets."""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

STATES = {
    "OPEN", "ASSIGNED", "AUTOFIX_ATTEMPTED", "RECHECK_REQUIRED",
    "FIXED_PENDING_REVIEW", "VERIFIED", "BLOCKED", "WONT_FIX_APPROVED", "REOPENED",
}
TRANSITIONS = {
    "OPEN": {"ASSIGNED", "BLOCKED", "WONT_FIX_APPROVED"},
    "ASSIGNED": {"AUTOFIX_ATTEMPTED", "RECHECK_REQUIRED", "BLOCKED", "WONT_FIX_APPROVED"},
    "AUTOFIX_ATTEMPTED": {"RECHECK_REQUIRED", "BLOCKED"},
    "RECHECK_REQUIRED": {"FIXED_PENDING_REVIEW", "OPEN", "BLOCKED"},
    "FIXED_PENDING_REVIEW": {"VERIFIED", "REOPENED", "BLOCKED"},
    "VERIFIED": {"REOPENED"},
    "BLOCKED": {"OPEN", "ASSIGNED"},
    "WONT_FIX_APPROVED": {"REOPENED"},
    "REOPENED": {"ASSIGNED", "BLOCKED"},
}
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


def transition(packet: dict, target: str, *, actor: str, reason: str = "", evidence: list | None = None) -> dict:
    """Return a new packet or raise; never silently repairs an invalid packet."""
    current = packet.get("state")
    finding_id = packet.get("id")
    if not isinstance(finding_id, str) or not SAFE_ID.fullmatch(finding_id):
        raise ValueError("finding id must be a bounded safe identifier")
    if current not in STATES or target not in STATES:
        raise ValueError("unknown lifecycle state")
    if target not in TRANSITIONS[current]:
        raise ValueError(f"invalid transition {current} -> {target}")
    if not actor.strip():
        raise ValueError("actor is required")
    if target in {"BLOCKED", "WONT_FIX_APPROVED", "REOPENED"} and len(reason.strip()) < 10:
        raise ValueError("blocked, wont-fix and reopened transitions require a reason")
    if target == "VERIFIED":
        if packet.get("required_checks_complete") is not True:
            raise ValueError("verification requires required_checks_complete=true")
        if not evidence:
            raise ValueError("verification requires non-empty evidence")
    updated = dict(packet)
    updated["state"] = target
    updated["release_approved"] = False
    updated["updated_at"] = datetime.now(timezone.utc).isoformat()
    updated["history"] = list(packet.get("history", [])) + [{
        "from": current, "to": target, "actor": actor.strip(), "reason": reason.strip(),
        "evidence_count": len(evidence or []), "at": updated["updated_at"],
    }]
    if evidence is not None:
        updated["evidence"] = evidence
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("packet", type=Path)
    parser.add_argument("target", choices=sorted(STATES))
    parser.add_argument("--actor", required=True)
    parser.add_argument("--reason", default="")
    parser.add_argument("--evidence", action="append", default=[])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    packet = json.loads(args.packet.read_text())
    updated = transition(packet, args.target, actor=args.actor, reason=args.reason, evidence=args.evidence)
    output = args.output or args.packet
    output.write_text(json.dumps(updated, indent=2) + "\n")
    print(json.dumps(updated, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
