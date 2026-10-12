# Contributing to Savage Tech Corner & Savage Browser

Thank you for contributing to Savage Tech Corner!

## 1. Governance & Verification Standards

All code contributions must adhere to the **Global Charter** (`docs/factory/CHARTER.md`):

1. **Rule 9 (Universal Change Ledger Rule)**: Every directory big and small must maintain a detailed, verified changelog (`CHANGES.md`) tracking all changes with timestamps and automated verification evidence.
2. **Rule 10 (Direct Verification Invariant)**: Speculative code is strictly prohibited. Changes can only be committed with automated test passes and green deterministic gates.
3. **No Padding**: Never pad commits or files with arbitrary filler or unverified claims (`NO_PADDING.md`).

---

## 2. Local Development & Verification Workflow

1. **Verify Python Syntax**:

   ```bash
   python3 -m py_compile $(git ls-files "*.py")
   ```

2. **Execute Full Test Suite**:

   ```bash
   python3 test_entire_system.py
   ```

3. **Run Deterministic Factory Gates**:

   ```bash
   bash .claude/scripts/gates.sh
   ```

4. **Android Build Verification**:
   ```bash
   cd shell/android_project && ./gradlew assembleDebug
   ```

---

## 3. Pull Request Guidelines

- Ensure your branch is branched from and targets `arena/880c7a8f-savage-tech-corner` or `main`.
- Include direct test execution output in your PR description.
- Update `CHANGES.md` in the affected directories.
