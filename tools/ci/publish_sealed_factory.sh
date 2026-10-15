#!/usr/bin/env bash
# Publish the sealed factory to the branch it owns - and only when it has proved itself functional.
#
# "Move it to main" is not what this does, and the reason is in the order below: the component has to
# verify, recheck itself with the factories it carries, repair what it can, pass its own tests, and then
# prove itself *again* inside the exact tree that is about to be pushed. A component that cannot do all
# of that is not published at all. Four gates, no exceptions, and nothing is written to the branch
# before the last one passes.
#
#   PUBLISH_BRANCH   the branch to publish to (default: sealed-factory). Never main, master or
#                    production - the script refuses those outright.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BRANCH="${PUBLISH_BRANCH:-sealed-factory}"
case "$BRANCH" in
    main|master|production)
        echo "::error title=Publish sealed factory::refusing to publish the component to ${BRANCH}; it goes to a branch of its own"
        exit 1
        ;;
esac
echo "=== publishing the sealed component to ${BRANCH} ==="

SELFCHECK_DIR="$(mktemp -d)/selfcheck"
GATE_FILE="${RUNNER_TEMP:-/tmp}/publish-gate.txt"
: > "$GATE_FILE"

#: What the run is doing when it is not inside a gate. The failure step reads this file too, so a failure
#: after the gates is reported as the stage it happened in rather than as the last gate that happened to
#: run before it - and it says whether the branch may already carry a commit, which is the difference
#: between "nothing was published" and "the publish worked and the reporting did not".
stage() {
    printf 'stage: %s' "$1" > "$GATE_FILE"
}

# Every gate records its own name before it runs, so the failure step can say which one refused rather
# than "a step failed". The output of the gate that refuses is annotated below it.
#: The status is captured inside the branch that ran the command. `status=$?` after the `fi` would
#: read the *if statement's* status - which is 0 when the condition failed and no else branch ran - and
#: a crashing gate was reported as "(exit 0)" exactly once because of it.
gate() {
    local name="$1"
    shift
    printf 'gate: %s' "$name" > "$GATE_FILE"
    echo "--- ${name}"
    local output
    local status
    if output="$("$@" 2>&1)"; then
        printf '%s\n' "$output"
        echo "::notice title=Publish gate::the gate '${name}' passed"
        return 0
    else
        status=$?
    fi
    echo "::error title=Publish sealed factory::the gate '${name}' refused the component (exit ${status})"
    printf '%s\n' "$output" | tail -25 | while read -r line; do
        echo "::error title=Publish sealed factory::- ${line}"
    done
    return "$status"
}

# 1. The component rechecks itself with the factories it carries and repairs what it can - including
#    re-sealing a runtime module that exists but is not pinned yet, which is what adding this round's
#    self-check to the seal looks like from inside the component. Integrity is verified next, on the
#    tree that repair produced, and the strict verdict is demanded after that. A repair may record a
#    file that is present; it may never bless a file that was changed, and that is what gate 2 is for.
if ! gate "the component rechecks itself and repairs what it can" \
        python3 sealed-factories/runtime/cli.py selfcheck --repair --workspace "$SELFCHECK_DIR"; then
    # Exit 1 here means "still not fit after repairing"; gate 3 is where that is judged, with the
    # annotations the self-check itself printed above naming every blocking finding.
    echo "the self-check still has blocking findings; the gates below decide whether that is fatal"
fi

# 2. Integrity: every payload, licence text and runtime module hashes to what the seal records.
gate "the seal verifies" python3 sealed-factories/runtime/cli.py verify

# 3. Fitness, now strictly: nothing blocking may remain, and the named states are named rather than
#    counted as fitness.
gate "the component is functional with only named states left" \
    python3 sealed-factories/runtime/cli.py selfcheck --require-functional \
    --workspace "$SELFCHECK_DIR/strict"

# 4. The component's own tests, on the tree that is about to be published.
gate "the component's own tests" python3 -m unittest \
    shell/tests/test_sealed_factory_runtime.py \
    shell/tests/test_sealed_selfcheck.py \
    shell/tests/test_license_manager.py

# 5. The snapshot, built from the component's own removal record, plus the verdict of this run.
stage "building the snapshot from the component's own removal record"
echo "--- building the snapshot"
SNAPSHOT="$(mktemp -d)/sealed-factory"
python3 tools/ci/build_component_snapshot.py --into "$SNAPSHOT" \
    --self-check "$SELFCHECK_DIR/self-check.json" \
    --source-commit "$(git rev-parse HEAD)" --source-branch "${BRANCH_SOURCE:-$BRANCH}"

# 6. The published copy proves itself, in the tree that will be pushed: this is the gate that would
#    catch a component that only worked because of a file left behind in the repository.
gate "the copy that is about to be published verifies on its own" \
    bash -c 'cd "$1" && exec python3 sealed-factories/runtime/cli.py verify' _ "$SNAPSHOT"
gate "the copy that is about to be published is functional on its own" \
    bash -c 'cd "$1" && exec python3 sealed-factories/runtime/cli.py selfcheck --require-functional \
             --workspace "$2"' _ "$SNAPSHOT" "$SELFCHECK_DIR/snapshot"

# 7. Commit it to the component's own branch: the worktree holds the snapshot and nothing else, so the
#    tree that lands is exactly the component even when a file was removed from it since the last
#    publish. No force-push: the branch keeps its history, and each publish is a commit on it.
stage "writing this run's snapshot onto the ${BRANCH} branch"
WORK="$(mktemp -d)/branch"
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
    git fetch --no-tags origin "$BRANCH:refs/remotes/origin/$BRANCH"
    git worktree add --detach "$WORK" "origin/$BRANCH"
    ( cd "$WORK" && git checkout -B "$BRANCH" )
else
    echo "--- ${BRANCH} does not exist yet: this publish creates it"
    git worktree add --detach "$WORK" HEAD
    ( cd "$WORK" && git checkout --orphan "$BRANCH" )
fi
find "$WORK" -mindepth 1 -maxdepth 1 -not -name .git -exec rm -rf {} +
cp -a "$SNAPSHOT/." "$WORK/"
# The message obeys the repository's commit-message rules, and is short enough to: a published commit
# is read by people, and one that would be refused by the commit hook a person's commit goes through is
# not written here either.
git -C "$WORK" add -A
git -C "$WORK" -c user.name="github-actions[bot]" \
    -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit \
    -m "chore(sealed-factory): publish the sealed component" \
    -m "Published by tools/ci/publish_sealed_factory.sh from the branch under review." \
    -m "The seal verified, the component rechecked itself with the factories it carries," \
    -m "its own tests passed, and the copy published here proved itself again in the" \
    -m "tree this commit contains." \
    -m "Not for main: this component is proposed for main only once this branch is green."
git -C "$WORK" push origin "HEAD:$BRANCH"
PUBLISHED="$(git -C "$WORK" rev-parse HEAD)"

# What was published is read back from the remote, not assumed from the push: a run that says it
# published a commit should be able to name the commit the branch actually carries.
REMOTE_HEAD="$(git ls-remote --heads origin "refs/heads/${BRANCH}" | cut -f1)"
if [ "$REMOTE_HEAD" != "$PUBLISHED" ]; then
    echo "::error title=Publish sealed factory::${BRANCH} carries ${REMOTE_HEAD:-nothing} and this run built ${PUBLISHED}"
    exit 1
fi
echo "--- published ${PUBLISHED} to ${BRANCH} (confirmed on the remote)"
git worktree remove --force "$WORK"

stage "writing the run's summary of what it published"
python3 - "$SELFCHECK_DIR/strict/self-check.json" "$PUBLISHED" "$BRANCH" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1]))
print(f"publish sealed factory: PASS - {report['records']} records, {report['carried']} carrying "
      f"content, {report['licence_texts']} licence texts, self-check {report['verdict']}, "
      f"seal {report['seal_root'][:16]}… -> branch {sys.argv[3]} at {sys.argv[2]}")
PY
