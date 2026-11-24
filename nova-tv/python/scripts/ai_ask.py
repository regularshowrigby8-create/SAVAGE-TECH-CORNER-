#!/usr/bin/env python3
"""Ask the configured model one question."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="One-shot question to the configured model", group="ai", needs_ai=True)
def ask(settings=None, prompt="Reply with OK"):
    from nova.ai import AI
    settings = settings or load_settings()
    return AI(settings.ai_api_key, settings.ai_base_url, settings.ai_model).complete(prompt)


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Ask the configured model one question.", REGISTRY))

