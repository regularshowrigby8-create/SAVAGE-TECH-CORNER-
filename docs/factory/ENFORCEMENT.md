# SAVAGE TECH CORNER — ENFORCEMENT RULES v2

Supersedes earlier ENFORCEMENT.md. The earlier version's rule #1 (no
hand-editing generated reports) worked — it caught fabrication in
factory-2/reports/*.md. But the same fabrication moved into
CRITICISM_ASSESSMENT.md (free-form doc not covered by provenance check).
The lesson: don't make rules about ONE file type. Make rules about CLAIMS.

Every `.md` file — hand-written or generated — must contain only claims that
resolve on disk right now (`test -e` / `cat` / running the command that
produced it). No past-tense descriptions of things not present.

## 1. Every markdown claim is checkable (rule #1 from user's updated rules)

Any `.md` file naming a file path (`.claude/scripts/gates.sh`,
`models/free-models.json`, `factory-2/apk-analysis/apk_scanner.py`) is
checked: does that path exist? A line in past/present tense (`X is
configured`, `X exists`, `X passes`) that refers to a missing file fails.
A claim about something planned must be prefixed with `PLANNED:` or placed
under `## Not Yet Built` explicitly.

**Check (in `gates.sh`):**
```bash
check_claims() {
    echo "[GATE] Claim-checking all markdown files..."
    CLAIM_FAILURES=""
    find . -name "*.md" -not -path "./.git/*" | while read -r mdfile; do
        grep -oE '[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+\.[a-z]{1,5}' "$mdfile" 2>/dev/null | sort -u | while read -r ref; do
            if grep -F "$ref" "$mdfile" | grep -qE 'PLANNED:|https?://'; then
                continue
            fi
            if [ ! -e "$ref" ]; then
                echo "CLAIM FAILURE in $mdfile: references '$ref' which does not exist"
                echo "$mdfile:$ref" >> /tmp/claim_failures
            fi
        done
    done
    if [ -s /tmp/claim_failures ]; then
        FAILURES="${FAILURES}unverified_claims;"
    fi
    rm -f /tmp/claim_failures
}
```
This covers `factory-2/reports/*.md`, `docs/factory/*.md`, `ENVIRONMENT.md`,
`CRITICISM_ASSESSMENT.md`, `ZIP_LINK.md`, and any future doc.

## 2. Generated output still can't be hand-edited (unchanged from v1, still needed)

`factory-2/reports/*.md`, any `*_criticism.md`, `.factory/logs/*.md` — only
produced by `run_all.sh`. Never edited directly.

**Check:** `check_report_provenance()` runs `run_all.sh`, compares.

## 3. Agent output must be deterministic

Any `os.walk` or file-system scan must sort before matching (`sorted(files)`,
`sorted(dirs)`). Non-determinism makes provenance checks fail by accident.

**Fix pattern:** `sorted(files)` in agent loops; `sorted(findings, key=...)` before
writing reports.

**Check:** run agent script 3 times; output must match byte-for-byte.

## 4. No file or directory without a reference (unchanged from v1, enforced now)

A file or directory exists only if `run_all.sh`, `.github/workflows/*.yml`,
`.claude/scripts/gates.sh`, `.factory/scripts/doctor.sh`, or `docs/factory/*.md`
references it. Otherwise: wire it in or delete it in the same PR.

**Check:** `check_padding()` — duplicate content hash + unreferenced-dir scan.

## 5. Every claim of completed work must name a literal path

Any sentence saying `X is configured`, `X passes`, `X was added`, `X fixed`
must name a path that resolves (`ls <path>` or `cat <path>`). Before trusting,
run that check.

**Check:** `check_claims()` (rule 1 above) enforces this mechanically.

## 6. New code ships wired in (added for apk_scanner.py fix)

A new script (`agent_*.py`, `*_check.py`) must appear in `run_all.sh` and,
if it should run in CI, `.github/workflows/*.yml` — same commit.

**Check:** `check_wiring()` — verifies new scripts appear in `run_all.sh`.

---

Putting all six in `gates.sh`:
```bash
echo "=== FACTORY GATE START ==="
check_lint
check_tests
check_dead_buttons
check_factory_2
check_claims              # new — covers every .md
check_report_provenance   # unchanged — generated reports
check_padding             # unchanged — duplicates + unreferenced dirs
check_wiring              # new — new scripts wired in
```
