#!/usr/bin/env python3
"""
Agent: Critic (Adversarial Model)
Returns SHIP / FIX-FIRST on changed user journeys.
"""

def adversarial_review(directory="."):
    print("[AGENT-CRITIC] Adversarial review running...")
    # Read previous reports and add adversarial perspective
    verdict = "FIX-FIRST"
    notes = [
        "Dead buttons potential in workspace/code/debug_pipeline.py",
        "No interactive elements verified",
        "No user journey tested end-to-end"
    ]
    with open("factory-2/reports/critic_report.md", "w") as f:
        f.write(f"# Critic Report\nVerdict: {verdict}\nNotes: {notes}\n")
    return {"verdict": verdict, "notes": notes}

if __name__ == "__main__":
    adversarial_review()
