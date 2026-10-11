"""Check that Searchhh UI routes, menu items and theme use one real registry."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    """Fail when the launcher bypasses the route/menu/theme contract."""
    activity = (ROOT / "app/src/main/java/info/plateaukao/einkbro/searchhh/SearchhhActivity.kt").read_text()
    route = (ROOT / "app/src/main/java/info/plateaukao/einkbro/searchhh/SearchhhRoute.kt").read_text()
    menu = (ROOT / "app/src/main/java/info/plateaukao/einkbro/searchhh/SearchhhMenuRegistry.kt").read_text()
    theme = (ROOT / "app/src/main/java/info/plateaukao/einkbro/searchhh/SearchhhDesignSystem.kt").read_text()
    findings = []
    routes = set(re.findall(r"^\s+([A-Z][A-Z0-9_]*)\(\"", route, re.MULTILINE))
    registered = set(re.findall(r"SearchhhRoute\.([A-Z][A-Z0-9_]*)", menu))
    if routes != registered:
        findings.append({"rule": "UI-ROUTE-001", "message": "Every implemented route must appear in the menu registry."})
    if "SearchhhTheme" not in activity:
        findings.append({"rule": "UI-THEME-001", "message": "Launcher must use the centralized SearchhhTheme boundary."})
    if "searchhhMenuRegistry" not in activity:
        findings.append({"rule": "UI-MENU-001", "message": "Launcher must render navigation from the centralized menu registry."})
    if "SearchhhTestTags" not in activity:
        findings.append({"rule": "UI-SEMANTICS-001", "message": "Launcher must retain stable semantic test tags."})
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/reports/factory/ui-contract.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {"schema_version": 1, "routes": sorted(routes), "registered_routes": sorted(registered), "findings": findings, "release_approved": False}
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"findings": len(findings), "routes": len(routes), "output": str(output)}))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
