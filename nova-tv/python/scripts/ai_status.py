#!/usr/bin/env python3
"""Show which AI providers and aliases are configured."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="AI configuration status", group="ai")
def status(settings=None):
    from nova.ai import status as ai_status
    settings = settings or load_settings()
    return ai_status(settings.ai_api_key, settings.ai_base_url, settings.ai_model)


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Show which AI providers and aliases are configured.", REGISTRY))

