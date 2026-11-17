"""Configuration loading.

Resolution order (first wins):
  1. explicit argument passed by the caller
  2. environment variable
  3. ``nova.toml`` in the project root
  4. documented default

No secret ever lives in the repository. ``nova.toml.example`` shows the shape and
``nova env`` prints the resolved view with secrets masked.
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Optional

if sys.version_info >= (3, 11):
    import tomllib
else:  # pragma: no cover - only on older interpreters
    try:
        import tomli as tomllib  # type: ignore
    except ImportError:  # pragma: no cover
        tomllib = None  # type: ignore

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_CONFIG = ROOT / "nova.toml"


def _load_toml(path: Path) -> Dict[str, Any]:
    if not path.exists() or tomllib is None:
        return {}
    with path.open("rb") as handle:
        return tomllib.load(handle)


@dataclass
class Settings:
    """Resolved settings for one run."""

    github_token: Optional[str] = None
    github_owner: str = "regularshowrigby8-create"
    github_repo: str = "nova-tv"
    # GitHub's contents API resolves "HEAD" to the repository's *default* branch,
    # which is not the branch this work lives on. Naming the ref explicitly is
    # what stops reads silently returning another branch's files.
    github_ref: str = "HEAD"
    ai_base_url: str = "https://api.openai.com/v1"
    ai_model: str = "gpt-4o-mini"
    ai_api_key: Optional[str] = None
    user_agent: str = "NovaTV/1.0 (+https://github.com/regularshowrigby8-create/nova-tv)"
    cache_dir: Path = field(default_factory=lambda: ROOT / ".nova-cache")
    timeout: float = 20.0
    max_retries: int = 3
    raw: Dict[str, Any] = field(default_factory=dict)

    @property
    def has_github(self) -> bool:
        return bool(self.github_token)

    @property
    def has_ai(self) -> bool:
        return bool(self.ai_api_key)


def _pick(env_name: str, toml_value: Any, default: Any) -> Any:
    value = os.environ.get(env_name)
    if value not in (None, ""):
        return value
    if toml_value not in (None, ""):
        return toml_value
    return default


def load_settings(config_path: Optional[Path] = None, env: Optional[Dict[str, str]] = None) -> Settings:
    """Build a :class:`Settings` from TOML + environment.

    ``env`` is injectable so tests never touch the real environment.
    """
    if env is not None:
        previous = dict(os.environ)
        os.environ.clear()
        os.environ.update(env)
        try:
            return _load_settings(config_path)
        finally:
            os.environ.clear()
            os.environ.update(previous)
    return _load_settings(config_path)


def _load_settings(config_path: Optional[Path]) -> Settings:
    data = _load_toml(config_path or DEFAULT_CONFIG)
    github = data.get("github", {}) or {}
    ai = data.get("ai", {}) or {}
    net = data.get("network", {}) or {}

    cache_raw = _pick("NOVA_CACHE_DIR", data.get("cache_dir"), str(ROOT / ".nova-cache"))
    settings = Settings(
        github_token=_pick("GITHUB_TOKEN", github.get("token"), None),
        github_owner=_pick("NOVA_GITHUB_OWNER", github.get("owner"), "regularshowrigby8-create"),
        github_repo=_pick("NOVA_GITHUB_REPO", github.get("repo"), "nova-tv"),
        github_ref=_pick("NOVA_GITHUB_REF", github.get("ref"), "HEAD"),
        ai_base_url=_pick("AI_BASE_URL", ai.get("base_url"), "https://api.openai.com/v1"),
        ai_model=_pick("AI_MODEL", ai.get("model"), "gpt-4o-mini"),
        ai_api_key=_pick("AI_API_KEY", ai.get("api_key"), None),
        user_agent=_pick("NOVA_USER_AGENT", net.get("user_agent"), Settings.user_agent),
        cache_dir=Path(str(cache_raw)).expanduser(),
        timeout=float(_pick("NOVA_TIMEOUT", net.get("timeout"), 20.0)),
        max_retries=int(_pick("NOVA_MAX_RETRIES", net.get("max_retries"), 3)),
        raw=data,
    )
    return settings


SECRET_MARKERS = ("token", "key", "secret", "password")


def mask(value: Optional[str]) -> str:
    """Return a display-safe form of a secret."""
    if not value:
        return "<unset>"
    text = str(value)
    if len(text) <= 8:
        return "*" * len(text)
    return f"{text[:4]}…{text[-4:]} ({len(text)} chars)"


def is_secret(name: str) -> bool:
    lowered = name.lower()
    return any(marker in lowered for marker in SECRET_MARKERS)


def redacted_view(settings: Settings) -> Dict[str, str]:
    """The settings rendered for logs — secrets masked, never raw."""
    view = {
        "github.owner": settings.github_owner,
        "github.repo": settings.github_repo,
        "github.ref": settings.github_ref,
        "github.token": mask(settings.github_token),
        "ai.base_url": settings.ai_base_url,
        "ai.model": settings.ai_model,
        "ai.api_key": mask(settings.ai_api_key),
        "cache_dir": str(settings.cache_dir),
        "timeout": str(settings.timeout),
        "max_retries": str(settings.max_retries),
    }
    return view
