# Manager Guidance — Factory 2

## How to Guide Agents
1. Read all agent reports.
2. For each finding: write either `ACCEPTED: [reason]` or `REJECTED: [reason with evidence]`.
3. Never write `ACCEPTED: N/A` or `REJECTED: Auto`.
4. Every response must reference the specific finding.
5. Changes must be documented before any modification.

## Example Response Format
```
Agent: agent_quality
Finding: Missing tests for debug_pipeline.py
Response: ACCEPTED. The pipeline requires test coverage. A Linear issue will be filed with label factory:ready.
```
