#!/usr/bin/env python3
"""ShellCheck for GitHub Actions `run:` blocks, using the shell GitHub really runs.

Glue only: every finding is ShellCheck's (https://github.com/koalaman/shellcheck).
This file exists because the usual wrapper, actionlint, prepends
`set -eo pipefail` to every bash step (rule_shellcheck.go, v1.7.12), while
GitHub only adds `pipefail` when a step says `shell: bash` explicitly. A step
with no `shell:` runs as `bash -e {0}`, so `./gradlew test | tee log` exits
with tee's status and a failing test suite shows green. actionlint cannot see
that; ShellCheck's SC2312 can, once it is told the real shell.

Two kinds of finding are reported:
  * every ShellCheck warning or error (real script bugs), and
  * SC2312 where the masked command is the left side of a pipe -- the exit
    status that GitHub throws away. Steps that turn errexit off (`set +e`)
    manage their own status and are not reported for SC2312.

Usage: workflow_shellcheck.py WORKFLOW.yml [...]   (exit 1 if anything found)
"""
from __future__ import annotations

import json
import re
import subprocess
import sys

import yaml  # PyYAML

# GitHub's documented defaults (workflow syntax, jobs.<id>.steps[*].shell).
PRELUDE = {
    None: "set -e",              # no shell: -> bash -e {0}  (NO pipefail)
    "bash": "set -eo pipefail",  # shell: bash -> bash --noprofile --norc -eo pipefail {0}
    "sh": "set -e",
}
EXPR = re.compile(r"\$\{\{.*?\}\}", re.DOTALL)


def run_blocks(path):
    doc = yaml.safe_load(open(path)) or {}
    wf_shell = ((doc.get("defaults") or {}).get("run") or {}).get("shell")
    for job_id, job in (doc.get("jobs") or {}).items():
        job_shell = ((job.get("defaults") or {}).get("run") or {}).get("shell")
        for i, step in enumerate(job.get("steps") or []):
            if "run" not in step:
                continue
            shell = step.get("shell") or job_shell or wf_shell
            if shell not in PRELUDE:
                continue  # pwsh, python, custom templates are not ShellCheck's job
            yield f"{path} :: {job_id} :: {step.get('name') or f'step {i + 1}'}", shell, step["run"]


def shellcheck(shell, script):
    body = EXPR.sub(lambda m: "_" * len(m.group(0)), script)  # as actionlint does
    lines = [PRELUDE[shell]] + body.splitlines()
    proc = subprocess.run(
        ["shellcheck", "--norc", "--format=json1",
         "--shell", "sh" if shell == "sh" else "bash",
         "--enable=check-extra-masked-returns", "--severity=info",
         # Same exclusions as actionlint, for the same reasons (CI-only env).
         "--exclude=SC1091,SC2194,SC2050,SC2153,SC2154,SC2157,SC2043", "-"],
        input="\n".join(lines) + "\n", text=True, capture_output=True,
    )
    return lines, json.loads(proc.stdout or '{"comments": []}')["comments"]


def keep(comment, lines, errexit_off):
    if comment["level"] in ("error", "warning"):
        return True
    if comment["code"] != 2312 or errexit_off:
        return False
    line = lines[comment["endLine"] - 1]
    after = line[comment["endColumn"] - 1:].lstrip()
    return after.startswith("|") and not after.startswith("||")


def main(paths):
    found = 0
    for path in paths:
        for label, shell, script in run_blocks(path):
            lines, comments = shellcheck(shell, script)
            errexit_off = bool(re.search(r"^\s*set\s+\+e\b", script, re.MULTILINE))
            hits = [c for c in comments if keep(c, lines, errexit_off)]
            if not hits:
                continue
            mode = "no shell: -> bash -e, NO pipefail" if shell is None else f"shell: {shell}"
            print(f"=== {label}  ({mode})")
            for c in hits:
                src = lines[c["line"] - 1].strip()
                print(f"  run line {c['line'] - 1}: SC{c['code']} ({c['level']}): {c['message']}")
                print(f"      {src}")
            found += len(hits)
    print(f"workflow_shellcheck: {found} finding(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
