#!/usr/bin/env bash
# Prepares everything the master gate's GATE 8 needs on a hosted runner: the Android SDK packages
# and a debug APK built by this checkout.
#
# GATE 8 (tools/reproducible_build_verifier.py) verifies an APK that *this* checkout built. Any job
# that runs `bash .claude/scripts/gates.sh` therefore has to build one first; without that the gate
# can only ever fail and the job reports a failure for a reason that has nothing to do with the code
# under test. Three workflows have been wrong in exactly that way, so the preparation lives in one
# script and shell/tests/test_android_assets.py asserts that every workflow running the gate calls
# it (or does the equivalent itself).
#
# The script installs nothing it is not asked to and hides nothing: a missing SDK, a missing
# sdkmanager or a failing build is an error with the reason printed.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$REPO_ROOT"

if ! command -v java > /dev/null 2>&1; then
  echo "::error::no java on PATH - set up JDK 17 before calling this script" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT" ]; then
  echo "::error::no Android SDK on this runner (ANDROID_HOME unset)" >&2
  exit 1
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  SDKMANAGER=$(command -v sdkmanager || true)
fi
if [ ! -x "$SDKMANAGER" ]; then
  echo "::error::sdkmanager not found in $SDK_ROOT" >&2
  exit 1
fi

printf 'y\n%.0s' {1..40} | "$SDKMANAGER" --licenses > /dev/null
"$SDKMANAGER" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

cd "$REPO_ROOT/shell/android_project"
chmod +x gradlew
./gradlew :app:assembleDebug --stacktrace --no-daemon

cd "$REPO_ROOT"
find shell/android_project/app/build/outputs/apk -name '*.apk' -exec sha256sum {} \;
