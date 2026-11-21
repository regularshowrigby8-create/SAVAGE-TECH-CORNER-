#!/usr/bin/env bash
# Install the packages requested by the pinned emulator action. This runs on a
# disposable CI runner, not on a viewer's device. Output is captured by the
# workflow, including failures, before the action begins.
set -euo pipefail

sdkmanager="${ANDROID_HOME:?Android SDK is required}/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
  echo "sdkmanager not found at $sdkmanager"
  exit 1
fi

echo 'Accepting Android SDK licenses.'
# `yes` normally receives SIGPIPE when sdkmanager stops reading. The pipeline
# status must reflect sdkmanager, not that expected SIGPIPE.
set +o pipefail
if yes | "$sdkmanager" --licenses; then
  echo 'SDK licenses accepted.'
else
  echo 'SDK license acceptance failed.'
  exit 1
fi
set -o pipefail

# These match the packages requested by the pinned emulator action. The API 34
# Android TV image is published as x86, not x86_64.
for package in 'build-tools;37.0.0' 'platform-tools' 'platforms;android-34' \
               'emulator' 'system-images;android-34;android-tv;x86'; do
  ok=0
  for attempt in 1 2 3; do
    echo "Installing $package (attempt $attempt of 3)"
    if timeout 300 "$sdkmanager" --install "$package"; then
      ok=1
      break
    fi
    echo "Install failed on attempt $attempt."
    if [ "$attempt" -lt 3 ]; then sleep "$(( attempt * 5 ))"; fi
  done
  if [ "$ok" != 1 ]; then
    echo "Could not install $package after three attempts."
    exit 1
  fi
done

if [ ! -f "$ANDROID_HOME/system-images/android-34/android-tv/x86/source.properties" ]; then
  echo 'SDK manager returned success but the Android TV image metadata is missing.'
  exit 1
fi
echo 'Android TV API 34 x86 image is installed.'
