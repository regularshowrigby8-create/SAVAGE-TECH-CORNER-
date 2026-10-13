#!/usr/bin/env bash
# Instrumented-test and install smoke stage for the Android pipeline.
#
# This is a real script rather than an inline step because ReactiveCircus/android-emulator-runner
# executes its `script:` input with /usr/bin/sh (dash on the runner image). Dash rejects
# `set -o pipefail` and does not know PIPESTATUS, so an inline bash script dies with exit code 2
# before any test runs - which is exactly what happened on the first emulator run. The workflow
# therefore calls this file with bash, and tools/ emits every failure reason into the log.
#
# It runs, in order:
#   1. connectedDebugAndroidTest on the already running emulator
#   2. tools/androidtest_failure_report.py - a short report naming every failing test case
#   3. tools/android_apk_smoke.sh on the debug APK built by the build job
# and exits non-zero when either step failed, after printing the reasons.
set -uo pipefail

LOG="${RUNNER_TEMP:-/tmp}/build.log"
WORKSPACE="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$WORKSPACE"

echo "=== instrumented tests on the attached emulator ===" | tee -a "$LOG"
( cd shell/android_project && ./gradlew connectedDebugAndroidTest --stacktrace --no-daemon ) \
  2>&1 | tee -a "$LOG"
INSTRUMENTED_STATUS=${PIPESTATUS[0]}
echo "connectedDebugAndroidTest exit=$INSTRUMENTED_STATUS" | tee -a "$LOG"

# Report what the instrumented run did even when it failed: a red job must name the failing case,
# and the raw log of a hosted runner is not always retrievable afterwards.
python3 tools/androidtest_failure_report.py > "$RUNNER_TEMP/instrumented-report.txt" 2>&1
REPORT_STATUS=$?
echo "androidtest_failure_report exit=$REPORT_STATUS (3 means it found no results to read)"
tee -a "$LOG" < "$RUNNER_TEMP/instrumented-report.txt"

echo "=== install, launch, kill, relaunch, uninstall and reinstall ===" | tee -a "$LOG"
DEBUG_APK=$(find shell/android_project/app/build/outputs/apk/debug -name '*.apk' | head -n 1)
SMOKE_STATUS=0
if [ -n "$DEBUG_APK" ]; then
  chmod +x tools/android_apk_smoke.sh
  bash tools/android_apk_smoke.sh "$DEBUG_APK" 2>&1 | tee -a "$LOG"
  SMOKE_STATUS=${PIPESTATUS[0]}
  echo "android_apk_smoke exit=$SMOKE_STATUS" | tee -a "$LOG"
else
  echo "the debug APK is missing on the emulator runner" | tee -a "$LOG"
  SMOKE_STATUS=1
fi

if [ "$INSTRUMENTED_STATUS" -ne 0 ] || [ "$SMOKE_STATUS" -ne 0 ] || [ "$REPORT_STATUS" -eq 3 ]; then
  echo "::error::instrumented tests exit=$INSTRUMENTED_STATUS, smoke test exit=$SMOKE_STATUS, report exit=$REPORT_STATUS"
  adb logcat -d -t 3000 > "$RUNNER_TEMP/logcat-tail.txt" 2>&1
  tail -n 200 "$RUNNER_TEMP/logcat-tail.txt" | tee -a "$LOG"
  exit 1
fi
echo "emulator stage finished: instrumented tests passed and the APK installed, launched and survived two restarts" | tee -a "$LOG"
