#!/usr/bin/env python3
"""Have the four models review the UI design, and fail honestly if they cannot.

ZeroKey is a localhost proxy that is not running in CI or in this sandbox, so the
default outcome of this script is `reachable: false` and a hint -- never a
fabricated review. That is deliberate: a review nobody wrote is worse than no
review, because it looks like evidence.

When ZeroKey *is* running (`github.com/downloaddoctor/zerokey`), each model is
asked the same three questions about the UI, and their answers are reported side
by side. Agreement is reported as agreement, not as truth: the models can only
tell us whether the design is coherent, not whether it compiles. Compilation is
CI's job and nothing here replaces it.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

REGISTRY = Registry()

QUESTIONS = (
    "You are reviewing an Android TV user interface for a media app. "
    "Answer in at most 60 words, concretely, no preamble.",
    "1. Name the single most likely usability failure in a 10-foot TV UI that "
    "uses a 96dp icon rail, 168dp posters, 18sp body text and a 48dp safe margin.",
    "2. For a dark palette of background #0B0E14, surface #151A24, accent "
    "#4C8DFF and text #F5F7FA, what is the most common contrast mistake, and "
    "does one accent colour risk losing its meaning?",
    "3. A stream picker shows only directly decodable streams and dims the rest. "
    "Is dimming-and-still-focusable better than removing, for a D-pad user? Why?",
)


def _prompt(design_summary: str) -> str:
    return "\n".join(QUESTIONS) + "\n\nDESIGN:\n" + design_summary


def _design_summary() -> str:
    """Read the real design doc rather than a hand-written summary."""
    doc = Path(__file__).resolve().parent.parent.parent / "docs" / "UI-REFERENCE.md"
    if not doc.exists():
        return "(UI-REFERENCE.md not found)"
    text = doc.read_text(encoding="utf-8")
    # The rules and the honest-gap table are what a reviewer should judge, not
    # the whole document.
    return text[:6000]


@REGISTRY.register(summary="Four models review the UI design", group="verify")
def review_ui(base_url: str = "http://localhost:7250/v1", settings=None):
    from nova import zerokey as zk

    status = zk.status(base_url)
    if not status["reachable"]:
        return {
            "reachable": False,
            "hint": (
                "ZeroKey is not running, so no model reviewed anything. "
                "Start it from github.com/downloaddoctor/zerokey; it serves "
                f"{base_url}. This is reported rather than simulated."
            ),
            "status": status,
            # Returning this deliberately: a caller that only checks `ok` will
            # still see reachable=false and can refuse to print a review.
            "reviews": {},
            "agreement": None,
        }

    client = zk.ZeroKey(base_url)
    prompt = _prompt(_design_summary())
    answers: dict[str, str] = {}
    failures: dict[str, str] = {}

    for model in status["models"]:
        try:
            answers[model] = client.complete(model, prompt)
        except Exception as error:  # noqa: BLE001 - one model failing is data
            failures[model] = str(error)

    return {
        "reachable": True,
        "providers": status["providers_found"],
        "answered": sorted(answers),
        "failed": failures,
        "reviews": answers,
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Four models review the UI design.", REGISTRY))
