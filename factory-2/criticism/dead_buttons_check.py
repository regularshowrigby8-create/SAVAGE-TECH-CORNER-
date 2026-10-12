#!/usr/bin/env python3
"""
Criticism Check: UI Interaction & Dead Element Handler Auditor.
Verifies that all registered UI elements across the shell and frontend have bound, callable, and executable handlers.
"""

from __future__ import annotations

import ast
import inspect
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
if (ROOT_DIR / "factories").exists() and not (ROOT_DIR / "shell").exists():
    ROOT_DIR = ROOT_DIR.parent
if not (ROOT_DIR / "shell").exists() and Path("/home/user/SAVAGE-TECH-CORNER-/shell").exists():
    ROOT_DIR = Path("/home/user/SAVAGE-TECH-CORNER-")

sys.path.insert(0, str(ROOT_DIR / "shell"))


def audit_ui_element_handlers() -> List[str]:
    findings: List[str] = []
    shell_file = ROOT_DIR / "shell" / "soul_browser" / "browser_shell.py"

    if not shell_file.exists():
        findings.append("No active shell file found for interaction audit")
        return findings

    try:
        from soul_browser.browser_shell import ModernLuminescenceUITheme, SoulBrowserShell

        shell = SoulBrowserShell()
        layout_spec = ModernLuminescenceUITheme.get_layout_spec()
        required_buttons: List[str] = layout_spec.get("bottom_bar", {}).get("buttons", [])

        if not required_buttons:
            findings.append("Layout specification has no registered buttons in bottom_bar")

        for btn_id in required_buttons:
            # 1. Verification of registration in shell buttons map
            if btn_id not in shell.buttons:
                findings.append(f"Dead button detected: '{btn_id}' is declared in UI layout spec but missing in shell.buttons map")
                continue

            handler = shell.buttons[btn_id]
            # 2. Verification of handler callability
            if not callable(handler):
                findings.append(f"Dead button detected: Handler for '{btn_id}' is not callable ({type(handler)})")
                continue

            # 3. Dynamic execution test: Invoking the button must return a valid non-empty response
            try:
                result = shell.click_button(btn_id)
                if not isinstance(result, dict) or "action" not in result:
                    findings.append(f"Dead button detected: Handler for '{btn_id}' returned invalid response: {result}")
            except Exception as ex:
                findings.append(f"Dead button detected: Handler for '{btn_id}' threw exception when clicked: {ex}")

        # Also audit frontend HTML buttons if present: an id-bearing <button>
        # without onclick must be bound from committed JS/TS, or it is dead.
        frontend_dir = ROOT_DIR / "frontend"
        if frontend_dir.exists():
            js_corpus = ""
            for jf in list(frontend_dir.rglob("*.js")) + list(frontend_dir.rglob("*.ts")):
                js_corpus += jf.read_text(encoding="utf-8", errors="ignore") + "\n"
            for fpath in frontend_dir.rglob("*.html"):
                content = fpath.read_text(encoding="utf-8", errors="ignore")
                for match in re.finditer(r"<button\b[^>]*\bid=[\"']([^\"']+)[\"'][^>]*>", content, re.IGNORECASE):
                    tag, button_id = match.group(0), match.group(1)
                    if "onclick" in tag.lower():
                        continue
                    bound = (
                        f'"{button_id}"' in js_corpus
                        or f"'{button_id}'" in js_corpus
                        or f"`{button_id}`" in js_corpus
                        or f"#{button_id}" in js_corpus
                    )
                    if not bound:
                        findings.append(
                            f"Dead button detected: '<button id=\"{button_id}\">' in {fpath} "
                            f"has no onclick attribute and no JS/TS binding referencing that id"
                        )

        if not findings:
            findings.append(
                f"All {len(required_buttons)} SoulBrowser shell UI button interactions verified with active, callable, and executable handlers — PASS"
            )
    except Exception as e:
        findings.append(f"Error auditing UI handlers: {e}")

    output_path = ROOT_DIR / "factory-2" / "reports" / "dead_buttons_criticism.md"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("# Dead Button Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings


if __name__ == "__main__":
    results = audit_ui_element_handlers()
    for r in results:
        print(f"[DEAD-BUTTON-AUDITOR] {r}")
    failures = [r for r in results if "— PASS" not in r]
    if failures:
        print(f"[DEAD-BUTTON-AUDITOR: FAIL] {len(failures)} dead or unbound UI element(s).")
    raise SystemExit(1 if failures else 0)

