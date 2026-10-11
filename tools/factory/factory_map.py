"""Render the factory map without coupling the runner to orchestration imports."""

import html
import json
from pathlib import Path

from tools.factory.safety import validate_tools

ROOT = Path(__file__).resolve().parents[2]


def read_snapshot() -> dict:
    """Choose a committed, source-labeled execution snapshot, never pretend live status."""
    path = ROOT / "docs/factory/verification-FACTORY002.json"
    if not path.is_file():
        path = ROOT / "docs/factory/verification-25f9401.json"
    return json.loads(path.read_text())


def write_overview(path: Path, tools: list[dict] | None = None) -> None:
    """Render an offline map with explicit historical evidence and deferred work."""
    tools = (
        tools
        if tools is not None
        else validate_tools(json.loads((ROOT / "tools/factory/tools.json").read_text()))
    )
    repos = json.loads((ROOT / "tools/factory/repositories.json").read_text())
    baseline = read_snapshot()
    source = baseline["source_commit"][:7]
    history = {
        r["id"]: r["status"]
        for page in baseline["pages"]
        for r in page["payload"]["records"]
    }
    esc = html.escape
    rows = []
    for tool in tools:
        status = esc(history.get(tool["id"], "UNVERIFIED"), quote=True)
        repository = repos["repositories"][tool["id"]]["repository"]
        if not repository.startswith("https://"):
            raise ValueError("Repository links require HTTPS")
        label = (
            "Snapshot " + source if tool["id"] in history else "No snapshot execution"
        )
        rows.append(
            f'<tr data-state="{status}"><th scope="row">{esc(tool["name"])}</th>'
            f"<td>{esc(tool['purpose'])}</td>"
            f'<td><a href="{esc(repository, quote=True)}" target="_blank" '
            f'rel="noopener noreferrer">{esc(repository)}</a></td>'
            f"<td>{esc(', '.join(tool['profiles']))}</td><td>{status}"
            f"<small>{label}</small></td></tr>"
        )
    planned = "".join(
        f"<article><h3>{esc(item['tool'])} <small>PLANNED ONLY</small></h3>"
        f"<p>{esc(item['handles'])}</p><p><b>Gap:</b> {esc(item['gap'])}</p>"
        f'<a href="{esc(item["repository"], quote=True)}">Upstream repository</a></article>'
        for item in repos["planned"]
    )
    template = (ROOT / "tools/factory/dashboard.html").read_text()
    body = (
        template.replace("@@COUNT@@", str(len(tools)))
        .replace("@@EXECUTED@@", str(baseline["executed_count"]))
        .replace("@@SOURCE@@", esc(source))
        .replace(
            "@@COUNTS@@",
            esc(
                " / ".join(
                    f"{value} {key}" for key, value in baseline["counts"].items()
                )
            ),
        )
        .replace("@@ROWS@@", "".join(rows))
        .replace("@@PLANNED@@", planned)
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)
