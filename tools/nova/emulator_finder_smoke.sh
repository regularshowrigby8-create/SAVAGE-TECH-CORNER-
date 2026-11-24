#!/usr/bin/env bash
# APK finder smoke test for a GitHub-hosted Android emulator.
#
# It installs the current mobile debug APK, runs the debug-only finder probe in
# two modes (bounded production pipeline and exact UI-button methods), pulls
# app-private JSON/trace evidence, then navigates the real mobile UI with
# UIAutomator and taps "Find sites". Every assertion is explicit; a missing
# button, missing evidence, zero stored sites or zero UI-button additions fails.
set -euo pipefail

OUT="tools/nova/evidence/ci"
PKG="dev.nova.mobile"
DEBUG_ACTIVITY="dev.nova.tv.finder.diag.FinderProbeActivity"
MAIN_ACTIVITY="dev.nova.tv.mobile.MobileActivity"
mkdir -p "$OUT"

set -- nova-tv/android/app/build/outputs/apk/mobile/debug/*.apk
APK="$1"
if [ ! -f "$APK" ]; then
    echo "::error title=mobile debug APK missing::looked in nova-tv/android/app/build/outputs/apk/mobile/debug" >&2
    exit 1
fi
echo "mobile debug APK: $APK"
adb install -r -t "$APK"

wait_for_done() {
    mode="$1"
    marker="files/finder-debug/$mode.done"
    for _ in $(seq 1 180); do
        if adb shell "run-as $PKG test -f $marker" >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    echo "::error title=finder probe timeout::mode=$mode marker=$marker" >&2
    return 1
}

assert_debug_json() {
    mode="$1"
    python3 - "$OUT/$mode.json" "$mode" <<'PY'
import json, sys
path, mode = sys.argv[1], sys.argv[2]
payload = json.load(open(path))
errors = []
if payload.get("ok") is not True:
    errors.append(f"top-level ok={payload.get('ok')!r}")
result = payload.get("result", {})
if result.get("ok") is not True:
    errors.append(f"result.ok={result.get('ok')!r}")
if result.get("foundAnything") is not True:
    errors.append(f"foundAnything={result.get('foundAnything')!r}")
if result.get("browserAvailable") is not True:
    errors.append("WebView renderer context unavailable")
if mode == "pipeline":
    keys = ("websiteReport", "addonReport")
    reports = [result.get(key, {}) for key in keys]
    for key, report in zip(keys, reports):
        if report.get("marker") != "nova-finder-pipeline-v3":
            errors.append(f"{key}.marker={report.get('marker')!r}")
        if report.get("stageCounts", {}).get("03-fetch", 0) <= 0:
            errors.append(f"{key} did not fetch any engine surface")
    verified_total = sum(report.get("stageCounts", {}).get("12-verify", 0) for report in reports)
    stored_total = sum(report.get("stageCounts", {}).get("13-store", 0) for report in reports)
    if verified_total <= 0:
        errors.append("no production pipeline report reached the verifier")
    if stored_total <= 0:
        errors.append("no production pipeline report reached the store")
    if result.get("newPipelineSites", 0) <= 0:
        errors.append("production pipeline stored no new verified sites")
else:
    if result.get("uiButtonAdditions", 0) <= 0:
        errors.append("UI-button methods added no sites")
    if result.get("newUiSites", 0) <= 0:
        errors.append("UI-button methods did not grow the UI pools")
if errors:
    print("::error title=finder probe assertions::" + "; ".join(errors))
    raise SystemExit(1)
print(f"OK: {mode} finder probe stored sites={result.get('newPipelineSites', 0)} "
      f"uiSites={result.get('newUiSites', 0)} uiAdded={result.get('uiButtonAdditions', 0)}")
PY
}

run_debug_mode() {
    mode="$1"
    echo "=== debug probe mode=$mode ==="
    adb shell am force-stop "$PKG"
    adb shell pm clear "$PKG" > "$OUT/$mode.pm-clear.txt"
    adb logcat -c
    adb shell "run-as $PKG rm -f files/finder-debug/$mode.done files/finder-debug/$mode.json files/finder-debug/$mode.trace.txt"
    adb shell am start -W -n "$PKG/$DEBUG_ACTIVITY" --es mode "$mode" --ez clear true > "$OUT/$mode.start.txt"
    wait_for_done "$mode"
    adb exec-out run-as "$PKG" cat "files/finder-debug/$mode.json" > "$OUT/$mode.json"
    adb exec-out run-as "$PKG" cat "files/finder-debug/$mode.trace.txt" > "$OUT/$mode.trace.txt"
    adb logcat -d -s NovaFinderDebug:V > "$OUT/$mode.logcat.txt"
    adb logcat -d > "$OUT/$mode.logcat-all.txt"
    assert_debug_json "$mode"
}

dump_ui() {
    name="$1"
    adb shell uiautomator dump /sdcard/window.xml > /dev/null
    adb pull /sdcard/window.xml "$OUT/$name.xml" > /dev/null
}

tap_text() {
    text="$1"
    attempts="${2:-8}"
    safe="$(printf '%s' "$text" | tr -cd '[:alnum:]_-')"
    for attempt in $(seq 1 "$attempts"); do
        dump_ui "ui-attempt-$safe-$attempt"
        if coords=$(python3 tools/nova/uiautomator_tap.py "$OUT/ui-attempt-$safe-$attempt.xml" --text "$text" 2>>"$OUT/ui.log"); then
            echo "tapping '$text' at $coords"
            adb shell input tap $coords
            return 0
        fi
        adb shell input swipe 540 1600 540 600 300
        sleep 1
    done
    echo "::error title=UI node missing::could not find '$text' after $attempts dumps" >&2
    return 1
}

run_debug_mode pipeline
run_debug_mode ui

echo "=== real mobile UI navigation ==="
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/$MAIN_ACTIVITY" > "$OUT/main.start.txt"
sleep 6
tap_text "More" 6
sleep 2
tap_text "Sources" 6
sleep 2
tap_text "Find sites" 10
# The button runs a bounded production harvest; poll for its explicit result
# rather than assuming a fixed twelve seconds is enough.
for _ in $(seq 1 60); do
    adb logcat -d -s NovaFinderUi:V > "$OUT/ui-navigation.logcat.txt"
    if grep -q "Find sites result=" "$OUT/ui-navigation.logcat.txt"; then
        break
    fi
    sleep 2
done
dump_ui "after-find-sites"
adb logcat -d -s NovaFinderUi:V > "$OUT/ui-navigation.logcat.txt"
python3 - "$OUT/ui-navigation.logcat.txt" <<'PY'
import sys
text = open(sys.argv[1], errors="ignore").read()
for required in ("Find sites tapped", "Find sites result="):
    if required not in text:
        print(f"::error title=UI button not observed::missing {required!r} in logcat")
        raise SystemExit(1)
print("OK: Find sites button reached NovaViewModel and returned a result")
PY

for name in servers_pool.json addons_pool.json website_pool.json server_pool.json; do
    if adb shell "run-as $PKG test -f files/$name" >/dev/null 2>&1; then
        adb exec-out run-as "$PKG" cat "files/$name" > "$OUT/$name"
    fi
done
python3 - "$OUT/website_pool.json" "$OUT/server_pool.json" <<'PY'
import json, os, sys
counts = []
for path in sys.argv[1:]:
    if not os.path.isfile(path):
        print(f"::error title=UI pool missing::{path}")
        raise SystemExit(1)
    payload = json.load(open(path))
    lists = [value for value in payload.values() if isinstance(value, list)]
    count = max((len(value) for value in lists), default=0)
    counts.append(count)
    if count <= 0:
        print(f"::error title=UI pool empty::{path}")
        raise SystemExit(1)
print("OK: persisted UI pools contain sites/servers:", counts)
PY

adb exec-out screencap -p > "$OUT/final-screen.png"
echo "OK: emulator finder smoke complete; evidence in $OUT"
