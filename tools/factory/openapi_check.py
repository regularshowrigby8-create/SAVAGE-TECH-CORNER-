"""Validate the real app schema without connecting to Redis or starting lifespan."""

import json
import os
import sys
from pathlib import Path

from openapi_spec_validator import validate


def main() -> int:
    """Export and validate OpenAPI; this does not test endpoint behavior."""
    out = Path(sys.argv[1])
    os.environ["DATABASE_URL"] = "sqlite:///:memory:"
    from searchhh.api import app

    spec = app.openapi()
    (out / "openapi.json").write_text(json.dumps(spec, indent=2) + "\n")
    validate(spec)
    result = {
        "valid": True,
        "paths": len(spec["paths"]),
        "openapi": spec["openapi"],
        "endpoint_behavior_verified": False,
    }
    (out / "openapi-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
