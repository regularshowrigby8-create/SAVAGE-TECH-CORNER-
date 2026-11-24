#!/usr/bin/env python3
"""Have the model review a unified diff."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Review a diff with the configured model", group="ai", needs_ai=True)
def review(diff_path="", settings=None):
    from pathlib import Path as P
    from nova.ai import AI
    settings = settings or load_settings()
    diff = P(diff_path).read_text("utf-8") if diff_path else ""
    return AI(settings.ai_api_key, settings.ai_base_url, settings.ai_model).review_patch(diff)


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Have the model review a unified diff.", REGISTRY))

