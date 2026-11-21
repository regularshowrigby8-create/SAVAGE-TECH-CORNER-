#!/usr/bin/env python3
"""Check both APKs contain the exact, nonempty pool assets supplied by this build.

This verifies packaging and crawler metadata, not network liveness or playback.
An empty site pool may be allowed only for an explicitly labelled smoke test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "nova-patch/nova-tv/tools"))
import server_crawler as server  # noqa: E402
import site_crawler as site  # noqa: E402


class VerificationError(ValueError):
    pass


def validate_assets(assets: Path, allow_empty_sites: bool = False) -> dict[str, tuple[bytes, int]]:
    result = {}
    for name in ("server_pool.json", "site_pool.json"):
        path = assets / name
        try:
            raw = path.read_bytes()
            doc = json.loads(raw)
        except (OSError, ValueError) as error:
            raise VerificationError(f"{name}: missing or invalid JSON: {error}") from error
        entries = doc.get("entries") if isinstance(doc, dict) else None
        if not isinstance(entries, list) or len(entries) > 3000:
            raise VerificationError(f"{name}: missing or oversized entries array")
        if not entries and not (name == "site_pool.json" and allow_empty_sites):
            raise VerificationError(f"{name}: pool is empty")
        if not all(isinstance(item, dict) for item in entries):
            raise VerificationError(f"{name}: malformed entry")
        if name == "server_pool.json":
            seen = set()
            hosts: Counter = Counter()
            for item in entries:
                manifest, base = item.get("manifestUrl"), item.get("baseUrl")
                if not isinstance(manifest, str) or not isinstance(base, str):
                    raise VerificationError(f"{name}: missing manifest or base URL")
                if not server.is_public_https(manifest) or not server.is_public_https(base):
                    raise VerificationError(f"{name}: unsafe manifest or base URL")
                key = server.pool_key(base)
                if key != server.pool_key(manifest) or key in seen:
                    raise VerificationError(f"{name}: duplicate or inconsistent manifest URL")
                seen.add(key)
                hosts[key.split("/", 1)[0]] += 1
            if hosts and max(hosts.values()) > server.PER_HOST:
                raise VerificationError(f"{name}: per-host ceiling exceeded")
        else:
            if doc.get("verification_method") != site.VERIFICATION_METHOD:
                raise VerificationError(f"{name}: legacy or missing verification method")
            seen = set()
            for item in entries:
                url, templates = item.get("url"), item.get("templates")
                if not isinstance(url, str) or not server.is_public_https(url):
                    raise VerificationError(f"{name}: unsafe site URL")
                if not site.verification_is_current(item.get("verification")):
                    raise VerificationError(f"{name}: site lacks current verification evidence")
                if not isinstance(templates, list) or not any(
                    isinstance(t, str) and ("{q}" in t or "{slug}" in t)
                    and site._same_host(t.replace("{q}", "test").replace("{slug}", "test"), url)
                    for t in templates
                ):
                    raise VerificationError(f"{name}: missing same-host search template")
                key = site.SitePool.key(url)
                if key in seen:
                    raise VerificationError(f"{name}: duplicate site host")
                seen.add(key)
        result[name] = (raw, len(entries))
    return result


def verify_apks(assets: Path, tv: Path, mobile: Path, allow_empty_sites: bool = False) -> dict:
    expected = validate_assets(assets, allow_empty_sites)
    report: dict[str, object] = {"counts": {name: count for name, (_, count) in expected.items()}, "apks": {}}
    for flavor, path in (("tv", tv), ("mobile", mobile)):
        try:
            sha256 = hashlib.sha256(path.read_bytes()).hexdigest()
            with zipfile.ZipFile(path) as apk:
                names = apk.namelist()
                for name, (raw, _) in expected.items():
                    asset_name = "assets/" + name
                    if names.count(asset_name) != 1:
                        raise VerificationError(f"{flavor}: {asset_name} must occur exactly once")
                    if apk.read(asset_name) != raw:
                        raise VerificationError(f"{flavor}: {asset_name} does not match the published asset")
        except (OSError, zipfile.BadZipFile, KeyError) as error:
            raise VerificationError(f"{flavor}: APK is missing or unreadable: {error}") from error
        report["apks"][flavor] = {"file": path.name, "sha256": sha256}
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--tv", type=Path, required=True)
    parser.add_argument("--mobile", type=Path, required=True)
    parser.add_argument("--allow-empty-sites-for-smoke", action="store_true")
    args = parser.parse_args()
    try:
        report = verify_apks(args.assets, args.tv, args.mobile, args.allow_empty_sites_for_smoke)
    except VerificationError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    for name, count in report["counts"].items():
        print(f"{name}: {count} entries in both APKs")
    for flavor, info in report["apks"].items():
        print(f"{flavor}: {info['file']} sha256={info['sha256']}")
    if not report["counts"]["site_pool.json"]:
        print("WARNING: empty site pool permitted for smoke testing only; not eligible as a complete pool artifact")
    print("Packaging and recorded heuristic metadata checked; playback, licensing and liveness were not verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
