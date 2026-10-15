# SAVAGE TECH CORNER — ENFORCEMENT RULES v4: FAIL-CLOSED ANTI-BYPASS & 5X EXHAUSTIVE VERIFICATION

Supersedes ENFORCEMENT v3. Establishes immutable, fail-closed rule enforcement across all developers, models, agents, and CI pipelines.

---

## 1. Zero Placeholders, Stubs, or Empty Mock Implementations

**Standing Mandate**: Neither the human engineer nor any autonomous factory model or agent
may output "TODO", "FIXME", "placeholder", empty mock returns (`return {}`, `return "dummy"`),
or decorative shell scripts (e.g. exit 0 fake wrappers).

- **Rule**: Every function, component, or module committed must perform its functional purpose.
- **Uncertainty Protocol**: If an agent, model, or developer is unsure about how to implement a
  particular component or platform integration, **they must immediately perform AST/semantic analysis,
  design analysis, and execute the best functional implementation immediately**.
- Leaving stub code for "later" is strictly prohibited: unfinished code gets lost, causes regression,
  and accumulates technical debt that degrades high-assurance guarantees.

---

## 2. Strict Anti-Bypass & Exception Mandate

**Standing Invariant**: **Rules cannot be bypassed, suppressed, or ignored under any standard condition.**

In the unprecedented event that an operational exception is required:

1. **Extremely Good Reason**: Must provide a comprehensive, rigorous architectural justification of at least 100 characters detailing why an exception is mathematically, structurally, or upstream-wise necessary.
2. **Mandatory Functional Solution**: Every bypass request MUST include an actual functional solution file. Bypassing a rule to leave code broken, missing, or empty is rejected unconditionally.
3. **5x Exhaustive Test Verification Across the Entire System**: The proposed solution and codebase MUST be executed against the entire system test suite (Factory 2 tests, Factory 3 tests, full system integration audit, and deterministic quality gates) **at least 5 consecutive times across isolated iterations** with a 100% pass rate.
4. **Cryptographic Certificate**: If any single test fails during the 5 iterations, the bypass is aborted. Only if all 5 iterations pass is a cryptographic certificate issued and recorded in `factories/governance/rule_bypass_registry.json`.
5. **Continuous Hash Validation**: If solution files are tampered with or modified post-certification, the bypass certificate is immediately invalidated.

---

## 3. Anti-Deception & Provenance Verification Invariant

**Standing Mandate**: Deliverables must be the genuine product of this repository.

- **Package Identity Match**: Android APKs, Dalvik bytecode, manifests, and runtime components
  must match the authorized project identity (`com.savage.soulbrowser`).
- **No Foreign Binaries**: Repackaging third-party packages (e.g. `acr.browser.barebones`, `Lightning`)
  or masquerading external binaries under project names is strictly prohibited.
- **Fail-Closed Verification**: `browser_app_rules.py` inspects binary AXML and DEX bytecode directly.
  Any foreign package strings, third-party repository URLs, or foreign author attributions abort the
  build and fail the quality gate immediately.

---

## 4. Strict Forward-Only Progression (No Working Backwards)

To stop working backwards or constantly cycling through criticism checks without forward movement:

1. **Verify Before Claiming**: No claim in documentation, PRs, or commit messages may be written
   without direct automated runtime proof (tests passing, bytecode inspected, curl/dispatch verified).
2. **Regression Rollback Invariant**: Any proposed patch or change that breaks an existing test or
   violates a quality invariant is automatically reverted to its last known good state by
   `main_agent_verifier.py`.
3. **No Redundant / No-Op Cycles**: Proposals that generate byte-identical code or cosmetic comment
   edits without behavioral improvements are rejected.
4. **No Code Duplication**: Code must exist in exactly one canonical directory. Duplicate files
   between module roots and submodules are strictly blocked.

---

## 5. Summary of Automated Quality Gates in CI & Git Pre-Commit

Every commit and CI run executes:

```bash
echo "=== FACTORY GATE START ==="
check_python_syntax
check_single_job_workflow
check_anti_stub_invariants    # Checks zero TODO/stubs/empty mocks
check_provenance_and_apk      # Verifies authentic package identity
check_claims_and_paths        # Verifies all markdown claims
check_wiring_and_coverage     # Verifies all tools are wired into run_all.sh
```

Git pre-commit hooks (`.githooks/pre-commit`) and GitHub Actions fail closed if any rule is breached.
