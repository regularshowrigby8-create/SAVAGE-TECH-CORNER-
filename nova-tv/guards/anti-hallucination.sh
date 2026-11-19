#!/usr/bin/env bash
# Anti-hallucination guard: every API, workflow key, link and name the code or
# docs mention must actually exist.
#
#   Kotlin compiler (via Gradle)        every class, function and import the
#                                       app and its tests use exists
#   actionlint (rhysd/actionlint, MIT)  workflow keys, action inputs, contexts
#                                       and expression functions are real
#   lychee (lycheeverse/lychee, Apache-2.0/MIT)  every URL, relative file path
#                                       and #anchor cited in Nova's docs exists
#   ruff (astral-sh/ruff, MIT)          Python names that are used but never
#                                       defined (pyflakes F82x, F811, F632, PLE)
#
# Usage: nova-tv/guards/anti-hallucination.sh   exit 0 only if every check passed
source "$(dirname "$0")/lib.sh"
guard_start anti-hallucination "Anti-hallucination guard"

check "Kotlin compiles: app and unit tests, both flavours" java -- \
  gradle :app:compileTvDebugUnitTestKotlin :app:compileMobileDebugUnitTestKotlin

existing_workflows() { for f in "${NOVA_WORKFLOWS[@]}"; do [[ -f "$f" ]] && echo "$f"; done; }
mapfile -t WORKFLOWS < <(existing_workflows)

# ShellCheck findings are the anti-false-green guard's job (it runs ShellCheck
# with the shell GitHub really uses); here actionlint judges workflow syntax.
check "actionlint: workflow keys, action inputs, expressions" actionlint -- \
  actionlint -no-color -shellcheck= -pyflakes= "${WORKFLOWS[@]}"

mapfile -t DOCS < <(find "$NOVA_DIR" -name '*.md' \
  -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/node_modules/*' \
  -not -path '*/guards/reports/*' -not -path '*/.tools/*' | sort)
check "lychee: URLs, file paths and anchors cited in docs (${#DOCS[@]} files)" lychee -- \
  lychee --config "$GUARDS_DIR/lychee.toml" --root-dir "$REPO_ROOT" "${DOCS[@]}"

check "ruff: Python names used but never defined" ruff -- \
  ruff check --isolated --output-format concise \
    --select F821,F822,F823,F811,F632,PLE "$NOVA_DIR/python" "$GUARDS_DIR/tools"

guard_finish
