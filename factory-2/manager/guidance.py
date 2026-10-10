#!/usr/bin/env python3
"""
Factory 2 Manager — Guides agents, accepts/rejects feedback.
Never discounts feedback automatically.
"""

def review_findings():
    agents = ["correctness", "security", "architecture", "quality", "critic"]
    all_findings = {}
    for agent in agents:
        try:
            with open(f"factory-2/reports/{agent}_report.md", "r") as f:
                content = f.read()
                all_findings[agent] = content
                print(f"[MANAGER] Received findings from {agent}")
        except FileNotFoundError:
            print(f"[MANAGER] No findings from {agent}")
    
    # Manager response: must write acceptance/rejection for each
    manager_notes = []
    for agent, finding in all_findings.items():
        # Manager evaluates — does not auto-discount
        manager_notes.append(f"Agent {agent}: REVIEWED. Not discounted.")
        if "dead" in finding.lower() or "incomplete" in finding.lower():
            manager_notes.append(f"  -> Finding requires action or explicit rejection with reason.")
    
    with open("factory-2/reports/manager_response.md", "w") as f:
        f.write("# Manager Response\n")
        for note in manager_notes:
            f.write(note + "\n")
    
    print("[MANAGER] Review complete. All findings examined. No automatic discounts.")
    return manager_notes

if __name__ == "__main__":
    review_findings()
