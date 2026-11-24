#!/usr/bin/env python3
"""Check that claims in the docs are backed by evidence, not by memory."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry

REGISTRY = Registry()


@REGISTRY.register(summary="Verify documented claims against files and the GitHub API", group="verify")
def verify(settings=None):
    from nova import hallucination
    from nova.config import ROOT, load_settings
    from nova.github import GitHub

    settings = settings or load_settings()
    github = None
    if settings.has_github:
        github = GitHub(settings.github_token, settings.user_agent, settings.timeout, settings.github_ref)

    docs = sorted((ROOT / "docs").glob("*.md"))
    all_results = []
    for doc in docs:
        all_results.extend(hallucination.verify_document(doc, root=ROOT.parent, github=github))

    summary = hallucination.summarise(all_results)
    return {
        "documents": len(docs),
        "summary": summary,
        "unsupported": [r.as_dict() for r in all_results if not r.ok][:40],
        "ok": bool(summary.get("ok")),
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Verify documented claims", REGISTRY))
