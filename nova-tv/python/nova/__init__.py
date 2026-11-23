"""Nova TV — provider-agnostic streaming client tooling.

A deliberately small core: config, secrets, HTTP, caching, and a plugin registry.
Every script in ``scripts/`` is a thin command over this core so the same logic is
testable without network access.
"""

__version__ = "1.0.0"
