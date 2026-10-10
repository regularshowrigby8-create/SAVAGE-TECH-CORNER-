#!/usr/bin/env python3
"""
Agent: Critic (Adversarial Model)
Returns SHIP / FIX-FIRST based on real evidence from the repo.
"""
import os

def adversarial_review(directory="."):
    # Read real reports from other agents
    reports_dir = "factory-2/reports/"
    notes = []
    verdict = "SHIP"  # Default to SHIP unless real evidence says otherwise
    
    # Check if quality has missing tests
    quality_path = os.path.join(reports_dir, "quality_report.md")
    if os.path.exists(quality_path):
        with open(quality_path, 'r') as f:
            content = f.read()
        if "missing_tests" in content:
            notes.append("Quality: missing tests found — requires fix")
            verdict = "FIX-FIRST"
        if "test_present" in content:
            notes.append("Quality: test present — passes")
    
    # Check architecture empty dirs
    arch_path = os.path.join(reports_dir, "architecture_report.md")
    if os.path.exists(arch_path):
        with open(arch_path, 'r') as f:
            content = f.read()
        if "empty_directory" in content:
            notes.append("Architecture: empty scaffolding found — requires content")
            verdict = "FIX-FIRST"
        if "missing_reference" in content:
            notes.append("Architecture: broken references found")
    
    # Check manager response — must exist and contain real decisions
    manager_path = os.path.join(reports_dir, "manager_response.md")
    if not os.path.exists(manager_path):
        notes.append("No manager response found — requires review")
        verdict = "FIX-FIRST"
    else:
        with open(manager_path, 'r') as f:
            manager_content = f.read()
        if "ACCEPTED" not in manager_content and "REJECTED" not in manager_content:
            notes.append("Manager response missing written accept/reject — requires review")
            verdict = "FIX-FIRST"
        else:
            notes.append("Manager response present with written decisions")
    
    with open("factory-2/reports/critic_report.md", "w") as f:
        f.write(f"# Critic Report\nVerdict: {verdict}\nNotes: {notes}\n")
    return {"verdict": verdict, "notes": notes}

if __name__ == "__main__":
    result = adversarial_review()
    print(f"[AGENT-CRITIC] Verdict: {result['verdict']} — Notes: {len(result['notes'])}")
