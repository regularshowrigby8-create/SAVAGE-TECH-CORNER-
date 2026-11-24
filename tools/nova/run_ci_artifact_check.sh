#!/usr/bin/env bash
# CI-only four-cell APK verification. The SDK root must come from an explicit
# environment variable; this wrapper never guesses a developer-machine path.
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "usage: run_ci_artifact_check.sh EVIDENCE_JSON" >&2
    exit 2
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK/build-tools" ]; then
    echo "::error title=Android SDK missing::set ANDROID_SDK_ROOT or ANDROID_HOME to a real SDK" >&2
    exit 1
fi
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
for tool in aapt2 apksigner zipalign; do
    if [ ! -x "$BT/$tool" ]; then
        echo "::error title=build tool missing::expected executable $BT/$tool" >&2
        exit 1
    fi
done

export NOVA_BUILD_TOOLS="$BT"
python3 tools/nova/check_artifacts.py \
    --repo . \
    --evidence "$1" \
    --json
