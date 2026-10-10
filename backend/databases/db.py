"""Browser Backend: Databases Module."""

from typing import Any, Dict, List, Optional


class DatabaseManager:
    """Manages transactional and document databases for user data and crawl indexes."""

    def __init__(self) -> None:
        self.tables: Dict[str, List[Dict[str, Any]]] = {
            "users": [],
            "crawl_index": [],
            "telemetry": [],
        }

    def insert(self, table: str, record: Dict[str, Any]) -> Dict[str, Any]:
        if table not in self.tables:
            self.tables[table] = []
        self.tables[table].append(record)
        return {"inserted": True, "table": table}

    def query(self, table: str) -> List[Dict[str, Any]]:
        return list(self.tables.get(table, []))
