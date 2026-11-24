#!/usr/bin/env python3
"""Strict, evidence-based verification gate for Nova build changes.

This gate exists to make five failure modes impossible to report as green:

* hallucinated claims: every numeric/source claim is checked against a file;
* false green: a command must exit successfully AND contain the exact success
  evidence, and nested result objects are asserted explicitly;
* stubs: forbidden stub markers are scanned in the files that own the logic;
* silent failures: missing tools, timeouts, skipped tests, uncovered changed
  files, empty command sets and unwritten evidence all fail the gate;
* guessing: the caller supplies an explicit contract, base revision, head
  revision, profile, evidence path and output log path. Nothing is inferred;
  the exact changed-file bytes are fingerprinted into the evidence, and any
  profile limitations are copied from the contract into the result. The gate's
  own evidence/log outputs are excluded from the changed-file fingerprint so
  the record binds source bytes rather than referencing itself.

There is no skip-success path. Commands whose profile is not selected are not
run, but the selected profile and its minimum command count are explicit in the
contract and in the evidence. Every selected command runs and must prove itself.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import traceback
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1


class GateFailure(Exception):
    """A deterministic, user-visible verification failure."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def glob_to_regex(pattern: str) -> re.Pattern[str]:
    """Translate a repository-relative glob into an anchored regular expression.

    ``**`` crosses directory separators; ``*`` does not. This intentionally
    supports only the forms used by the contract so that scope matching is
    deterministic rather than dependent on a third-party glob implementation.
    """
    out = ["^"]
    i = 0
    while i < len(pattern):
        char = pattern[i]
        if char == "*":
            if i + 1 < len(pattern) and pattern[i + 1] == "*":
                i += 2
                if i < len(pattern) and pattern[i] == "/":
                    i += 1
                    out.append("(?:.*/)?")
                else:
                    out.append(".*")
            else:
                out.append("[^/]*")
                i += 1
            continue
        if char == "?":
            out.append("[^/]")
            i += 1
            continue
        if char == "[":
            end = pattern.find("]", i + 1)
            if end == -1:
                out.append(r"\[")
                i += 1
                continue
            body = pattern[i + 1 : end]
            if body.startswith("!"):
                body = "^" + body[1:]
            out.append("[" + body.replace("\\", r"\\") + "]")
            i = end + 1
            continue
        out.append(re.escape(char))
        i += 1
    out.append("$")
    return re.compile("".join(out))


def matches(path: str, patterns: list[str]) -> bool:
    return any(glob_to_regex(pattern).match(path) for pattern in patterns)


def strip_line_comments(line: str) -> str:
    """Remove whole-line and trailing ``//`` or ``#`` comments.

    The whitespace guard prevents ``https://`` from being mistaken for a
    comment. This is a static gate, not a language parser: patterns are written
    so that code-shaped failures are caught without pretending to parse Kotlin.
    """
    without_slashes = re.sub(r"(?:^|\s)//.*$", " ", line)
    return re.sub(r"(?:^|\s)#.*$", " ", without_slashes)


def run_git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise GateFailure(
            "git command failed: "
            + " ".join(["git", *args])
            + "\nstdout: "
            + completed.stdout
            + "\nstderr: "
            + completed.stderr
        )
    return completed.stdout


def changed_files(repo: Path, base: str, head: str) -> list[str]:
    run_git(repo, "cat-file", "-e", f"{base}^{{commit}}")
    if head != "WORKTREE":
        run_git(repo, "cat-file", "-e", f"{head}^{{commit}}")
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, head],
            cwd=str(repo),
            capture_output=True,
            text=True,
            check=False,
        )
        if ancestor.returncode != 0:
            raise GateFailure(
                f"base {base} is not an ancestor of head {head}; "
                "refusing to infer a diff"
            )
        raw = run_git(
            repo, "diff", "--name-only", "--diff-filter=ACMRT", "-z", base, head
        )
        names = [item for item in raw.split("\0") if item]
    else:
        raw = run_git(
            repo, "diff", "--name-only", "--diff-filter=ACMRT", "-z", base
        )
        names = [item for item in raw.split("\0") if item]
        untracked = run_git(
            repo, "ls-files", "--others", "--exclude-standard", "-z"
        )
        names.extend(item for item in untracked.split("\0") if item)
    return sorted(set(names))


def is_binary(path: Path) -> bool:
    with path.open("rb") as handle:
        return b"\0" in handle.read(8192)


def read_utf8(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise GateFailure(f"{path}: not valid UTF-8: {error}") from error


def verification_output_paths(repo: Path, evidence_path: Path, log_path: Path) -> list[str]:
    """Return repository-relative verification outputs, which are not source."""
    root = repo.resolve()
    out: set[str] = set()
    for candidate in (evidence_path.resolve(), log_path.resolve()):
        try:
            out.add(str(candidate.relative_to(root)))
        except ValueError:
            # Outputs intentionally outside the repository cannot pollute the
            # repository's changed-file list.
            continue
    return sorted(out)


def worktree_fingerprint(repo: Path, files: list[str]) -> dict[str, Any]:
    """Bind evidence to the exact bytes of every changed worktree path."""
    digest = hashlib.sha256()
    hashes: dict[str, str] = {}
    for rel in files:
        path = repo / rel
        rel_bytes = rel.encode("utf-8")
        digest.update(len(rel_bytes).to_bytes(8, "big"))
        digest.update(rel_bytes)
        if path.is_file():
            file_hash = sha256_file(path)
            hashes[rel] = file_hash
            digest.update(file_hash.encode("ascii"))
        else:
            hashes[rel] = "MISSING"
            digest.update(b"MISSING")
    return {"sha256": digest.hexdigest(), "file_sha256": hashes}


def dotted_value(payload: Any, path: str) -> Any:
    if path in ("", "."):
        return payload
    current = payload
    for part in path.split("."):
        if isinstance(current, dict) and part in current:
            current = current[part]
        elif isinstance(current, list) and part.isdigit():
            index = int(part)
            if index >= len(current):
                raise GateFailure(f"JSON path {path!r} index {index} is out of range")
            current = current[index]
        else:
            raise GateFailure(f"JSON path {path!r} is absent from the result")
    return current


def check_assertion(payload: Any, assertion: dict[str, Any]) -> str | None:
    path = assertion.get("path")
    if not isinstance(path, str):
        raise GateFailure(f"assertion has no string path: {assertion!r}")
    try:
        actual = dotted_value(payload, path)
    except GateFailure as error:
        return str(error)
    if "equals" in assertion and actual != assertion["equals"]:
        return f"{path}: expected {assertion['equals']!r}, got {actual!r}"
    if "min" in assertion:
        if not isinstance(actual, (int, float)) or actual < assertion["min"]:
            return f"{path}: expected >= {assertion['min']!r}, got {actual!r}"
    if "max" in assertion:
        if not isinstance(actual, (int, float)) or actual > assertion["max"]:
            return f"{path}: expected <= {assertion['max']!r}, got {actual!r}"
    if assertion.get("empty") is True:
        if actual not in ([], {}, "", None):
            return f"{path}: expected empty, got {actual!r}"
    if "contains" in assertion:
        if assertion["contains"] not in actual:
            return f"{path}: expected to contain {assertion['contains']!r}"
    return None


def validate_contract(contract: dict[str, Any], profile: str) -> None:
    required = [
        "schema_version",
        "name",
        "scope_globs",
        "inspection_globs",
        "allowed_binary_paths",
        "allowed_binary_hashes",
        "required_files",
        "required_patterns",
        "forbidden_patterns",
        "source_claims",
        "commands",
        "minimum_commands_by_profile",
    ]
    missing = [key for key in required if key not in contract]
    if missing:
        raise GateFailure(f"contract is missing fields: {missing}")
    if contract["schema_version"] != SCHEMA_VERSION:
        raise GateFailure(
            f"unsupported contract schema {contract['schema_version']!r}; "
            f"expected {SCHEMA_VERSION}"
        )
    if profile not in contract["minimum_commands_by_profile"]:
        raise GateFailure(f"profile {profile!r} has no explicit minimum command count")
    command_ids: set[str] = set()
    for command in contract["commands"]:
        for key in ("id", "argv", "cwd", "profiles", "timeout"):
            if key not in command:
                raise GateFailure(f"command is missing {key!r}: {command!r}")
        if command["id"] in command_ids:
            raise GateFailure(f"duplicate command id {command['id']!r}")
        command_ids.add(command["id"])
        if not isinstance(command["argv"], list) or not all(
            isinstance(item, str) for item in command["argv"]
        ):
            raise GateFailure(f"command {command['id']!r} argv must be a string list")
        if not isinstance(command["profiles"], list) or not command["profiles"]:
            raise GateFailure(f"command {command['id']!r} needs explicit profiles")
        if not isinstance(command["timeout"], int) or command["timeout"] <= 0:
            raise GateFailure(f"command {command['id']!r} needs a positive timeout")
        if "env" in command:
            if not isinstance(command["env"], dict) or not all(
                isinstance(key, str) and isinstance(value, str)
                for key, value in command["env"].items()
            ):
                raise GateFailure(f"command {command['id']!r} env must map strings to strings")
    for rule in contract["forbidden_patterns"]:
        for key in ("id", "globs", "patterns"):
            if key not in rule:
                raise GateFailure(f"forbidden rule is missing {key!r}: {rule!r}")
        for pattern in rule["patterns"]:
            re.compile(pattern)
    for rule in contract["required_patterns"]:
        for key in ("id", "globs", "patterns"):
            if key not in rule:
                raise GateFailure(f"required rule is missing {key!r}: {rule!r}")
        for pattern in rule["patterns"]:
            re.compile(pattern)


def static_checks(
    repo: Path, contract: dict[str, Any], files: list[str]
) -> tuple[list[dict[str, Any]], list[str]]:
    checks: list[dict[str, Any]] = []
    errors: list[str] = []
    for rel in contract["required_files"]:
        path = repo / rel
        if not path.is_file():
            errors.append(f"required file missing: {rel}")
        checks.append({"kind": "required_file", "path": rel, "ok": path.is_file()})

    for rel in files:
        path = repo / rel
        if not path.exists():
            errors.append(f"changed path disappeared during verification: {rel}")
            continue
        if not matches(rel, contract["scope_globs"]):
            errors.append(f"changed file outside declared scope: {rel}")
        if not matches(rel, contract["inspection_globs"]):
            errors.append(f"changed file has no inspection coverage: {rel}")
        if is_binary(path):
            if rel not in contract["allowed_binary_paths"]:
                errors.append(f"changed binary file is not explicitly allowed: {rel}")
            expected_hash = contract["allowed_binary_hashes"].get(rel)
            if not isinstance(expected_hash, str):
                errors.append(f"allowed binary file has no pinned sha256: {rel}")
            else:
                actual_hash = sha256_file(path)
                if actual_hash != expected_hash:
                    errors.append(
                        f"binary hash mismatch for {rel}: "
                        f"expected {expected_hash}, got {actual_hash}"
                    )
                checks.append(
                    {
                        "kind": "binary_hash",
                        "path": rel,
                        "sha256": actual_hash,
                        "ok": actual_hash == expected_hash,
                    }
                )
            continue
        text = read_utf8(path)

        for rule in contract["forbidden_patterns"]:
            if not matches(rel, rule["globs"]):
                continue
            hits: list[dict[str, Any]] = []
            for number, original in enumerate(text.splitlines(), 1):
                line = strip_line_comments(original) if rule.get("strip_comments") else original
                for pattern in rule["patterns"]:
                    if re.search(pattern, line):
                        hits.append(
                            {
                                "line": number,
                                "pattern": pattern,
                                "text": original.strip()[:240],
                            }
                        )
            checks.append(
                {
                    "kind": "forbidden_pattern",
                    "rule": rule["id"],
                    "path": rel,
                    "hits": hits,
                    "ok": not hits,
                }
            )
            if hits:
                errors.append(
                    f"{rule['id']}: {rel} has {len(hits)} forbidden marker(s)"
                )

    for rule in contract["required_patterns"]:
        candidates = [
            rel
            for rel in files
            if matches(rel, rule["globs"]) and (repo / rel).is_file() and not is_binary(repo / rel)
        ]
        if not candidates:
            errors.append(f"{rule['id']}: no changed file matched its required-pattern globs")
            continue
        combined = "\n".join(read_utf8(repo / rel) for rel in candidates)
        for pattern in rule["patterns"]:
            found = re.search(pattern, combined) is not None
            checks.append(
                {
                    "kind": "required_pattern",
                    "rule": rule["id"],
                    "pattern": pattern,
                    "paths": candidates,
                    "ok": found,
                }
            )
            if not found:
                errors.append(f"{rule['id']}: required evidence {pattern!r} is absent")

    for claim in contract["source_claims"]:
        kind = claim.get("kind")
        rel = claim.get("path")
        if not isinstance(rel, str):
            errors.append(f"source claim has no path: {claim!r}")
            continue
        path = repo / rel
        if not path.is_file():
            errors.append(f"source claim file missing: {rel}")
            continue
        text = read_utf8(path)
        if kind == "regex_count":
            count = len(re.findall(claim["regex"], text))
        elif kind == "literal_count":
            count = text.count(claim["literal"])
        elif kind == "literal_all":
            count = sum(1 for literal in claim["literals"] if literal in text)
        else:
            errors.append(f"unknown source claim kind {kind!r}")
            continue
        minimum = claim.get("min")
        maximum = claim.get("max")
        ok = (minimum is None or count >= minimum) and (maximum is None or count <= maximum)
        checks.append(
            {
                "kind": "source_claim",
                "id": claim.get("id"),
                "path": rel,
                "count": count,
                "min": minimum,
                "max": maximum,
                "ok": ok,
            }
        )
        if not ok:
            errors.append(
                f"source claim {claim.get('id')!r} not proven: count={count}, "
                f"min={minimum}, max={maximum}"
            )
    return checks, errors


def execute_commands(
    repo: Path,
    contract: dict[str, Any],
    profile: str,
    log_path: Path,
) -> tuple[list[dict[str, Any]], list[str], list[dict[str, str]]]:
    records: list[dict[str, Any]] = []
    errors: list[str] = []
    excluded: list[dict[str, str]] = []
    selected = [
        command
        for command in contract["commands"]
        if profile in command["profiles"]
    ]
    minimum = contract["minimum_commands_by_profile"][profile]
    if len(selected) < minimum:
        errors.append(
            f"profile {profile!r} selected {len(selected)} commands; "
            f"contract requires at least {minimum}"
        )
    for command in contract["commands"]:
        if command not in selected:
            excluded.append(
                {
                    "id": command["id"],
                    "reason": f"explicitly not in profile {profile}",
                    "profiles": ", ".join(command["profiles"]),
                }
            )

    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        for command in selected:
            command_id = command["id"]
            argv = command["argv"]
            cwd = repo / command["cwd"]
            started = time.monotonic()
            record: dict[str, Any] = {
                "id": command_id,
                "argv": argv,
                "cwd": command["cwd"],
                "profile": profile,
                "status": "FAIL",
            }
            log.write(f"\n===== COMMAND {command_id} =====\n")
            log.write(f"argv: {json.dumps(argv)}\n")
            log.write(f"cwd: {cwd}\n")
            try:
                if not cwd.is_dir():
                    raise GateFailure(f"command working directory missing: {command['cwd']}")
                command_env = os.environ.copy()
                command_env.update(command.get("env", {}))
                completed = subprocess.run(
                    argv,
                    cwd=str(cwd),
                    env=command_env,
                    capture_output=True,
                    text=True,
                    timeout=command["timeout"],
                    check=False,
                )
                stdout = completed.stdout
                stderr = completed.stderr
                returncode = completed.returncode
                record["returncode"] = returncode
                record["stdout_sha256"] = sha256_bytes(stdout.encode("utf-8"))
                record["stderr_sha256"] = sha256_bytes(stderr.encode("utf-8"))
                record["stdout_tail"] = stdout[-4000:]
                record["stderr_tail"] = stderr[-4000:]
                log.write(stdout)
                if stderr:
                    log.write("\n----- stderr -----\n")
                    log.write(stderr)
                combined = stdout + "\n" + stderr
                command_errors: list[str] = []
                expected_exit = command.get("expect_exit", 0)
                if returncode != expected_exit:
                    command_errors.append(
                        f"exit {returncode}, expected {expected_exit}"
                    )
                for pattern in command.get("required_stdout", []):
                    if not re.search(pattern, combined):
                        command_errors.append(f"missing success evidence {pattern!r}")
                for pattern in command.get("forbidden_stdout", []):
                    if re.search(pattern, combined):
                        command_errors.append(f"forbidden output evidence {pattern!r}")
                if "json_path" in command or "json_assertions" in command:
                    try:
                        payload = json.loads(stdout)
                    except json.JSONDecodeError as error:
                        command_errors.append(f"stdout is not valid JSON: {error}")
                    else:
                        root = dotted_value(payload, command.get("json_path", ""))
                        for assertion in command.get("json_assertions", []):
                            problem = check_assertion(root, assertion)
                            if problem:
                                command_errors.append(problem)
                if command_errors:
                    record["errors"] = command_errors
                    errors.extend(f"{command_id}: {item}" for item in command_errors)
                else:
                    record["status"] = "PASS"
                print(
                    f"[strict-verify] {command_id}: {record['status']} "
                    f"({time.monotonic() - started:.2f}s)"
                )
            except subprocess.TimeoutExpired as error:
                message = f"timeout after {command['timeout']}s: {error}"
                record["errors"] = [message]
                errors.append(f"{command_id}: {message}")
                log.write(f"TIMEOUT: {message}\n")
                print(f"[strict-verify] {command_id}: FAIL ({message})")
            except (OSError, GateFailure) as error:
                message = str(error)
                record["errors"] = [message]
                errors.append(f"{command_id}: {message}")
                log.write(f"ERROR: {message}\n")
                print(f"[strict-verify] {command_id}: FAIL ({message})")
            finally:
                record["duration_seconds"] = round(time.monotonic() - started, 3)
                log.write(f"===== END {command_id} =====\n")
                log.flush()
                records.append(record)
    if len(records) != len(selected):
        errors.append(
            f"only {len(records)} of {len(selected)} selected commands produced evidence"
        )
    return records, errors, excluded


def write_evidence(evidence_path: Path, evidence: dict[str, Any]) -> None:
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def verify(args: argparse.Namespace) -> int:
    repo = Path(args.repo).resolve()
    contract_path = Path(args.contract).resolve()
    evidence_path = Path(args.evidence).resolve()
    log_path = Path(args.log).resolve()
    if not repo.is_dir() or not (repo / ".git").exists():
        raise GateFailure(f"repository root is not a git checkout: {repo}")
    if not contract_path.is_file():
        raise GateFailure(f"contract file missing: {contract_path}")
    contract_text = read_utf8(contract_path)
    try:
        contract = json.loads(contract_text)
    except json.JSONDecodeError as error:
        raise GateFailure(f"contract is not valid JSON: {error}") from error
    if not isinstance(contract, dict):
        raise GateFailure("contract root must be a JSON object")
    validate_contract(contract, args.profile)
    files = changed_files(repo, args.base, args.head)
    verification_outputs_excluded_from_fingerprint = verification_output_paths(
        repo, evidence_path, log_path
    )
    files = [
        rel for rel in files
        if rel not in verification_outputs_excluded_from_fingerprint
    ]
    fingerprint = worktree_fingerprint(repo, files)
    static_records, static_errors = static_checks(repo, contract, files)
    command_records, command_errors, excluded = execute_commands(
        repo, contract, args.profile, log_path
    )
    errors = static_errors + command_errors
    selected_count = sum(
        1 for command in contract["commands"] if args.profile in command["profiles"]
    )
    if len(command_records) != selected_count:
        errors.append("selected command evidence is incomplete")
    if any(record["status"] != "PASS" for record in command_records):
        errors.append("at least one selected command did not prove success")
    if any(not record.get("ok", False) for record in static_records):
        errors.append("at least one static check did not prove success")
    status = "PASS" if not errors else "FAIL"
    evidence = {
        "schema_version": SCHEMA_VERSION,
        "status": status,
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "repository_root": str(repo),
        "contract": str(contract_path),
        "contract_sha256": sha256_bytes(contract_text.encode("utf-8")),
        "profile": args.profile,
        "base": args.base,
        "head": args.head,
        "changed_files": files,
        "changed_file_count": len(files),
        "verification_outputs_excluded_from_fingerprint": verification_outputs_excluded_from_fingerprint,
        "worktree_fingerprint": fingerprint,
        "contract_name": contract.get("name", ""),
        "profile_limitations": contract.get("profile_limitations", {}).get(args.profile, []),
        "selected_command_count": selected_count,
        "minimum_command_count": contract["minimum_commands_by_profile"][args.profile],
        "executed_command_count": len(command_records),
        "excluded_commands": excluded,
        "static_checks": static_records,
        "commands": command_records,
        "errors": errors,
        "output_log": str(log_path),
    }
    write_evidence(evidence_path, evidence)
    print(f"[strict-verify] status={status} evidence={evidence_path} log={log_path}")
    if errors:
        for error in errors:
            print(f"[strict-verify] FAIL: {error}", file=sys.stderr)
        return 1
    return 0


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Strict honesty gate for Nova build changes"
    )
    parser.add_argument("--repo", required=True, help="git checkout root")
    parser.add_argument("--contract", required=True, help="explicit JSON contract")
    parser.add_argument("--base", required=True, help="explicit base git revision")
    parser.add_argument(
        "--head", required=True, help="explicit head git revision or WORKTREE"
    )
    parser.add_argument(
        "--profile", required=True, choices=["local", "ci"], help="explicit command profile"
    )
    parser.add_argument("--evidence", required=True, help="exact evidence JSON path")
    parser.add_argument("--log", required=True, help="exact full-output log path")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        return verify(args)
    except Exception as error:  # visibility, never suppression
        evidence_path = Path(args.evidence).resolve()
        emergency = {
            "schema_version": SCHEMA_VERSION,
            "status": "FAIL",
            "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "error": str(error),
            "error_type": type(error).__name__,
            "traceback": traceback.format_exc(),
            "profile": args.profile,
            "base": args.base,
            "head": args.head,
        }
        try:
            write_evidence(evidence_path, emergency)
        except OSError as write_error:
            print(f"could not write evidence: {write_error}", file=sys.stderr)
        print("strict-verify crashed; failing closed", file=sys.stderr)
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
