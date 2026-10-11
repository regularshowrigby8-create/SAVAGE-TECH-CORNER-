"""Export compact, bounded job receipts without exposing scanner source/secret logs."""

import hashlib
import json
from pathlib import Path


def pages(summary: dict, digest: str) -> list[dict]:
    """Keep status distinct from execution and source provenance in every page."""
    records = []
    for result in summary["results"]:
        records.append(
            {
                "id": result["id"],
                "status": result["status"],
                "executed": "returncode" in result,
                "returncode": result.get("returncode"),
                "bound": result.get("bound"),
                "source_before": result["source_before"],
                "source_after": result["source_after"],
                "stdout_sha256": result.get("log_sha256"),
                "stderr_sha256": result.get("stderr_sha256"),
                "reports": result.get("reports", []),
            }
        )
    if not records:
        raise ValueError("Empty factory receipts are not execution evidence")
    chunks = [records[index : index + 3] for index in range(0, len(records), 3)]
    return [
        {
            "schema": 1,
            "source_commit": summary["source_commit"],
            "summary_sha256": digest,
            "page": index + 1,
            "total": len(chunks),
            "records": chunk,
        }
        for index, chunk in enumerate(chunks)
    ]


def main() -> None:
    """Emit notices readable through the Checks API when artifact transport fails."""
    paths = sorted(Path("build/reports/factory").glob("*/summary.json"))
    if not paths:
        raise ValueError("No factory run summary exists")
    path = paths[-1]
    data = path.read_bytes()
    for page in pages(json.loads(data), hashlib.sha256(data).hexdigest()):
        payload = json.dumps(page, separators=(",", ":"))
        if len(payload) > 3000:
            raise ValueError("Receipt exceeds safe annotation transport bound")
        escaped = payload.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(
            f"::notice title=Factory receipt {page['page']}/{page['total']}::{escaped}"
        )


if __name__ == "__main__":
    main()
