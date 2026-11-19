#!/usr/bin/env bash
# Anti-false-green guard: finds checks that report success without proving it.
#
#   semgrep  (semgrep/semgrep, LGPL-2.1)  tests with no assertion, @Ignore'd
#            tests, tools that hard-code "ok": True, `|| true`,
#            continue-on-error, uploads that ignore missing files
#   ShellCheck (koalaman/shellcheck, GPL-3.0, run as a tool)  workflow steps
#            whose exit status is thrown away (SC2312), using the shell GitHub
#            really runs -- see tools/workflow_shellcheck.py for why actionlint
#            cannot see this
#   Gradle test task  the unit tests run and pass
#   PIT (hcoles/pitest, Apache-2.0) via koral--/gradle-pitest-plugin
#            (Apache-2.0)  mutation testing: break the code on purpose and
#            require the tests to notice
#
# Usage: nova-tv/guards/anti-false-green.sh     exit 0 only if every check passed
source "$(dirname "$0")/lib.sh"
guard_start anti-false-green "Anti-false-green guard"

SG="$GUARDS_DIR/semgrep/false-green"
existing_workflows() { for f in "${NOVA_WORKFLOWS[@]}"; do [[ -f "$f" ]] && echo "$f"; done; }
mapfile -t WORKFLOWS < <(existing_workflows)

check "semgrep rules prove they fire (semgrep --test)" semgrep -- \
  semgrep scan --test --metrics=off --disable-version-check "$SG"

check "semgrep: tests without assertions, @Ignore, hard-coded ok=True" semgrep -- \
  semgrep scan --error --quiet --metrics=off --disable-version-check --text \
    --project-root "$NOVA_DIR" --config "$SG/false-green-code.yml" \
    "$NOVA_DIR/android/app/src" "$NOVA_DIR/python"

check "semgrep: CI steps that cannot fail (or-true, continue-on-error, ignored uploads)" semgrep -- \
  semgrep scan --error --quiet --metrics=off --disable-version-check --text \
    --config "$SG/false-green-ci.yml" "${WORKFLOWS[@]}" "$GUARDS_DIR"/*.sh

check "ShellCheck: workflow steps that discard a failing exit status" shellcheck -- \
  python3 "$GUARDS_DIR/tools/workflow_shellcheck.py" "${WORKFLOWS[@]}"

check "unit tests run and pass (both flavours)" java -- \
  gradle :app:testTvDebugUnitTest :app:testMobileDebugUnitTest

# The threshold lives in app/build.gradle.kts (pitest { testStrengthThreshold }),
# so PIT itself fails the build when the tests catch too few broken versions.
run_pitest() (
  cd "$NOVA_DIR/android" || return 1
  local log
  log="$(mktemp)"
  ./gradlew --no-daemon --console=plain :app:pitestTvDebug > "$log" 2>&1
  local rc=$?
  cat "$log"
  local xml=app/build/reports/pitest/tvDebug/mutations.xml
  if [[ -f "$xml" ]]; then
    echo "--- surviving mutants: code was broken and no test noticed (first 60) ---"
    grep "status='SURVIVED'" "$xml" \
      | sed -E 's|.*<sourceFile>([^<]*)</sourceFile>.*<lineNumber>([^<]*)</lineNumber>.*<description>([^<]*)</description>.*|\1:\2  \3|' \
      | head -60
    echo "--- PIT statistics ---"
    grep -E "^>> |Generated [0-9]+ mutations|Test strength|Mutations with no coverage|Line Coverage" "$log"
    echo "--- mutant status counts (from mutations.xml) ---"
    for s in KILLED SURVIVED NO_COVERAGE TIMED_OUT MEMORY_ERROR RUN_ERROR; do
      printf '%-12s %s\n' "$s" "$(grep -c "status='$s'" "$xml")"
    done
  fi
  rm -f "$log"
  return $rc
)
check "PIT mutation testing: tests must catch deliberately broken code" java -- run_pitest

guard_finish
