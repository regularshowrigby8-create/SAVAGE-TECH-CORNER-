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

# 1. Integrity: every payload, licence text and runtime module still hashes to what the seal recorded.
echo "--- the seal verifies"
python3 sealed-factories/runtime/cli.py verify

# 2. Fitness: the component rechecks itself with the factories it carries - the same reader a run uses,
#    every repair handed to the functional record whose own definitions name that capability - and it
#    must come out functional with only named states left.
echo "--- the component rechecks itself"
python3 sealed-factories/runtime/cli.py selfcheck --repair --require-functional \
    --workspace "$SELFCHECK_DIR"

# 3. The component's own tests, on the tree that is about to be published.
echo "--- the component's own tests"
python3 -m unittest shell/tests/test_sealed_factory_runtime.py \
                     shell/tests/test_sealed_selfcheck.py \
                     shell/tests/test_license_manager.py

# 4. The snapshot, built from the component's own removal record, plus the verdict of this run.
echo "--- building the snapshot"
SNAPSHOT="$(mktemp -d)/sealed-factory"
python3 tools/ci/build_component_snapshot.py --into "$SNAPSHOT" \
    --self-check "$SELFCHECK_DIR/self-check.json" \
    --source-commit "$(git rev-parse HEAD)" --source-branch "${BRANCH_SOURCE:-$BRANCH}"

# 5. The published copy proves itself, in the tree that will be pushed: this is the gate that would
#    catch a component that only worked because of a file left behind in the repository.
echo "--- the copy that is about to be published proves itself on its own"
( cd "$SNAPSHOT" && python3 sealed-factories/runtime/cli.py verify )
( cd "$SNAPSHOT" && python3 sealed-factories/runtime/cli.py selfcheck --require-functional \
      --workspace "$SELFCHECK_DIR/snapshot" )

# 6. Commit it to the component's own branch: the worktree holds the snapshot and nothing else, so the
#    tree that lands is exactly the component even when a file was removed from it since the last
#    publish. No force-push: the branch keeps its history, and each publish is a commit on it.
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
(
    cd "$WORK"
    git add -A
    git -c user.name="github-actions[bot]" \
        -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
        commit \
        -m "chore(sealed-factory): publish the sealed component" \
        -m "Published by tools/ci/publish_sealed_factory.sh from the branch under review." \
        -m "The seal verified, the component rechecked itself with the factories it carries," \
        -m "its own tests passed, and the copy published here proved itself again in the" \
        -m "tree this commit contains." \
        -m "Not for main: this component is proposed for main only once this branch is green."
    git push origin "HEAD:$BRANCH"
    PUBLISHED="$(git rev-parse HEAD)"
)
echo "--- published ${PUBLISHED} to ${BRANCH}"
git worktree remove --force "$WORK"

python3 - "$SELFCHECK_DIR/self-check.json" "$PUBLISHED" "$BRANCH" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1]))
print(f"publish sealed factory: PASS - {report['records']} records, {report['carried']} carrying "
      f"content, {report['licence_texts']} licence texts, self-check {report['verdict']}, "
      f"seal {report['seal_root'][:16]}… -> branch {sys.argv[3]} at {sys.argv[2]}")
PY
