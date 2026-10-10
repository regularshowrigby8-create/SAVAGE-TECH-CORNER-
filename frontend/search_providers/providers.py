"""Android Browser Frontend: Search Providers Module."""

from typing import Any, Dict, List


class SearchProviderRegistry:
    """Manages search engine templates and query formatting."""

    def __init__(self) -> None:
        self.providers: Dict[str, str] = {
            "duckduckgo": "https://duckduckgo.com/?q={query}",
            "google": "https://www.google.com/search?q={query}",
            "ecosia": "https://www.ecosia.org/search?q={query}",
            "savage": "https://savage-tech-corner.io/search?q={query}",
        }
        self.default_provider: str = "duckduckgo"

    def format_query(self, query: str, provider: str = "") -> str:
        prov = provider or self.default_provider
        template = self.providers.get(prov, self.providers[self.default_provider])
        import urllib.parse
        encoded = urllib.parse.quote_plus(query)
        return template.format(query=encoded)
