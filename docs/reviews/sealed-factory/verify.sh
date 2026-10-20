#!/usr/bin/env bash
# Reproduce the sealed-factory audit and the hardening, on throwaway copies.
#
#   ./verify.sh                 # download the sealed-factory branch, apply the patch, run everything
#   ./verify.sh --tree DIR      # use an unpacked copy of the branch instead of downloading
#   ./verify.sh --baseline      # also reproduce the defect on the unpatched tree
#
# Nothing is written inside the repository. The patch is checked before it is applied, so a patch
# that no longer matches the branch stops here instead of half-applying. No real provider key is
# needed: audits use a fake key and a closed proxy, and record the URL that would have been called.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="$HERE/0001-fix-sealed-factory-one-network-policy-a-publish-gate.patch"
BRANCH="${SEALED_BRANCH:-sealed-factory}"
REPO_SLUG="${SEALED_REPO:-regularshowrigby8-create/SAVAGE-TECH-CORNER-}"
TREE=""
BASELINE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --tree) TREE="$2"; shift 2 ;;
        --baseline) BASELINE=1; shift ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -n "$TREE" ]; then
    ROOT="$TREE"
else
    echo "=== downloading ${REPO_SLUG}@${BRANCH} ==="
    mkdir -p "$WORK/download"
    curl -sSL -o "$WORK/branch.tar.gz" \
        "https://codeload.github.com/${REPO_SLUG}/tar.gz/refs/heads/${BRANCH}"
    tar xzf "$WORK/branch.tar.gz" -C "$WORK/download"
    ROOT="$(find "$WORK/download" -mindepth 1 -maxdepth 1 -type d | head -1)"
fi

# A copy of the branch with no run state in it: reports and run directories are machine output.
copy_tree() {
    local target="$1"
    cp -a "$ROOT" "$target"
    find "$target" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
    rm -rf "$target/sealed-factories/manifest" "$target/sealed-factories/runs" "$target/.sealed"
}

PATCHED="$WORK/patched"
copy_tree "$PATCHED"

echo "=== patch check ==="
git -C "$PATCHED" apply --check "$PATCH"
git -C "$PATCHED" apply "$PATCH"
echo "PASS: patch applies to ${BRANCH}"

cat > "$WORK/audit.py" <<'PY'
"""Run a command and record every URL and every non-local socket it opens."""
import os
import runpy
import sys

LOG = os.environ["AUDIT_LOG"]
opened = []


def hook(event, args):
    if event == "urllib.Request":
        opened.append(("url", args[0]))
    elif event == "socket.connect":
        address = args[1]
        host = address[0] if isinstance(address, tuple) else str(address)
        if host not in ("127.0.0.1", "::1", "localhost"):
            opened.append(("connect", f"{host}:{address[1] if isinstance(address, tuple) else ''}"))


sys.addaudithook(hook)
script = sys.argv[1]
sys.argv = sys.argv[1:]
try:
    runpy.run_path(script, run_name="__main__")
finally:
    with open(LOG, "a", encoding="utf-8") as handle:
        for kind, value in opened:
            handle.write(f"{kind} {value}\n")
PY

FAKE_OPENAI="sk-$(printf '0%.0s' {1..32})"

# Every audit runs in its own copy of the tree under test, so the report a run writes cannot leak
# into the next step and the audited code is exactly the one named in the label. AUDIT_APPLY decides
# whether that copy carries the patch: 0 for the reproduction, 1 for the fixed tree.
AUDIT_APPLY=1
audit() {
    local label="$1"
    shift
    local tree="$WORK/audit-$(echo "$label" | tr -c 'a-z0-9' '-' | cut -c1-40)"
    copy_tree "$tree"
    if [ "$AUDIT_APPLY" = "1" ]; then
        ( cd "$tree" && git apply "$PATCH" )
    fi
    local log="$tree.audit.log"
    : > "$log"
    echo "--- ${label}"
    ( cd "$tree" && AUDIT_LOG="$log" HTTPS_PROXY=http://127.0.0.1:9 HTTP_PROXY=http://127.0.0.1:9 \
        "$@" >/dev/null 2>&1 || true )
    if [ -s "$log" ]; then
        sed 's/^/    /' "$log"
    else
        echo "    (no outbound connection)"
    fi
    rm -rf "$tree"
}

if [ "$BASELINE" = "1" ]; then
    echo "=== BEFORE the patch: the air-gap switch is not honoured by the model runtime ==="
    AUDIT_APPLY=0 audit "air-gap switch, provider keys present (unpatched)" \
        env -u GITHUB_TOKEN -u GH_TOKEN SEALED_NO_NETWORK=1 OPENAI_API_KEY="$FAKE_OPENAI" \
        ANTHROPIC_API_KEY="sk-ant-$(printf '0%.0s' {1..32})" \
        python3 "$WORK/audit.py" tools/factory/sealed_factory_runtime.py run --max-model-calls 3 --no-env
fi

echo "=== AFTER the patch ==="
audit "air-gap switch, provider keys present" \
    env -u GITHUB_TOKEN -u GH_TOKEN SEALED_NO_NETWORK=1 OPENAI_API_KEY="$FAKE_OPENAI" \
    ANTHROPIC_API_KEY="sk-ant-$(printf '0%.0s' {1..32})" \
    python3 "$WORK/audit.py" tools/factory/sealed_factory_runtime.py run --max-model-calls 3 --no-env
audit "default policy, provider key present" \
    env -u GITHUB_TOKEN -u GH_TOKEN OPENAI_API_KEY="$FAKE_OPENAI" \
    python3 "$WORK/audit.py" tools/factory/sealed_factory_runtime.py run --no-env
audit "plan-mode swarm with a GitHub token (expect a skipped step)" \
    env -u GITHUB_TOKEN -u GH_TOKEN GITHUB_TOKEN="ghp_FAKE$(printf '0%.0s' {1..20})" \
    python3 "$WORK/audit.py" sealed-factories/runtime/cli.py run --limit 5
audit "remote opt-in, closed proxy (expect exactly the provider URL)" \
    env -u GITHUB_TOKEN -u GH_TOKEN OPENAI_API_KEY="$FAKE_OPENAI" \
    python3 "$WORK/audit.py" tools/factory/sealed_factory_runtime.py run --allow-network remote \
    --max-model-calls 2 --no-env

echo "=== seal, self-check ==="
( cd "$PATCHED" && python3 sealed-factories/runtime/cli.py verify )
( cd "$PATCHED" && python3 sealed-factories/runtime/cli.py selfcheck --require-functional \
    --workspace "$WORK/selfcheck" )

echo "=== the suite, policy off ==="
rm -rf "$PATCHED/sealed-factories/manifest" "$PATCHED/sealed-factories/runs"
( cd "$PATCHED" && env -u GITHUB_TOKEN -u GH_TOKEN SEALED_NETWORK=off \
    python3 -m unittest discover -s shell/tests 2>&1 | tail -3 )

echo "=== the suite again, air-gapped (the run report now exists) ==="
( cd "$PATCHED" && env -u GITHUB_TOKEN -u GH_TOKEN SEALED_NO_NETWORK=1 \
    python3 -m unittest discover -s shell/tests 2>&1 | tail -3 )

echo "=== the copy runs itself, and the publish judge reads the run ==="
rm -rf "$PATCHED/sealed-factories/manifest" "$PATCHED/sealed-factories/runs"
( cd "$PATCHED" && env -u GITHUB_TOKEN -u GH_TOKEN python3 tools/factory/sealed_factory_runtime.py run )
( cd "$PATCHED" && python3 tools/ci/published_environment_judge.py \
    sealed-factories/manifest/RUN-REPORT.json | tail -2 )

echo
echo "=== VERIFIED ==="
echo "seal, self-check, both suite runs, the snapshot run and the judge all passed on the patched tree."
