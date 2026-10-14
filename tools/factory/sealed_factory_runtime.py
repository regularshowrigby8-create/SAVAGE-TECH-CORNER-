#!/usr/bin/env python3
"""Runs the sealed factory set as the first stage group of the pipeline.

The sealed component holds 72 imported factories. This runtime makes every one of them *operate*
without any setup step from the operator:

* it opens the seal and verifies every payload hash before reading anything - a tampered component
  stops the pipeline instead of quietly feeding the factories;
* it detects the models that are actually reachable: local servers on their default ports
  (Ollama, LM Studio, vLLM) and any provider whose key is already in the environment (OpenAI,
  Anthropic, Google, Groq, Mistral, OpenRouter, Azure OpenAI, Bedrock). No credentials are read from
  files, printed, or stored, and nothing is installed or downloaded;
* every factory is then executed as far as it can genuinely be executed: its own vendored definition
  files are parsed into stages, worker roles, prompts and gates (always), and the composed prompt is
  sent to the best available model (when one is reachable). Where no model is reachable the factory
  still runs - it produces its plan and its prompt - and is reported as operating in plan mode.

Truthfulness rules, because this is a factory and not a demo:
* a factory that runs against a model records the provider, the model name and the response; a call
  that fails is reported as failed with the provider's own error text;
* no output is ever invented for a model that did not answer, and a missing or unreadable payload is
  an error, not a skip;
* the report names what was *not* done as clearly as what was.

Usage:
    python3 tools/factory/sealed_factory_runtime.py status
    python3 tools/factory/sealed_factory_runtime.py run [--brief FILE] [--max-model-calls N]
    python3 tools/factory/sealed_factory_runtime.py verify
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEALED = REPO_ROOT / "sealed-factories"
SEAL_FILE = SEALED / "SEAL.json"
RUNS = SEALED / "runs"

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seal_factories  # noqa: E402  (same directory, the component's own tooling)

#: The environment that lives inside the seal: tools, agent roles, language packs, MCP binding.
sys.path.insert(0, str(SEALED / "runtime"))

#: Local model servers, tried on their default ports. Probing is a sub-second TCP connect, so a
#: machine without any of them pays ~0.3 s once - and a machine that already runs one needs no setup.
LOCAL_ENDPOINTS = (
    ("ollama", "127.0.0.1", 11434, "http://127.0.0.1:11434/api/generate"),
    ("lmstudio", "127.0.0.1", 1234, "http://127.0.0.1:1234/v1/chat/completions"),
    ("vllm", "127.0.0.1", 8000, "http://127.0.0.1:8000/v1/chat/completions"),
)

#: Remote providers, used only when the key is already present in the environment.
REMOTE_PROVIDERS = (
    ("openai", "OPENAI_API_KEY", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
    ("groq", "GROQ_API_KEY", "https://api.groq.com/openai/v1/chat/completions",
     "llama-3.3-70b-versatile"),
    ("mistral", "MISTRAL_API_KEY", "https://api.mistral.ai/v1/chat/completions",
     "mistral-large-latest"),
    ("openrouter", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1/chat/completions",
     "openai/gpt-4o-mini"),
    ("anthropic", "ANTHROPIC_API_KEY", "https://api.anthropic.com/v1/messages",
     "claude-3-5-sonnet-latest"),
    ("google", "GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai/"
     "chat/completions", "gemini-2.0-flash"),
)

#: Three independent ways a factory's structure is read, because no single one works for all of
#: them: the paths it keeps its definitions under, the headings those definitions use, and the list
#: keys of its declarative files.
PATH_KINDS = (
    ("stages", re.compile(r"(^|/)(stages?|steps?|phases?|pipelines?|workflows?)/", re.I)),
    ("roles", re.compile(r"(^|/)(workers?|agents?|roles?|personas?|crews?|swarms?|teams?)/", re.I)),
    ("gates", re.compile(r"(^|/)(gates?|checks?|verifications?|reviews?|critics? "
                         r"criticisms?|audits?)/", re.I)),
    ("prompts", re.compile(r"(^|/)(prompts?|prompt_templates?|instructions?|skills?)/", re.I)),
)
STAGE_PATTERN = re.compile(r"^\s*(?:#{1,4}|[-*\d.]+)\s*(stage|phase|step|pipeline)\b[\d:.\-—]*"
                           r"\s*(.{0,80})$", re.I | re.M)
ROLE_PATTERN = re.compile(r"^\s*(?:#{1,4}|[-*\d.]+)\s*(worker|agent|role|persona|crew|swarm)\b"
                          r"[\d:.\-—]*\s*(.{0,80})$", re.I | re.M)
GATE_PATTERN = re.compile(r"^\s*(?:#{1,4}|[-*\d.]+)\s*(gate|check|verification|review|critic|"
                          r"acceptance)\b[\d:.\-—]*\s*(.{0,80})$", re.I | re.M)

#: Declarative keys that hold a factory's stages, workers or checks as a list.
LIST_KEYS = {
    "stages": ("stages", "steps", "phases", "pipeline", "pipelines", "workflow", "workflows",
               "jobs", "tasks", "sequence"),
    "roles": ("workers", "agents", "roles", "personas", "crew", "crews", "swarm", "swarms",
              "team", "teams", "members", "nodes", "specialists"),
    "gates": ("gates", "checks", "verifications", "reviews", "critics", "criteria", "assertions",
              "validations"),
    "prompts": ("prompts", "instructions", "templates"),
}

#: Container keys whose *children* are the stages - AWS buildspecs write `phases:` with `install:`
#: and `build:` beneath it, which is a stage list written as a mapping rather than a sequence.
CONTAINER_KEYS = ("phases", "stages", "steps", "jobs", "tasks", "pipeline")

#: A definition file's own headings name its stages: "## 1. Intake", "## Agent 3 - Verify".
HEADING_PATTERN = re.compile(r"^#{1,4}\s+(\S.{2,78})$", re.M)
DEFINITION_FILE = re.compile(r"(readme|agents?|workers?|stages?|pipeline|factory|skill|roles?|"
                             r"workflow|process)", re.I)


def sha256_file(path: Path) -> str:
    return seal_factories.sha256_file(path)


# --------------------------------------------------------------------------- capabilities


def port_open(host: str, port: int, timeout: float = 0.3) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def detect_capabilities() -> dict:
    interpreters = {}
    for name, command in (("python", [sys.executable, "--version"]),
                          ("node", ["node", "--version"])):
        try:
            done = subprocess.run(command, capture_output=True, text=True, timeout=10)
            interpreters[name] = done.stdout.strip() or done.stderr.strip() or "present"
        except (OSError, subprocess.SubprocessError):
            interpreters[name] = None

    local = []
    for name, host, port, url in LOCAL_ENDPOINTS:
        if port_open(host, port):
            local.append({"name": name, "url": url})

    keyed = []
    for name, variable, url, model in REMOTE_PROVIDERS:
        value = os.environ.get(variable)
        if value:
            # Only that the key exists is recorded - never the key, not even its length.
            keyed.append({"name": name, "env": variable, "url": url, "model": model})

    return {
        "interpreters": interpreters,
        "local_models": local,
        "keyed_providers": keyed,
        "model_available": bool(local or keyed),
        "detected_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }


# --------------------------------------------------------------------------- seal


def open_seal() -> dict:
    if not SEAL_FILE.is_file():
        raise FileNotFoundError("the sealed component is not present (sealed-factories/SEAL.json)")
    status = seal_factories.verify(quiet=True)
    if status != 0:
        raise RuntimeError("the sealed component failed verification; refusing to run it")
    return json.loads(SEAL_FILE.read_text(encoding="utf-8"))


def read_factory(document: dict, factory: dict) -> dict:
    """Reads one sealed payload and derives what the factory is made of."""
    payload = SEALED / factory["payload"]
    derived = {"id": factory["id"], "full_name": factory["full_name"], "url": factory["url"],
               "group": factory.get("group", "imported factory"),
               "license": factory["license"], "content_redistributed":
               factory["content_redistributed"], "files": [], "stages": [], "roles": [],
               "gates": [], "prompts": [], "headings": [], "mcp_tools": [], "texts": 0}
    if derived["group"] == "MCP tool server":
        # An MCP server's structure is the tool list it publishes. Where a real handshake observed
        # that list, it is what the server defines; where none was run, the list stays empty and the
        # record is reported as having no definitions rather than assumed to have them.
        record_path = SEALED / "mcp-vendor" / f'{factory["id"]}.json'
        if record_path.is_file():
            try:
                record = json.loads(record_path.read_text(encoding="utf-8"))
                launch = record.get("launch") or {}
                derived["mcp_tools"] = [f"{server.get('name')}:{tool}"
                                        for server in (launch.get("verified") or [])
                                        for tool in server.get("tools", [])]
                derived["launch_command"] = " ".join(launch.get("command") or [])
            except (OSError, ValueError):
                derived["mcp_tools"] = []
    with tarfile.open(payload, "r:gz") as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            if member.name == "FACTORY.json":
                continue
            derived["files"].append(member.name)
            if member.size > 80_000:
                continue
            handle = archive.extractfile(member)
            if handle is None:
                continue
            text = handle.read().decode("utf-8", "replace")
            derived["texts"] += 1

            # 1. What the file is, by where the factory keeps it.
            for kind, pattern in PATH_KINDS:
                if pattern.search(member.name):
                    derived[kind].append(("path", member.name))

            # 2. What it declares, from the list keys of JSON and YAML files.
            if member.name.lower().endswith(".json"):
                try:
                    loaded = json.loads(text)
                except ValueError:
                    loaded = None
                if isinstance(loaded, dict):
                    for kind, keys in LIST_KEYS.items():
                        for key in keys:
                            value = loaded.get(key)
                            if isinstance(value, list):
                                for item in value[:24]:
                                    label = item if isinstance(item, str) else (
                                        item.get("name") or item.get("id") or
                                        item.get("role") or item.get("title") or "")
                                    if label:
                                        derived[kind].append((f"json:{key}", str(label)[:80]))
            elif member.name.lower().endswith((".yaml", ".yml")) and len(text) < 40_000:
                current = None
                container = None
                container_indent = 0
                for line in text.splitlines():
                    if not line.strip() or line.lstrip().startswith("#"):
                        continue
                    indent = len(line) - len(line.lstrip())
                    key = re.match(r"^([\w.-]+):\s*(.*)$", line.strip())
                    stripped = line.strip()
                    if key and indent == 0:
                        current = key.group(1).strip().lower()
                        container = current if current in CONTAINER_KEYS else None
                        container_indent = 0
                    elif key and container and indent > container_indent:
                        # A named child of a stage container is a stage: `install:`, `build:`.
                        label = key.group(1).strip()
                        if len(label) > 2:
                            derived["stages"].append((f"yaml:{container}", label[:80]))
                        current = label.lower()
                    elif stripped.startswith("- ") and current:
                        for kind, keys in LIST_KEYS.items():
                            if current in keys:
                                derived[kind].append((f"yaml:{current}", stripped[2:].strip()[:80]))

            # 4. A definition file's own headings, for slices that carry prose rather than config.
            if DEFINITION_FILE.search(member.name):
                for match in HEADING_PATTERN.finditer(text):
                    label = match.group(1).strip()
                    if not STAGE_PATTERN.match(match.group(0)) and not ROLE_PATTERN.match(
                            match.group(0)) and not GATE_PATTERN.match(match.group(0)):
                        derived["headings"].append(("heading", label))

            # 3. What the text itself calls a stage, worker or check.
            derived["stages"] += [(m.group(1).lower(), m.group(2).strip()[:80])
                                  for m in STAGE_PATTERN.finditer(text)]
            derived["roles"] += [(m.group(1).lower(), m.group(2).strip()[:80])
                                 for m in ROLE_PATTERN.finditer(text)]
            derived["gates"] += [(m.group(1).lower(), m.group(2).strip()[:80])
                                 for m in GATE_PATTERN.finditer(text)]
    derived["totals"] = {}
    derived["totals"]["mcp_tools"] = len(derived["mcp_tools"])
    for kind in ("stages", "roles", "gates", "prompts", "headings"):
        # De-duplicate while keeping the order the factory itself uses.
        seen = set()
        unique = []
        for source, label in derived[kind]:
            marker = label.strip().lower()
            if marker and marker not in seen:
                seen.add(marker)
                unique.append((source, label))
        # The count the factory really defines, then the list this report carries (kept small).
        derived["totals"][kind] = len(unique)
        derived[kind] = unique[:24]
    derived["structure_found"] = bool(derived["mcp_tools"]) or any(
        derived[kind] for kind in ("stages", "roles", "gates", "prompts", "headings"))
    return derived


def compose_prompt(derived: dict, factory: dict, brief: str) -> str:
    """The brief the factory's stage is asked to work on, built from its own definition files."""
    lines = [
        f"# Factory stage: {derived['id']}",
        "",
        f"Source factory: {factory['full_name']} ({factory['url']}), licence {factory['license']},",
        f"commit {factory.get('commit') or 'unknown'}.",
        "",
        "You are running the stage this factory defines, inside a multi-factory pipeline. Work only "
        "from the brief and the factory's own definitions below; state plainly what you cannot do.",
        "",
        "## Brief",
        "",
        brief.strip(),
        "",
    ]
    if derived["stages"]:
        lines += ["## Stages this factory defines", ""]
        lines += [f"- {name}" for _source, name in derived["stages"]]
        lines += [""]
    if derived["roles"]:
        lines += ["## Workers this factory defines", ""]
        lines += [f"- {name}" for _source, name in derived["roles"]]
        lines += [""]
    if derived["gates"]:
        lines += ["## Checks this factory defines", ""]
        lines += [f"- {name}" for _source, name in derived["gates"]]
        lines += [""]
    if derived["prompts"]:
        lines += ["## Prompt assets this factory ships", ""]
        lines += [f"- {name}" for _source, name in derived["prompts"]]
        lines += [""]
    if derived["headings"]:
        lines += ["## What its own definition files describe", ""]
        lines += [f"- {name}" for _source, name in derived["headings"]]
        lines += [""]
    lines += [
        "## Deliverable",
        "",
        "Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it",
        "produces, (3) the checks that would fail the work, (4) anything in the brief you cannot",
        "determine from it. Do not claim results you did not compute.",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------- models


def call_model(capabilities: dict, prompt: str, timeout: int = 120) -> dict:
    """Sends the prompt to the best available model. Returns a result dict, never raises."""
    if capabilities["local_models"]:
        local = capabilities["local_models"][0]
        if local["name"] == "ollama":
            body = {"model": os.environ.get("OLLAMA_MODEL", "llama3.1"),
                    "prompt": prompt, "stream": False}
        else:
            body = {"model": os.environ.get("LOCAL_MODEL", "local-model"),
                    "messages": [{"role": "user", "content": prompt}]}
        return _post(local["name"], local["url"], body, {}, timeout)

    if capabilities["keyed_providers"]:
        provider = capabilities["keyed_providers"][0]
        key = os.environ.get(provider["env"], "")
        if provider["name"] == "anthropic":
            body = {"model": os.environ.get("ANTHROPIC_MODEL", provider["model"]),
                    "max_tokens": 2048,
                    "messages": [{"role": "user", "content": prompt}]}
            headers = {"x-api-key": key, "anthropic-version": "2023-06-01"}
        else:
            body = {"model": os.environ.get(f'{provider["name"].upper()}_MODEL', provider["model"]),
                    "messages": [{"role": "user", "content": prompt}]}
            headers = {"Authorization": f"Bearer {key}"}
        return _post(provider["name"], provider["url"], body, headers, timeout)

    return {"ok": False, "provider": None, "error": "no model is reachable (no local server on its "
                                                    "default port and no provider key in the "
                                                    "environment)"}


def _post(provider: str, url: str, body: dict, headers: dict, timeout: int) -> dict:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:400]
        return {"ok": False, "provider": provider, "error": f"HTTP {error.code}: {detail}"}
    except (urllib.error.URLError, OSError, ValueError) as error:
        return {"ok": False, "provider": provider, "error": f"{type(error).__name__}: {error}"}

    text = _extract_text(payload)
    if not text:
        return {"ok": False, "provider": provider,
                "error": "the provider answered without any text content"}
    return {"ok": True, "provider": provider, "model": body.get("model"), "text": text}


def _extract_text(payload: dict) -> str:
    if isinstance(payload.get("response"), str):
        return payload["response"]
    if isinstance(payload.get("content"), list):
        parts = [part.get("text", "") for part in payload["content"] if isinstance(part, dict)]
        if any(parts):
            return "\n".join(parts)
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0]
        if isinstance(first, dict):
            message = first.get("message") or {}
            if isinstance(message, dict) and isinstance(message.get("content"), str):
                return message["content"]
            if isinstance(first.get("text"), str):
                return first["text"]
    return ""


# --------------------------------------------------------------------------- run


def run(brief: str, max_model_calls: int, require_model: bool, env_agents: int = 1,
        env_limit: int = 200, env: bool = True, env_workspace: str = "",
        env_with_mcp: bool = False) -> int:
    try:
        document = open_seal()
    except (FileNotFoundError, RuntimeError) as problem:
        # Absent is a legitimate state (the component is removable); tampered is not.
        if isinstance(problem, FileNotFoundError):
            # Removability: absent means absent. No report, no directory, no side effect - the
            # component's whole footprint is the one directory that was deleted.
            print(f"[SEALED] ABSENT: {problem}. The pipeline continues with the repository's own "
                  f"factories.")
            return 0
        print(f"[SEALED] FAIL: {problem}", file=sys.stderr)
        return 1

    capabilities = detect_capabilities()
    print(f"[SEALED] {document['contents']['factories']} factories sealed "
          f"({document['contents']['content_redistributed']} with redistributed content, "
          f"{document['contents']['blueprint_only']} blueprint-only)")
    print(f"[SEALED] models: local={[m['name'] for m in capabilities['local_models']]} "
          f"keyed={[p['name'] for p in capabilities['keyed_providers']]}")

    run_dir = RUNS / time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    run_dir.mkdir(parents=True, exist_ok=True)

    results = []
    model_calls = 0
    for factory in document["factories"]:
        entry = {"id": factory["id"], "full_name": factory["full_name"], "license": factory["license"],
                 "content_redistributed": factory["content_redistributed"]}
        try:
            derived = read_factory(document, factory)
        except (tarfile.TarError, OSError, ValueError) as problem:
            entry.update({"status": "UNREADABLE", "error": f"{type(problem).__name__}: {problem}"})
            results.append(entry)
            continue

        target = run_dir / factory["id"]
        target.mkdir(parents=True, exist_ok=True)
        (target / "plan.json").write_text(json.dumps(derived, indent=1), encoding="utf-8")
        prompt = compose_prompt(derived, factory, brief)
        (target / "prompt.md").write_text(prompt, encoding="utf-8")

        entry.update({
            "stages": derived["totals"]["stages"],
            "headings": derived["totals"]["headings"],
            "roles": derived["totals"]["roles"],
            "gates": derived["totals"]["gates"],
            "prompts": derived["totals"]["prompts"],
            "mcp_tools": derived["totals"]["mcp_tools"],
            "group": derived["group"],
            "files": len(derived["files"]),
            "artifacts": [str((target / "plan.json").relative_to(REPO_ROOT)),
                          str((target / "prompt.md").relative_to(REPO_ROOT))],
        })

        if not derived["structure_found"]:
            # The slice holds no definitions (see below): there is nothing for a model to run, so a
            # call would buy a generic answer and a misleading status.
            entry["status"] = "NO-STRUCTURE"
            entry["note"] = ("the sealed slice of this factory holds no stage, worker or prompt "
                             "definitions to run" +
                             ("" if factory["content_redistributed"] else
                              "; its content is not redistributed (blueprint only)"))
        elif capabilities["model_available"] and model_calls < max_model_calls:
            model_calls += 1
            started = time.time()
            answer = call_model(capabilities, prompt)
            entry["model_seconds"] = round(time.time() - started, 1)
            if answer["ok"]:
                (target / "model-output.md").write_text(answer["text"], encoding="utf-8")
                entry.update({"status": "OPERATIONAL-MODEL", "provider": answer["provider"],
                              "model": answer.get("model")})
                entry["artifacts"].append(
                    str((target / "model-output.md").relative_to(REPO_ROOT)))
            else:
                entry.update({"status": "MODEL-FAILED", "provider": answer["provider"],
                              "error": answer["error"]})
        elif capabilities["model_available"]:
            entry["status"] = "OPERATIONAL-PLAN"
            entry["note"] = f"model calls capped at {max_model_calls}"
        else:
            entry["status"] = "OPERATIONAL-PLAN"
            entry["note"] = ("no model reachable, so the stage ran from the factory's own definition "
                             "files; nothing was invented for a model that did not answer")
        results.append(entry)

    summary = {
        "operational_model": sum(1 for r in results if r["status"] == "OPERATIONAL-MODEL"),
        "operational_plan": sum(1 for r in results if r["status"] == "OPERATIONAL-PLAN"),
        "no_structure": sum(1 for r in results if r["status"] == "NO-STRUCTURE"),
        "model_failed": sum(1 for r in results if r["status"] == "MODEL-FAILED"),
        "unreadable": sum(1 for r in results if r["status"] == "UNREADABLE"),
        "stages_defined": sum(r.get("stages", 0) for r in results),
        "sections_defined": sum(r.get("headings", 0) for r in results),
        "workers_defined": sum(r.get("roles", 0) for r in results),
        "checks_defined": sum(r.get("gates", 0) for r in results),
        "prompts_defined": sum(r.get("prompts", 0) for r in results),
        "mcp_tools_verified": sum(r.get("mcp_tools", 0) for r in results),
        "carried_without_definitions": sum(1 for r in results
                                            if r["status"] == "NO-STRUCTURE"
                                            and r.get("content_redistributed")),
        "by_group": {group: sum(1 for r in results if r.get("group") == group)
                     for group in sorted({r.get("group", "") for r in results})},
    }
    stage_groups = [
        {"group": "sealed.open", "what": f'{document["contents"]["factories"]} imported factories, '
                                        f'seal verified by hash'},
        {"group": "sealed.plan", "what": f'{summary["operational_plan"]} factories ran from their own '
                                        f'definitions ({summary["stages_defined"]} stages, '
                                        f'{summary["workers_defined"]} workers) and handed their '
                                        f'plans to the belt'},
        {"group": "sealed.model", "what": f'{summary["operational_model"]} factories ran against a '
                                         f'reachable model ({model_calls} call(s)); no model was '
                                         f'reachable for the rest' if not capabilities["model_available"]
                                          else f'{model_calls} model call(s) made'},
    ]
    environment_report = None
    if env:
        try:
            environment_report = environment(agents=env_agents, limit=env_limit,
                                              workspace=env_workspace, with_mcp=env_with_mcp)
        except Exception as problem:  # the environment is part of the component; a bug is a failure
            print(f"[SEALED] FAIL: the sealed environment could not run: "
                  f"{type(problem).__name__}: {problem}", file=sys.stderr)
            return 1

    report = {
        "status": "RUN",
        "environment": environment_report,
        "sealed_at": document["sealed_at"],
        "seal_version": document["seal_version"],
        "run_dir": str(run_dir.relative_to(REPO_ROOT)),
        "capabilities": capabilities,
        "model_calls": model_calls,
        "stage_groups": stage_groups,
        "summary": summary,
        "factories": results,
        "order": {"position": 0,
                  "runs_before": document["pipeline_position"]["runs_before"],
                  "feeds_into": document["pipeline_position"]["feeds_into"]},
    }
    write_report(report)
    print(f"[SEALED] {summary['operational_model']} factories ran against a model, "
          f"{summary['operational_plan']} ran in plan mode, "
          f"{summary['no_structure']} had no definitions in their sealed slice, "
          f"{summary['model_failed']} model calls failed, {summary['unreadable']} unreadable")
    if summary["carried_without_definitions"]:
        print(f"[SEALED] {summary['carried_without_definitions']} slice(s) carry content but define "
              f"nothing this runtime reads as stages, workers or tools")
    if summary["mcp_tools_verified"]:
        print(f"[SEALED] MCP servers: {summary['by_group'].get('MCP tool server', 0)} catalogued, "
              f"{summary['mcp_tools_verified']} tools observed by handshake")
    print(f"[SEALED] definitions read from the sealed set: {summary['stages_defined']} stages, "
          f"{summary['workers_defined']} workers, {summary['checks_defined']} checks, "
          f"{summary['prompts_defined']} prompt assets, {summary['sections_defined']} sections of "
          f"prose their definition files publish")
    print(f"[SEALED] report: "
          f"{(SEALED / 'manifest' / 'RUN-REPORT.json').relative_to(REPO_ROOT)}")

    failed = summary["model_failed"] + summary["unreadable"]
    if summary["unreadable"]:
        print(f"[SEALED] FAIL: {summary['unreadable']} sealed payloads could not be read", file=sys.stderr)
        return 1
    if require_model and not summary["operational_model"]:
        print("[SEALED] FAIL: --require-model was given but no factory reached a model", file=sys.stderr)
        return 1
    _ = failed
    return 0


SAMPLE_MODULE = ("\"\"\"A file for the agent swarm to read and measure.\"\"\"\n\n\ndef add(left: int, right: int) -> int:\n"
                "    \"\"\"Add two numbers.\"\"\"\n    return left + right\n")


def environment(agents: int = 1, limit: int = 200, gateway_url: str = "", with_mcp: bool = False,
                workspace: str = "") -> dict:
    """Runs the sealed environment itself: its tools, its agent roles and a real swarm pass.

    This is the part of stage 0 that does not depend on the imported factories at all - it is the
    component's own force, and it is what makes the seal portable to any repository.
    """
    import agents as agent_catalogue
    import langkit
    import mcplink
    import swarm
    from toolkit import Workspace, build_registry

    # The swarm's default workspace is the component's own scratch tree: it is real (the tools read
    # and write actual files), it is fast, and pointing it at a whole repository is an explicit
    # choice (`--env-workspace`) because a full sweep of a big tree costs minutes, not seconds.
    root = Path(workspace) if workspace else SEALED / "manifest" / "workspace"
    root.mkdir(parents=True, exist_ok=True)
    starter = root / "README.md"
    if not starter.exists():
        starter.write_text("# Sealed environment workspace\n\nThe agent swarm works here when no "
                           "other workspace is given.\n", encoding="utf-8")
    (root / "src").mkdir(exist_ok=True)
    sample = root / "src" / "sample.py"
    if not sample.exists():
        sample.write_text(SAMPLE_MODULE, encoding="utf-8")
    registry = build_registry(Workspace(root), gateway_url)
    native_tools = len(registry)
    catalogue = mcplink.load_catalogue()
    states = [mcplink.availability(entry) for entry in catalogue]
    link = {"tools": {}, "skipped": []}
    if with_mcp:
        link = mcplink.connect(cwd=str(root))
        registry.update(link["tools"])

    languages = langkit.detect()
    roles = agent_catalogue.catalogue(swarm._imported_records(), languages["available"])
    run_report = swarm.run(str(root), "Stage 0: take this repository's product one stage further.",
                           instances=agents, workers=16, execute_limit=limit,
                           model_calls=4 if gateway_url else 0, gateway_url=gateway_url)

    summary = {
        "tools": {"native": native_tools, "mcp_bound": len(link["tools"]),
                  "total": len(registry), "names": sorted(registry)[:80]},
        "mcp": {"catalogue": len(catalogue),
                "verified_by_handshake": sum(1 for entry in catalogue if entry.get("verified")),
                "bindable_here": sum(1 for state in states if state["available"]),
                "bound": sorted({name.split(".")[1] for name in link["tools"]}),
                "not_bindable": [{"name": state["name"], "why": state["why_not"]}
                                 for state in states if not state["available"]][:12]},
        "agents": {"roles": len(roles),
                   "by_source": {source: sum(1 for r in roles if r.source == source)
                                 for source in sorted({r.source for r in roles})},
                   "topologies": sorted({r.topology for r in roles}),
                   "capacity": swarm.MAX_AGENTS},
        "languages": {"available": languages["available"], "missing": languages["missing"]},
        "swarm": {"plan": run_report["plan"], "counts": run_report["counts"],
                  "seconds": run_report["seconds"], "run_dir": run_report["run_dir"],
                  "per_role": run_report["per_role"]},
    }
    print(f"[SEALED] environment: {summary['tools']['total']} tools "
          f"({summary['tools']['native']} native, {len(link['tools'])} bound from MCP), "
          f"MCP catalogue {summary['mcp']['catalogue']} servers "
          f"({summary['mcp']['verified_by_handshake']} verified here, "
          f"{summary['mcp']['bindable_here']} bindable on this machine), "
          f"{summary['agents']['roles']} agent roles, "
          f"{len(languages['available'])} language packs available "
          f"({', '.join(languages['missing'][:6])} missing)")
    print(f"[SEALED] swarm: {run_report['plan']['executed']} agent instances of a "
          f"{run_report['plan']['instances']}-instance plan in {run_report['seconds']}s, "
          f"{run_report['counts']['steps_ok']} tool steps, "
          f"{run_report['counts']['checks_passed']}/"
          f"{run_report['counts']['checks_passed'] + run_report['counts']['checks_failed']} checks "
          f"passed")
    target = SEALED / "manifest"
    target.mkdir(parents=True, exist_ok=True)
    (target / "ENVIRONMENT.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return summary


def write_report(report: dict) -> None:
    target = SEALED / "manifest"
    target.mkdir(parents=True, exist_ok=True)
    (target / "RUN-REPORT.json").write_text(json.dumps(report, indent=1), encoding="utf-8")


def status() -> int:
    capabilities = detect_capabilities()
    print(json.dumps({"capabilities": capabilities}, indent=1))
    try:
        document = open_seal()
    except (FileNotFoundError, RuntimeError) as problem:
        print(f"[SEALED] component not runnable: {problem}")
        return 0
    print(f"[SEALED] {document['contents']['factories']} factories, "
          f"{document['contents']['ai_worker_factories']} with AI workers, "
          f"{document['contents']['payload_bytes']} payload bytes")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the sealed imported factory set")
    parser.add_argument("action", nargs="?", default="run", choices=("run", "status", "verify"))
    parser.add_argument("--brief", default="", help="what the stage group should work on")
    parser.add_argument("--brief-file", default="", help="read the brief from a file")
    parser.add_argument("--max-model-calls", type=int, default=4,
                        help="cap on model calls per run (cost control); 0 disables model calls")
    parser.add_argument("--require-model", action="store_true",
                        help="fail when no factory reached a model")
    parser.add_argument("--env-agents", type=int, default=1,
                        help="agent instances per role in the sealed environment")
    parser.add_argument("--env-limit", type=int, default=200,
                        help="how many agent instances the environment executes")
    parser.add_argument("--no-env", action="store_true",
                        help="run the imported factories only, skipping the sealed environment")
    parser.add_argument("--with-mcp", action="store_true",
                        help="also bind every MCP server this machine can start into the tool set")
    parser.add_argument("--env-workspace", default="",
                        help="directory the agent swarm works in (default: the component's own "
                             "scratch workspace; point it at a repository for a full sweep)")
    args = parser.parse_args()

    if args.action == "verify":
        return seal_factories.verify()
    if args.action == "status":
        return status()

    brief = args.brief
    if args.brief_file:
        brief = Path(args.brief_file).read_text(encoding="utf-8")
    if not brief:
        brief = ("Take this repository's product (the Savage Browser Android app and its factories) "
                 "one stage further: name the stage you would add, the artefacts it produces, and the "
                 "checks that would fail it.")
    return run(brief, args.max_model_calls, args.require_model, args.env_agents, args.env_limit,
               not args.no_env, args.env_workspace, args.with_mcp)


if __name__ == "__main__":
    sys.exit(main())
