# SAVAGE TECH CORNER — ENFORCEMENT RULES v3: ANTI-STUB & CONTINUOUS FORWARD PROGRESSION

Supersedes ENFORCEMENT v2. Institutionalizes strict anti-placeholder invariants,
real-time analysis mandates, anti-deception rules, and forward-only architectural progression.

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

**Fail-Closed Automated Check**:
`agent_correctness.py` and `gates.sh` scan the entire repository for stub tokens:
- Rejects unhandled `NotImplementedError`, empty `pass` in non-abstract methods, and unfunctional stubs.
- Rejects decorative build scripts.

---

## 2. Anti-Deception & Provenance Verification Invariant

**Standing Mandate**: Deliverables must be the genuine product of this repository.

- **Package Identity Match**: Android APKs, Dalvik bytecode, manifests, and runtime components
  must match the authorized project identity (`com.savage.soulbrowser`).
- **No Foreign Binaries**: Repackaging third-party packages (e.g. `acr.browser.barebones`, `Lightning`)
  or masquerading external binaries under project names is strictly prohibited.
- **Fail-Closed Verification**: `browser_app_rules.py` inspects binary AXML and DEX bytecode directly.
  Any foreign package strings, third-party repository URLs, or foreign author attributions abort the
  build and fail the quality gate immediately.

---

## 3. Strict Forward-Only Progression (No Working Backwards)

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

## 4. Every Markdown Claim Is Checkable

Any `.md` file naming a file path or making a factual statement is mechanically tested:
- Does the path exist?
- Does the command return 0?
- Is the claim verifiable in code?
If not, `gates.sh` fails with `CLAIM FAILURE`.

---

## 5. Summary of Automated Quality Gates in CI

```bash
echo "=== FACTORY GATE START ==="
check_python_syntax
check_single_job_workflow
check_anti_stub_invariants    # Checks zero TODO/stubs/empty mocks
check_provenance_and_apk      # Verifies authentic package identity
check_claims_and_paths        # Verifies all markdown claims
check_wiring_and_coverage     # Verifies all tools are wired into run_all.sh
```
