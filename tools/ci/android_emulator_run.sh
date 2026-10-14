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

# ---------------------------------------------------------------- emulator pre-flight
# The instrumented suite drives real activities and Espresso matches the root view that has window
# focus. On a headless runner the emulator can come up with its screen off and the keyguard showing:
# the activities do start (the platform reports them resumed) but they never take the window focus,
# so every focus-dependent assertion times out - `RootViewWithoutFocusException`, "the screen never
# took the window focus", and system Back never being dispatched all at once. That is what run
# 38537518650 on 226b71c showed. Waking the screen and dismissing the keyguard before the suite is
# the fix; the state is asserted afterwards rather than assumed, and a screen that is still not
# interactive is reported instead of being left to fail six tests mysteriously.
echo "=== emulator pre-flight: device, screen and keyguard ===" | tee -a "$LOG"
adb wait-for-device
for attempt in $(seq 1 30); do
  booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$booted" = "1" ] && break
  sleep 2
done
echo "sys.boot_completed=$booted after pre-flight wait" | tee -a "$LOG"

SCREEN_READY=0
WAKE_STATUS=0
DISMISS_STATUS=0
for attempt in $(seq 1 10); do
  # The two commands below can fail harmlessly (an emulator already awake refuses to be woken again),
  # so their exit status is captured and printed rather than suppressed: what decides readiness is
  # the state read back underneath, never the optimism of having issued the command.
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  WAKE_STATUS=$?
  adb shell wm dismiss-keyguard >/dev/null 2>&1
  DISMISS_STATUS=$?
  WAKE_STATE=$(adb shell dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness=' | tr -d '\r')
  FOCUS=$(adb shell dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus=' | tr -d '\r')
  case "$WAKE_STATE" in
    *Awake*) case "$FOCUS" in
               *Keyguard*) : ;;
               *) SCREEN_READY=1 ;;
             esac ;;
  esac
  [ "$SCREEN_READY" -eq 1 ] && break
  sleep 2
done
echo "wake=$WAKE_STATUS dismiss-keyguard=$DISMISS_STATUS attempt=$attempt" | tee -a "$LOG"
echo "power: ${WAKE_STATE:-unreadable}" | tee -a "$LOG"
echo "focus: ${FOCUS:-unreadable}" | tee -a "$LOG"
if [ "$SCREEN_READY" -ne 1 ]; then
  echo "::warning title=emulator pre-flight::the screen did not report itself awake and focused after $attempt attempts; the instrumented suite will run anyway and its result is what counts"
else
  echo "emulator pre-flight: screen awake and focused, keyguard dismissed" | tee -a "$LOG"
fi

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
SMOKE_REPORT="${RUNNER_TEMP:-/tmp}/smoke-report.txt"
rm -f "$SMOKE_REPORT"
if [ -n "$DEBUG_APK" ]; then
  chmod +x tools/android_apk_smoke.sh
  # The transcript is kept in its own file as well as in the shared log: the workflow's failure
  # annotator keeps the last sixty lines that look like errors, and a long list of failing test cases
  # can push the single "FAIL: <step>" line of the install smoke test out of that window. A smoke
  # failure that is not reported is a red job nobody can diagnose.
  bash tools/android_apk_smoke.sh "$DEBUG_APK" 2>&1 | tee -a "$LOG" "$SMOKE_REPORT"
  SMOKE_STATUS=${PIPESTATUS[0]}
  echo "android_apk_smoke exit=$SMOKE_STATUS" | tee -a "$LOG"
else
  echo "the debug APK is missing on the emulator runner" | tee -a "$LOG"
  SMOKE_STATUS=1
fi

if [ "$SMOKE_STATUS" -ne 0 ] && [ -s "$SMOKE_REPORT" ]; then
  # Keep the lines that explain the failure; if the transcript carries none, the tail still says
  # which step was running. Newlines and percent signs are percent-encoded the way the annotation
  # protocol expects, so the reason arrives as one annotation instead of being truncated away.
  SMOKE_REASON=$(grep -a -E '^(FAIL|FAIL:)' "$SMOKE_REPORT" | tail -n 12)
  if [ -z "$SMOKE_REASON" ]; then
    SMOKE_REASON=$(tail -n 12 "$SMOKE_REPORT")
  fi
  SMOKE_REASON=${SMOKE_REASON//%/%25}
  SMOKE_REASON=${SMOKE_REASON//$'\n'/%0A}
  echo "::error title=install smoke test::${SMOKE_REASON}"
fi

if [ "$INSTRUMENTED_STATUS" -ne 0 ] || [ "$SMOKE_STATUS" -ne 0 ] || [ "$REPORT_STATUS" -eq 3 ]; then
  echo "::error::instrumented tests exit=$INSTRUMENTED_STATUS, smoke test exit=$SMOKE_STATUS, report exit=$REPORT_STATUS"
  adb logcat -d -t 3000 > "$RUNNER_TEMP/logcat-tail.txt" 2>&1
  tail -n 200 "$RUNNER_TEMP/logcat-tail.txt" | tee -a "$LOG"
  exit 1
fi
echo "emulator stage finished: instrumented tests passed and the APK installed, launched and survived two restarts" | tee -a "$LOG"
