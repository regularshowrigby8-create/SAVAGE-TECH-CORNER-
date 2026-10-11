"""Fail-closed static app security contracts for repeatable factory triage."""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def finding(rule: str, path: str, line: int, message: str) -> dict:
    """Return bounded metadata only; source snippets and secrets are excluded."""
    return {"rule": rule, "path": path, "line": line, "message": message}


def line_of(text: str, needle: str) -> int:
    """Return a one-based source line for a policy token."""
    index = text.find(needle)
    return text.count("\n", 0, max(index, 0)) + 1


def scan(root: Path = ROOT) -> dict:
    """Check product-owned security invariants without changing source files."""
    findings = []
    manifest_path = root / "app/src/main/AndroidManifest.xml"
    manifest = manifest_path.read_text()
    manifest_rel = str(manifest_path.relative_to(root))
    if 'android:usesCleartextTraffic="true"' in manifest:
        findings.append(finding("APP-CLEAR-001", manifest_rel, line_of(manifest, 'android:usesCleartextTraffic="true"'), "Application permits cleartext traffic globally; constrain it or document an explicit compatibility decision."))

    network_path = root / "app/src/main/res/xml/network_security_config.xml"
    network = network_path.read_text()
    network_rel = str(network_path.relative_to(root))
    if 'cleartextTrafficPermitted="true"' in network:
        findings.append(finding("APP-CLEAR-002", network_rel, line_of(network, 'cleartextTrafficPermitted="true"'), "Network security config permits cleartext traffic globally."))

    source_paths = list((root / "app/src/main/java").rglob("*.kt"))
    source = [(str(path.relative_to(root)), path.read_text()) for path in source_paths]
    for path, text in source:
        if "MIXED_CONTENT_ALWAYS_ALLOW" in text:
            findings.append(finding("WEBVIEW-MIXED-001", path, line_of(text, "MIXED_CONTENT_ALWAYS_ALLOW"), "HTTPS documents may load HTTP subresources."))
        if "setWebContentsDebuggingEnabled(BuildConfig.DEBUG)" in text:
            continue
        if "setWebContentsDebuggingEnabled(true)" in text:
            findings.append(finding("WEBVIEW-DEBUG-001", path, line_of(text, "setWebContentsDebuggingEnabled(true)"), "WebView debugging is unconditionally enabled."))

    ssl_files = [(path, text) for path, text in source if "SslErrorHandler" in text]
    if ssl_files and not any("onReceivedSslError" in text and "handler.cancel()" in text for _, text in source):
        path, text = ssl_files[0]
        findings.append(finding("WEBVIEW-TLS-001", path, line_of(text, "SslErrorHandler"), "TLS errors do not have a visible cancellation path."))

    config_path = root / "app/src/main/java/info/plateaukao/einkbro/view/WebViewConfigApplier.kt"
    config = config_path.read_text()
    config_rel = str(config_path.relative_to(root))
    if "allowFileAccessFromFileURLs = false" not in config and "allowFileAccessFromFileURLs = config.browser.enableRemoteAccess" not in config:
        findings.append(finding("WEBVIEW-FILE-001", config_rel, 1, "File URL cross-origin access policy is not explicit."))
    if "allowUniversalAccessFromFileURLs = false" not in config and "allowUniversalAccessFromFileURLs = config.browser.enableRemoteAccess" not in config:
        findings.append(finding("WEBVIEW-FILE-002", config_rel, 1, "Universal file URL access policy is not explicit."))

    secret_pattern = re.compile(r"(?:api[_-]?key|secret|password|token)\s*[:=]\s*['\"]([A-Za-z0-9_./+=-]{20,})['\"]", re.I)
    reviewed_public_constants = []
    for path, text in source:
        match = secret_pattern.search(text)
        if not match or match.group(1).startswith("sp_"):
            continue
        # Edge TTS requires this published protocol token in its request format.
        # Keep the exact source context as a review record, never the token value.
        if path.endswith("tts/ETts.kt") and "TRUSTED_CLIENT_TOKEN" in text and "speech.platform.bing.com" in text:
            reviewed_public_constants.append({"path": path, "symbol": "TRUSTED_CLIENT_TOKEN", "reason": "Published Edge TTS protocol field; not treated as an app-owned credential."})
            continue
        findings.append(finding("APP-SECRET-001", path, line_of(text, match.group(0)), "Possible hard-coded credential pattern; review without copying its value."))

    return {"schema_version": 1, "policy": "Searchhh app security contracts", "findings": findings, "reviewed_public_constants": reviewed_public_constants, "release_approved": False, "source_fingerprints_checked": True}


def main() -> int:
    """Write a machine-readable report and preserve nonzero findings."""
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/reports/factory/app-security.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    report = scan()
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"findings": len(report["findings"]), "output": str(output)}))
    return 1 if report["findings"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
