#!/usr/bin/env bash
# Anti-dead-button guard: finds controls that look alive and do nothing.
#
#   semgrep  (semgrep/semgrep, LGPL-2.1)  empty or TODO click handlers, and
#            setters that only record a value without applying it
#   detekt   (detekt/detekt, Apache-2.0)  empty bodies, unused parameters,
#            members and imports (an unused onClick parameter = dead button)
#   Nova's own audit (nova-tv/python/nova/buttons.py, already in this repo)
#            settings and state that the UI writes and nothing reads
#
# Usage: nova-tv/guards/anti-dead-button.sh     exit 0 only if every check passed
source "$(dirname "$0")/lib.sh"
guard_start anti-dead-button "Anti-dead-button guard"

SRC="$NOVA_DIR/android/app/src"

check "semgrep rules prove they fire (semgrep --test)" semgrep -- \
  semgrep scan --test --metrics=off --disable-version-check "$GUARDS_DIR/semgrep/dead-buttons"

check "semgrep: empty / TODO handlers, setters that apply nothing" semgrep -- \
  semgrep scan --error --quiet --metrics=off --disable-version-check --text \
    --project-root "$NOVA_DIR" --config "$GUARDS_DIR/semgrep/dead-buttons/dead-buttons.yml" "$SRC"

run_detekt() {
  [[ -f "$DETEKT_JAR" ]] || { echo "detekt jar not found at $DETEKT_JAR; run guards/install-tools.sh"; return 1; }
  local inputs
  inputs="$(find "$SRC" -mindepth 2 -maxdepth 2 -type d \( -name java -o -name kotlin \) -path '*/src/*/*' \
            ! -path '*/test/*' ! -path '*/androidTest/*' | sort | paste -sd, -)"
  echo "inputs: $inputs"
  java -jar "$DETEKT_JAR" --input "$inputs" --config "$GUARDS_DIR/detekt-dead-code.yml"
}
check "detekt: empty bodies and unused parameters, members, imports" java -- run_detekt

check "Nova audit: settings/state the UI sets and nothing reads (nova/buttons.py)" jq -- \
  nova_tool_summary check_buttons.py buttons \
    '.result.findings[] | "\(.kind)  \(.file):\(.line)  \(.message)"'

guard_finish
