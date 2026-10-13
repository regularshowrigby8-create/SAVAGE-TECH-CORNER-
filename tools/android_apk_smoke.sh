#!/usr/bin/env bash
#
# Real-device smoke test for the Savage Browser APK.
#
# Requires an attached emulator or device (adb) and an APK path. Every step asserts what it
# observed; the script never prints a success message it did not verify, and it exits non-zero on
# the first unmet expectation.
#
#   usage: tools/android_apk_smoke.sh <path-to.apk> [--package com.savage.soulbrowser]
#
# What it proves (see docs/ACCEPTANCE_CRITERIA.md):
#   AC-001  the app installs and its launcher activity starts without crashing
#   AC-023/24/25/37  the app restarts cleanly after its process is killed, twice in a row
#   AC-044  the installed package matches the APK, and uninstall + reinstall works
#
set -euo pipefail

APK="${1:-}"
if [ -z "$APK" ]; then
    echo "usage: $0 <path-to.apk> [--package <applicationId>]" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "FAIL: APK not found: $APK" >&2
    exit 2
fi

PACKAGE="com.savage.soulbrowser"
ACTIVITY="com.savage.soulbrowser.ui.MainActivity"
if [ "${2:-}" = "--package" ] && [ -n "${3:-}" ]; then
    PACKAGE="$3"
fi

# A failing command aborts the script (`set -e`), and an abort that prints nothing is a red job
# nobody can diagnose - the fifth emulator run died at step 9 with no message at all. Every step
# names itself in STEP, and the trap reports the step it stopped in.
STEP="startup"
on_exit() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "FAIL: the smoke test stopped during ${STEP} (exit ${status})" >&2
    fi
}
trap on_exit EXIT

# $1 = package name. Prints the pids of that package, or nothing when it is not running.
#
# `pidof` exits non-zero when it matches nothing - which is the *expected* answer in the restart
# step - and under `set -o pipefail` an unguarded `adb shell pidof | tr` inside `$( )` makes the
# assignment itself fail, so `set -e` ends the script before it prints why: the fifth emulator run
# stopped inside step 9 with no message at all. The producer's status is therefore read here, on
# purpose, and only the "nothing matched" case is treated as an empty result. A real adb failure
# (a dead device, an unknown command) is reported.
pid_of() {
    local output status=0
    output=$(adb shell pidof "$1" 2>&1) || status=$?
    output=$(tr -d '\r' <<<"$output")
    if [ "${status:-0}" -ne 0 ] && [ -n "$output" ]; then
        echo "FAIL: could not read the process list of $1: $output" >&2
        return 1
    fi
    printf '%s' "$output"
}

adb wait-for-device
BOOT_COMPLETED=$(adb shell getprop sys.boot_completed | tr -d '\r')
if [ "$BOOT_COMPLETED" != "1" ]; then
    echo "FAIL: the device has not finished booting (sys.boot_completed=$BOOT_COMPLETED)" >&2
    exit 1
fi
STEP="[1/12] device ready: $(adb shell getprop ro.build.version.sdk | tr -d '\r') (API)"
echo "${STEP}"

STEP="[2/12] APK structure on the host"
echo "${STEP}"
[ -s "$APK" ] || { echo "FAIL: the APK is empty" >&2; exit 1; }
# The listing is read in full before it is matched. Piping a listing straight into `grep -q` makes
# the producer die of SIGPIPE as soon as grep has its answer, and with `set -o pipefail` that
# non-zero producer turns a match into "no AndroidManifest.xml" - a false failure.
APK_LISTING=$(unzip -l "$APK") || {
    echo "FAIL: the APK is not a readable ZIP archive: $APK" >&2
    exit 1
}
grep -q "AndroidManifest.xml" <<<"$APK_LISTING" || {
    echo "FAIL: the APK has no AndroidManifest.xml. Entries:" >&2
    head -n 20 <<<"$APK_LISTING" >&2
    exit 1
}
grep -qE "classes[0-9]*\.dex" <<<"$APK_LISTING" || {
    echo "FAIL: the APK has no classes.dex. Entries:" >&2
    head -n 20 <<<"$APK_LISTING" >&2
    exit 1
}
unzip -t "$APK" >/dev/null || { echo "FAIL: the APK is not a valid ZIP" >&2; exit 1; }
echo "       APK: $APK ($(stat -c%s "$APK") bytes)"

STEP="[3/12] uninstall any previous installation"
echo "${STEP}"
PACKAGES=$(adb shell pm list packages "$PACKAGE" | tr -d '\r')
if grep -qx "package:$PACKAGE" <<<"$PACKAGES"; then
    UNINSTALL_OUTPUT=$(adb uninstall "$PACKAGE" 2>&1)
    grep -q "Success" <<<"$UNINSTALL_OUTPUT" || {
        echo "FAIL: a previous installation could not be removed: $UNINSTALL_OUTPUT" >&2
        exit 1
    }
fi

STEP="[4/12] install"
echo "${STEP}"
INSTALL_OUTPUT=$(adb install -r -t "$APK" 2>&1)
sed 's/^/       /' <<<"$INSTALL_OUTPUT"
grep -q "Success" <<<"$INSTALL_OUTPUT" || { echo "FAIL: adb install did not succeed" >&2; exit 1; }

STEP="[5/12] the installed package matches the APK"
echo "${STEP}"
INSTALLED=$(adb shell pm list packages "$PACKAGE" | tr -d '\r')
grep -qx "package:$PACKAGE" <<<"$INSTALLED" || {
    echo "FAIL: $PACKAGE is not installed after a successful install" >&2
    exit 1
}
APK_PATH=$(adb shell pm path "$PACKAGE" | tr -d '\r' | sed 's/^package://')
echo "       installed at: $APK_PATH"

STEP="[6/12] launch the launcher activity"
echo "${STEP}"
LAUNCH_OUTPUT=$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1)
sed 's/^/       /' <<<"$LAUNCH_OUTPUT"
grep -qE "Status: ok" <<<"$LAUNCH_OUTPUT" || {
    echo "FAIL: the launcher activity did not report Status: ok" >&2
    exit 1
}

STEP="[7/12] the activity is the resumed top activity and the process is alive"
echo "${STEP}"
sleep 3
ACTIVITIES=$(adb shell dumpsys activity activities | tr -d '\r')
TOP=$(grep -m1 -E "ResumedActivity|mResumedActivity" <<<"$ACTIVITIES")
grep -q "$PACKAGE" <<<"$TOP" || {
    echo "FAIL: the browser is not the resumed activity: $TOP" >&2
    exit 1
}
if ! PID=$(pid_of "$PACKAGE"); then
    exit 1
fi
if [ -z "$PID" ]; then
    echo "FAIL: no live process for $PACKAGE after launch" >&2
    exit 1
fi
echo "       pid $PID, resumed: $TOP"
CRASH_LOG=$(adb logcat -d -b crash)
if grep -q "$PACKAGE" <<<"$CRASH_LOG"; then
    echo "FAIL: the app produced a crash entry on first launch" >&2
    grep "$PACKAGE" <<<"$CRASH_LOG" >&2
    exit 1
fi

STEP="[8/12] the browser chrome is on screen (view hierarchy dump)"
echo "${STEP}"
adb shell uiautomator dump /sdcard/savage_ui.xml >/dev/null
adb shell cat /sdcard/savage_ui.xml > /tmp/savage_ui.xml
for expected in "edit_omnibox" "btn_nav_back" "btn_nav_tabs" "btn_action_bookmark"; do
    grep -q "resource-id=\"$PACKAGE:id/$expected\"" /tmp/savage_ui.xml || {
        echo "FAIL: the launched browser is missing $expected" >&2
        exit 1
    }
    echo "       found $expected"
done

STEP="[9/12] kill the process and relaunch (restart persistence, first restart)"
echo "${STEP}"
# force-stop, not `am kill`: `am kill` only reclaims *background* processes, and the browser is in the
# foreground here, so it survives `am kill` - which is what made this step fail on a perfectly healthy
# build. force-stop is the platform's "this process is gone" operation, and its outcome is checked
# explicitly so a refusal is reported instead of silently ending the run.
if ! FORCE_STOP_OUTPUT=$(adb shell am force-stop "$PACKAGE" 2>&1); then
    echo "FAIL: the platform refused to stop the process: $FORCE_STOP_OUTPUT" >&2
    exit 1
fi
sleep 2
if ! ALIVE=$(pid_of "$PACKAGE"); then
    exit 1
fi
if [ -n "$ALIVE" ]; then
    echo "FAIL: the process survived force-stop" >&2
    exit 1
fi
if ! LAUNCH_OUTPUT=$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1); then
    echo "FAIL: the app did not relaunch after being killed: $LAUNCH_OUTPUT" >&2
    exit 1
fi
grep -qE "Status: ok" <<<"$LAUNCH_OUTPUT" || {
    echo "FAIL: the relaunch did not report Status: ok: $LAUNCH_OUTPUT" >&2
    exit 1
}

STEP="[10/12] kill and relaunch a second time"
echo "${STEP}"
if ! FORCE_STOP_OUTPUT=$(adb shell am force-stop "$PACKAGE" 2>&1); then
    echo "FAIL: the platform refused to stop the process on the second restart: $FORCE_STOP_OUTPUT" >&2
    exit 1
fi
sleep 2
if ! LAUNCH_OUTPUT=$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1); then
    echo "FAIL: the app did not relaunch on the second restart: $LAUNCH_OUTPUT" >&2
    exit 1
fi
grep -qE "Status: ok" <<<"$LAUNCH_OUTPUT" || {
    echo "FAIL: the second relaunch did not report Status: ok: $LAUNCH_OUTPUT" >&2
    exit 1
}

STEP="[11/12] the app is still alive and has not crashed"
echo "${STEP}"
sleep 3
if ! PID=$(pid_of "$PACKAGE"); then
    exit 1
fi
[ -n "$PID" ] || { echo "FAIL: no live process after the restarts" >&2; exit 1; }
CRASH_LOG=$(adb logcat -d -b crash)
if grep -q "$PACKAGE" <<<"$CRASH_LOG"; then
    echo "FAIL: the app crashed during the smoke test" >&2
    grep "$PACKAGE" <<<"$CRASH_LOG" >&2
    exit 1
fi

STEP="[12/12] uninstall and reinstall"
echo "${STEP}"
UNINSTALL_OUTPUT=$(adb uninstall "$PACKAGE" 2>&1)
grep -q "Success" <<<"$UNINSTALL_OUTPUT" || {
    echo "FAIL: uninstall failed: $UNINSTALL_OUTPUT" >&2
    exit 1
}
REINSTALL_OUTPUT=$(adb install -r -t "$APK" 2>&1)
sed 's/^/       /' <<<"$REINSTALL_OUTPUT"
grep -q "Success" <<<"$REINSTALL_OUTPUT" || {
    echo "FAIL: reinstall failed: $REINSTALL_OUTPUT" >&2
    exit 1
}
if ! LAUNCH_OUTPUT=$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1); then
    echo "FAIL: the reinstalled app did not launch: $LAUNCH_OUTPUT" >&2
    exit 1
fi
grep -qE "Status: ok" <<<"$LAUNCH_OUTPUT" || {
    echo "FAIL: the reinstalled app did not report Status: ok: $LAUNCH_OUTPUT" >&2
    exit 1
}

echo "SMOKE TEST PASSED: installed, launched, killed, relaunched, uninstalled and reinstalled."
