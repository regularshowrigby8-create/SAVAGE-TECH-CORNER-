#!/usr/bin/env bash
# Installs the imported tools the guards run, at pinned versions, into
# nova-tv/guards/.tools (git-ignored). Python tools go into the active Python
# environment from requirements.txt.
#
# Needs: curl, tar, python3 -m pip. detekt and Gradle also need Java 17.
set -euo pipefail

source "$(dirname "$0")/lib.sh"
set -e

# Everything below is also written to reports/install-tools.md.
mkdir -p "$REPORT_DIR"
exec > >(tee "$REPORT_DIR/install-tools.md") 2>&1
echo '```'
trap 'rc=$?; echo "install-tools exit status: $rc"; echo "\`\`\`"; exit $rc' EXIT

ACTIONLINT_VERSION="1.7.12"
LYCHEE_VERSION="0.24.2"

mkdir -p "$TOOLS_DIR/bin"
cd "$TOOLS_DIR"

echo "pip: semgrep, ruff, shellcheck, PyYAML"
python3 -m pip install --quiet -r "$GUARDS_DIR/requirements.txt"

if [[ ! -f "$DETEKT_JAR" ]]; then
  echo "detekt $DETEKT_VERSION"
  curl -fsSL -o "$DETEKT_JAR" \
    "https://github.com/detekt/detekt/releases/download/v$DETEKT_VERSION/detekt-cli-$DETEKT_VERSION-all.jar"
fi

if [[ ! -x bin/actionlint ]]; then
  echo "actionlint $ACTIONLINT_VERSION"
  base="https://github.com/rhysd/actionlint/releases/download/v$ACTIONLINT_VERSION"
  file="actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz"
  curl -fsSL -o "$file" "$base/$file"
  curl -fsSL -o actionlint.sha "$base/actionlint_${ACTIONLINT_VERSION}_checksums.txt"
  grep " $file\$" actionlint.sha | sha256sum -c -
  tar -xzf "$file" -C bin actionlint
  rm -f "$file" actionlint.sha
fi

if [[ ! -x bin/lychee ]]; then
  echo "lychee $LYCHEE_VERSION"
  file="lychee-x86_64-unknown-linux-gnu.tar.gz"
  base="https://github.com/lycheeverse/lychee/releases/download/lychee-v$LYCHEE_VERSION"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/$file" "$base/$file"
  curl -fsSL -o "$tmp/$file.sha256" "$base/$file.sha256"
  (cd "$tmp" && echo "$(cut -d' ' -f1 "$file.sha256")  $file" | sha256sum -c -)
  tar -xzf "$tmp/$file" -C "$tmp"
  # The binary sits at the archive root or in a lychee-* folder, depending on
  # the release (same detection as lycheeverse/lychee-action).
  bin="$(find "$tmp" -type f -name lychee | head -n 1)"
  [[ -n "$bin" ]] || { echo "no lychee binary in $file"; ls -R "$tmp"; exit 1; }
  install -m 0755 "$bin" bin/lychee
  rm -rf "$tmp"
fi

echo "installed:"
semgrep --version | sed 's/^/  semgrep /'
ruff --version | sed 's/^/  /'
shellcheck --version | sed -n 2p | sed 's/^/  shellcheck /'
bin/actionlint --version | head -1 | sed 's/^/  actionlint /'
bin/lychee --version | sed 's/^/  /'
echo "  detekt $DETEKT_VERSION ($DETEKT_JAR)"
