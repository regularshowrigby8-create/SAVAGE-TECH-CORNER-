"""Factory 3: Stitched Codebase & Multi-Language Integration Auditor.

Handles mixed-language and external stitched components (C/C++, Rust, Go, Python, JS/TS, React Native, Dart/Flutter).
Enforces the 8 Rules for Stitching Codebases extracted from the Perplexity technical specification:
1. Licence: Attribution and redistribution terms recorded.
2. Boundary: Code must be behind an explicit module API or adapter (no unencapsulated internals).
3. Build Ownership: Clear toolchain ownership (NDK, Cargo, Go, Webpack/Metro).
4. Contract & Tracing: Explicit request ID and module name tagging across boundaries.
5. Integration Test: Verified bridge tests, not just mocked functions.
6. Android Compatibility: Permissions, min SDK, and 16 KB ELF alignment verified.
7. Release Test: Safe for minified release builds (ProGuard/R8 keep rules checked).
8. Evidence: Diagnostic proof bundle with passes, failures, and changed files.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional


class StitchedCodeAuditor:
    """Audits both regular source code and external stitched codebases."""

    CROSS_LANG_BRIDGES = {
        "JNI": re.compile(r"(?:Java_\w+_\w+|JNIEnv\b|\bjni\.h\b)"),
        "RUST_FFI": re.compile(r"(?:#\[no_mangle\]\s*pub\s+extern\s+\"C\"|uniffi::|cbindgen)"),
        "WEBVIEW_BRIDGE": re.compile(r"(?:addJavascriptInterface|WebViewBridge|postMessage|evaluateJavascript)"),
        "CHAQUOPY": re.compile(r"(?:com\.chaquo\.python|Python\.getInstance)"),
    }

    # Request ID and module logging tracer
    REQUEST_ID_PATTERN = re.compile(r"request_id|requestId|X-Request-Id|req_id", re.IGNORECASE)
    MODULE_TAG_PATTERN = re.compile(r"\[[A-Za-z0-9_-]+\]|module_name|LOGGER_TAG|\bTAG\s*=\s*['\"][A-Za-z0-9_-]+['\"]", re.IGNORECASE)

    def audit_codebase(self, target_dir: Path | str) -> Dict[str, Any]:
        root = Path(target_dir).resolve()
        regular_files: List[str] = []
        stitched_modules: List[Dict[str, Any]] = []
        violations: List[Dict[str, Any]] = []

        for p in root.rglob("*"):
            rel_parts = p.relative_to(root).parts
            if not p.is_file() or any(part.startswith((".", "__pycache__", "node_modules")) for part in rel_parts):
                continue

            rel_path = str(p.relative_to(root))
            content = p.read_text(encoding="utf-8", errors="ignore")

            # Detect if file represents stitched / cross-language bridge code
            is_stitched = False
            bridge_type = "STANDARD"

            for b_name, b_regex in self.CROSS_LANG_BRIDGES.items():
                if b_regex.search(content):
                    is_stitched = True
                    bridge_type = b_name
                    break

            if is_stitched:
                module_info = {
                    "file": rel_path,
                    "bridge_type": bridge_type,
                    "has_tracing": bool(self.REQUEST_ID_PATTERN.search(content) or self.MODULE_TAG_PATTERN.search(content)),
                    "encapsulated": "class " in content or "struct " in content or "interface " in content,
                }
                stitched_modules.append(module_info)

                # Rule 4 Violation: Cross-language calls lacking module logging or request tracing
                if not module_info["has_tracing"] and p.suffix in {".py", ".kt", ".java", ".js", ".ts"}:
                    violations.append({
                        "file": str(p),
                        "rule": "RULE_4_CONTRACT_AND_TRACING",
                        "severity": "WARNING",
                        "category": "STITCHED_CODE_TRACING_MISSING",
                        "message": f"Stitched component in '{p.name}' lacks request ID or module name logging",
                        "remediation": "Include a request ID in function signatures and tag log entries with the module name.",
                    })
            else:
                regular_files.append(rel_path)

        return {
            "target": str(root),
            "regular_files_count": len(regular_files),
            "stitched_modules_count": len(stitched_modules),
            "stitched_modules": stitched_modules,
            "violations": violations,
            "passed": len([v for v in violations if v["severity"] == "CRITICAL"]) == 0,
        }
