"""AI provider client.

Speaks the OpenAI-compatible chat-completions shape, which is what every hosted
provider and every local gateway exposes (Ollama, LM Studio, vLLM, OpenRouter,
Together, Groq, and OpenAI itself). Nothing here is hard-wired to one vendor.

Model aliases are resolved through ``models.json`` so "darkgpt" or "worm-gpt"
point at whatever real endpoint you configure, instead of pretending a model
called that exists at a public URL.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from .http import FetchError, fetch

DEFAULT_SYSTEM = (
    "You are Nova's engineering assistant. Answer with concrete, minimal, correct "
    "output. When asked for code, return only the code and no commentary."
)


@dataclass
class ModelSpec:
    alias: str
    model: str
    base_url: str
    description: str = ""

    @property
    def endpoint(self) -> str:
        return self.base_url.rstrip("/") + "/chat/completions"


def load_model_registry(path: Optional[Path] = None) -> Dict[str, ModelSpec]:
    """Load alias -> ModelSpec from models.json (or the built-in defaults)."""
    path = path or Path(__file__).resolve().parent.parent / "models.json"
    specs: Dict[str, ModelSpec] = {}
    if path.exists():
        try:
            data = json.loads(path.read_text("utf-8"))
        except (OSError, ValueError):
            data = {}
        for alias, entry in (data.get("models") or {}).items():
            if not isinstance(entry, dict) or not entry.get("model"):
                continue
            specs[alias] = ModelSpec(
                alias=alias,
                model=entry["model"],
                base_url=entry.get("base_url", "https://api.openai.com/v1"),
                description=entry.get("description", ""),
            )
    return specs


def resolve_model(alias: str, default_model: str, default_base: str, registry: Dict[str, ModelSpec]) -> ModelSpec:
    """Map an alias to a spec, falling back to the configured default."""
    if alias in registry:
        return registry[alias]
    return ModelSpec(alias=alias, model=default_model, base_url=default_base)


class AI:
    def __init__(
        self,
        api_key: Optional[str],
        base_url: str,
        model: str = "gpt-4o-mini",
        *,
        timeout: float = 60.0,
        registry: Optional[Dict[str, ModelSpec]] = None,
    ):
        self.api_key = api_key
        self.base_url = base_url
        self.model = model
        self.timeout = timeout
        self.registry = registry if registry is not None else load_model_registry()

    @property
    def available(self) -> bool:
        return bool(self.api_key)

    def complete(self, prompt: str, *, system: str = DEFAULT_SYSTEM, model: Optional[str] = None, max_tokens: int = 1200) -> str:
        if not self.available:
            raise RuntimeError("AI_API_KEY is not set; run `nova ai-status` to see what is configured")
        spec = resolve_model(model or self.model, self.model, self.base_url, self.registry)
        payload = {
            "model": spec.model,
            "max_tokens": max_tokens,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt},
            ],
        }
        try:
            response = fetch(
                spec.endpoint,
                headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
                timeout=self.timeout,
                retries=2,
                method="POST",
                data=json.dumps(payload).encode("utf-8"),
            )
        except FetchError as error:
            raise RuntimeError(f"AI call failed: {error}") from error
        body = response.json()
        choices = body.get("choices") if isinstance(body, dict) else None
        if not choices:
            raise RuntimeError(f"AI response had no choices: {str(body)[:200]}")
        message = choices[0].get("message") or {}
        return (message.get("content") or "").strip()

    def review_patch(self, diff: str, *, model: Optional[str] = None) -> str:
        prompt = (
            "Review this unified diff for a streaming player codebase. "
            "Report only real defects: crashes, resource leaks, security issues, "
            "or behaviour changes that break playback. If it is fine, reply 'LGTM'.\n\n"
            f"```diff\n{diff[:12000]}\n```"
        )
        return self.complete(prompt, model=model, max_tokens=800)

    def propose_test(self, source: str, *, model: Optional[str] = None) -> str:
        prompt = (
            "Write one focused unittest for this Python module. Return only code, "
            "no explanation, using only the standard library.\n\n"
            f"```python\n{source[:12000]}\n```"
        )
        return self.complete(prompt, model=model, max_tokens=900)


def status(api_key: Optional[str], base_url: str, model: str) -> Dict[str, Any]:
    """What the AI layer is actually able to do, without making a call."""
    registry = load_model_registry()
    return {
        "configured": bool(api_key),
        "base_url": base_url,
        "default_model": model,
        "aliases": {
            alias: {"model": spec.model, "base_url": spec.base_url, "description": spec.description}
            for alias, spec in sorted(registry.items())
        },
        "note": (
            "Aliases resolve to whatever endpoint you set. A model branded "
            "'darkgpt' or 'worm-gpt' only works if that alias points at a real "
            "server; no public endpoint by that name exists."
        ),
    }
