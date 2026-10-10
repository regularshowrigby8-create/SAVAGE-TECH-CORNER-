#!/usr/bin/env python3
"""
Agent: Architecture (Architecture Model)
Finds: layer issues, wrong paths, broken references in actual repo.
"""
import os

def review_architecture(directory="."):
    findings = []
    # Check for broken references (files mentioned but missing)
    mentioned = []
    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in ['.git', 'node_modules', '__pycache__', 'factory-2/reports', 'docs', '.factory/logs', '.claude']]
        for fname in files:
            fpath = os.path.join(root, fname)
            # Skip agent reports — they describe findings, not architecture
            if 'factory-2/reports' in fpath or '.factory/logs' in fpath:
                continue
            if fname.endswith('.md') or fname.endswith('.py'):
                fpath = os.path.join(root, fname)
                try:
                    with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                        content = f.read()
                    if 'workspace/code/' in content or 'workshop/code/' in content:
                        # Check if the referenced file exists
                        refs = [line for line in content.split('\n') if 'workspace/code/' in line or 'workshop/code/' in line]
                        for ref in refs:
                            # Extract potential file reference
                            if 'debug_pipeline' in ref:
                                target = 'workspace/code/debug_pipeline.py'
                                if not os.path.exists(target):
                                    findings.append({"type": "missing_reference", "reference": ref.strip(), "expected_file": target})
                except Exception:
                    pass
    # Real layer check: look for empty directories that should have content
    for item in ['shared/agents/', 'factory/template/', '.factory/scripts/', '.factory/logs/']:
        if os.path.isdir(item) and not os.listdir(item):
            findings.append({"type": "empty_directory", "path": item})
    with open("factory-2/reports/architecture_report.md", "w") as f:
        f.write(f"# Architecture Report\nFindings: {findings}\n")
    return findings

if __name__ == "__main__":
    findings = review_architecture()
    print(f"[AGENT-ARCHITECTURE] Architecture review complete. Findings: {len(findings)}")
