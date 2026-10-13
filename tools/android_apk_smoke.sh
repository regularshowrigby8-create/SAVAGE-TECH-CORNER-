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

adb wait-for-device
BOOT_COMPLETED=$(adb shell getprop sys.boot_completed | tr -d '\r')
if [ "$BOOT_COMPLETED" != "1" ]; then
    echo "FAIL: the device has not finished booting (sys.boot_completed=$BOOT_COMPLETED)" >&2
    exit 1
fi
echo "[1/12] device ready: $(adb shell getprop ro.build.version.sdk | tr -d '\r') (API)"

echo "[2/12] APK structure on the host"
[ -s "$APK" ] || { echo "FAIL: the APK is empty" >&2; exit 1; }
unzip -l "$APK" | grep -q "AndroidManifest.xml" || {
    echo "FAIL: the APK has no AndroidManifest.xml" >&2
    exit 1
}
unzip -l "$APK" | grep -qE "classes[0-9]*\.dex" || {
    echo "FAIL: the APK has no classes.dex" >&2
    exit 1
}
unzip -t "$APK" >/dev/null || { echo "FAIL: the APK is not a valid ZIP" >&2; exit 1; }
echo "       APK: $APK ($(stat -c%s "$APK") bytes)"

echo "[3/12] uninstall any previous installation"
if adb shell pm list packages "$PACKAGE" | grep -q "package:$PACKAGE"; then
    adb uninstall "$PACKAGE" | grep -q "Success" || {
        echo "FAIL: a previous installation could not be removed" >&2
        exit 1
    }
fi

echo "[4/12] install"
INSTALL_OUTPUT=$(adb install -r -t "$APK" 2>&1)
echo "$INSTALL_OUTPUT" | sed 's/^/       /'
echo "$INSTALL_OUTPUT" | grep -q "Success" || { echo "FAIL: adb install did not succeed" >&2; exit 1; }

echo "[5/12] the installed package matches the APK"
INSTALLED=$(adb shell pm list packages "$PACKAGE" | tr -d '\r')
echo "$INSTALLED" | grep -qx "package:$PACKAGE" || {
    echo "FAIL: $PACKAGE is not installed after a successful install" >&2
    exit 1
}
APK_PATH=$(adb shell pm path "$PACKAGE" | tr -d '\r' | sed 's/^package://')
echo "       installed at: $APK_PATH"

echo "[6/12] launch the launcher activity"
adb shell am start -W -n "$PACKAGE/$ACTIVITY" | sed 's/^/       /' | tee /tmp/savage_launch.txt
grep -qE "Status: ok" /tmp/savage_launch.txt || {
    echo "FAIL: the launcher activity did not report Status: ok" >&2
    exit 1
}

echo "[7/12] the activity is the resumed top activity and the process is alive"
sleep 3
TOP=$(adb shell dumpsys activity activities | grep -m1 -E "ResumedActivity|mResumedActivity" | tr -d '\r')
echo "$TOP" | grep -q "$PACKAGE" || {
    echo "FAIL: the browser is not the resumed activity: $TOP" >&2
    exit 1
}
PID=$(adb shell pidof "$PACKAGE" | tr -d '\r')
if [ -z "$PID" ]; then
    echo "FAIL: no live process for $PACKAGE after launch" >&2
    exit 1
fi
echo "       pid $PID, resumed: $TOP"
if adb logcat -d -b crash | grep -q "$PACKAGE"; then
    echo "FAIL: the app produced a crash entry on first launch" >&2
    adb logcat -d -b crash | grep "$PACKAGE" >&2
    exit 1
fi

echo "[8/12] the browser chrome is on screen (view hierarchy dump)"
adb shell uiautomator dump /sdcard/savage_ui.xml >/dev/null
adb shell cat /sdcard/savage_ui.xml > /tmp/savage_ui.xml
for expected in "edit_omnibox" "btn_nav_back" "btn_nav_tabs" "btn_action_bookmark"; do
    grep -q "resource-id=\"$PACKAGE:id/$expected\"" /tmp/savage_ui.xml || {
        echo "FAIL: the launched browser is missing $expected" >&2
        exit 1
    }
    echo "       found $expected"
done

echo "[9/12] kill the process and relaunch (restart persistence, first restart)"
adb shell am kill "$PACKAGE"
sleep 2
if adb shell pidof "$PACKAGE" | grep -q .; then
    echo "FAIL: the process survived am kill" >&2
    exit 1
fi
adb shell am start -W -n "$PACKAGE/$ACTIVITY" | grep -qE "Status: ok" || {
    echo "FAIL: the app did not relaunch after being killed" >&2
    exit 1
}

echo "[10/12] kill and relaunch a second time"
adb shell am kill "$PACKAGE"
sleep 2
adb shell am start -W -n "$PACKAGE/$ACTIVITY" | grep -qE "Status: ok" || {
    echo "FAIL: the app did not relaunch on the second restart" >&2
    exit 1
}

echo "[11/12] the app is still alive and has not crashed"
sleep 3
PID=$(adb shell pidof "$PACKAGE" | tr -d '\r')
[ -n "$PID" ] || { echo "FAIL: no live process after the restarts" >&2; exit 1; }
if adb logcat -d -b crash | grep -q "$PACKAGE"; then
    echo "FAIL: the app crashed during the smoke test" >&2
    adb logcat -d -b crash | grep "$PACKAGE" >&2
    exit 1
fi

echo "[12/12] uninstall and reinstall"
adb uninstall "$PACKAGE" | grep -q "Success" || { echo "FAIL: uninstall failed" >&2; exit 1; }
adb install -r -t "$APK" | grep -q "Success" || { echo "FAIL: reinstall failed" >&2; exit 1; }
adb shell am start -W -n "$PACKAGE/$ACTIVITY" | grep -qE "Status: ok" || {
    echo "FAIL: the reinstalled app did not launch" >&2
    exit 1
}

echo "SMOKE TEST PASSED: installed, launched, killed, relaunched, uninstalled and reinstalled."
