# Factory Verify Skill

## Cold-Read Procedure

1. Read the diff without reading the agent's explanation.
2. Revert the fix locally; confirm the test fails.
3. Read the original code for dead/incomplete patterns.
4. Return `MERGE / FIX / ESCALATE` — never paraphrase.
