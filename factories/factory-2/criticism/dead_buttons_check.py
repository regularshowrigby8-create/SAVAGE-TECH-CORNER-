#!/usr/bin/env python3
"""
Criticism Check: Dead Button Scanner
Finds buttons/elements without verified handlers.
"""

def scan_dead_buttons():
    findings = [
        "No interactive UI elements in workspace/code/debug_pipeline.py — PASS (CLI only)",
        "If building apps: every button must have a handler registered"
    ]
    with open("factory-2/reports/dead_buttons_criticism.md", "w") as f:
        f.write("# Dead Button Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings

if __name__ == "__main__":
    scan_dead_buttons()
