#!/usr/bin/env python3
"""The sealed factory's control surface: one command for everything the component does.

    python3 sealed-factories/runtime/cli.py capabilities     # what this machine can actually do
    python3 sealed-factories/runtime/cli.py tools            # every tool, with its schema
    python3 sealed-factories/runtime/cli.py mcp              # serve the tools over MCP (stdio)
    python3 sealed-factories/runtime/cli.py gateway          # the unified model + key gateway
    python3 sealed-factories/runtime/cli.py mcp-probe        # installed / missing MCP servers
    python3 sealed-factories/runtime/cli.py init --language python --name thing --into out/
    python3 sealed-factories/runtime/cli.py run --task "..." --agents 3 --limit 60
    python3 sealed-factories/runtime/cli.py capacity         # how many agents this component holds
    python3 sealed-factories/runtime/cli.py verify           # recompute every sealed payload hash
    python3 sealed-factories/runtime/cli.py selfcheck        # recheck the set with the factories it carries

Everything defaults to the current directory as the workspace, and to the gateway this component can
start for itself. Nothing else has to be installed, configured or running for the offline paths.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import langkit  # noqa: E402
import mcplink  # noqa: E402
import swarm  # noqa: E402
import sealcheck  # noqa: E402
from toolkit import ToolError, Workspace, build_registry, tool_names  # noqa: E402

SEALED = HERE.parent
REPO_ROOT = SEALED.parent


def _gateway_url(explicit: str) -> str:
    if explicit:
        return explicit
    import os
    return os.environ.get("SEALED_GATEWAY_URL", "")


def _registry(args) -> tuple[dict, Workspace]:
    workspace = Workspace(args.root)
    registry = build_registry(workspace, _gateway_url(getattr(args, "gateway", "")))
    if getattr(args, "with_mcp", False):
        link = mcplink.connect(cwd=str(workspace.root))
        registry.update(link["tools"])
        if link["skipped"]:
            print(f"[mcp] {len(link['skipped'])} catalogued server(s) not bound; "
                  f"`cli.py mcp-probe` says why", file=sys.stderr)
    return registry, workspace


def cmd_capabilities(args) -> int:
    languages = langkit.detect()
    registry, _workspace = _registry(args)
    report = {
        "component": {"path": str(SEALED), "seal": _seal_summary()},
        "tools": {"count": len(registry), "native": len([n for n in registry if not
                                                         n.startswith("mcp.")])},
        "languages": languages,
        "agents": swarm.capacity_report(),
        "interpreters": {"python": sys.version.split()[0]},
        "workspace": str(Path(args.root).resolve()),
    }
    print(json.dumps(report, indent=1))
    return 0


def cmd_tools(args) -> int:
    registry, _workspace = _registry(args)
    if args.json:
        print(json.dumps({name: {"description": registry[name]["description"],
                                 "schema": registry[name]["schema"]}
                          for name in tool_names(registry)}, indent=1))
        return 0
    for name in tool_names(registry):
        print(f"{name:<24} {registry[name]['description']}")
    print(f"\n{len(registry)} tools")
    return 0


def cmd_call(args) -> int:
    registry, _workspace = _registry(args)
    try:
        arguments = json.loads(args.arguments)
    except ValueError as problem:
        print(f"the arguments are not valid JSON: {problem}", file=sys.stderr)
        return 2
    if args.tool not in registry:
        print(f"no tool named {args.tool}; run `cli.py tools`", file=sys.stderr)
        return 2
    try:
        print(json.dumps(registry[args.tool]["fn"](**arguments), indent=1, default=str))
        return 0
    except ToolError as problem:
        print(f"the tool refused: {problem}", file=sys.stderr)
        return 1


def cmd_mcp(args) -> int:
    import toolserver
    return toolserver.serve(args.root, _gateway_url(args.gateway))


def cmd_gateway(args) -> int:
    import gateway
    sys.argv = ["gateway", "--port", str(args.port), "--host", args.host]
    if args.token:
        sys.argv += ["--token", args.token]
    if args.once:
        sys.argv += ["--once"]
    return gateway.main()


def cmd_mcp_probe(args) -> int:
    print(json.dumps(mcplink.probe(), indent=1)[:20_000])
    return 0


def cmd_init(args) -> int:
    files = langkit.scaffold(args.language, args.name)
    target = Path(args.into).resolve()
    written = []
    for relative, content in files.items():
        if args.language == "python" and relative.startswith("tests/"):
            pass  # tests are a normal directory in the scaffold
        destination = target / relative
        if destination.exists() and not args.force:
            print(f"refusing to overwrite {destination} (use --force)", file=sys.stderr)
            return 1
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")
        written.append(str(destination.relative_to(target)))
    language = langkit.by_name(args.language)
    report = {"language": args.language, "into": str(target), "files": written,
              "build": language.build_command, "test": language.test_command,
              "run": language.run_command.replace("{entry}", next(iter(language.files), ""))}
    if args.check:
        workspace = Workspace(target)
        registry = build_registry(workspace, "")
        results = []
        for label, command in (("build", language.build_command), ("test", language.test_command)):
            if not command:
                continue
            try:
                outcome = registry["proc.run"]["fn"](command=command, timeout=600,
                                                     allow_failure=True)
                results.append({"step": label, "command": command,
                                "exit_code": outcome["exit_code"], "stdout": outcome["stdout"][-800:],
                                "stderr": outcome["stderr"][-800:]})
            except ToolError as problem:
                results.append({"step": label, "command": command, "exit_code": None,
                                "error": str(problem)})
        report["checks"] = results
        report["verified"] = all(result.get("exit_code") == 0 for result in results) and bool(results)
    print(json.dumps(report, indent=1))
    return 0


def cmd_run(args) -> int:
    roles = tuple(r for r in (args.roles or "").split(",") if r)
    report = swarm.run(args.root, args.task, instances=args.agents, workers=args.workers,
                       execute_limit=args.limit, model_calls=args.model_calls,
                       gateway_url=_gateway_url(args.gateway), roles_filter=roles)
    counts = report["counts"]
    print(f"[swarm] {report['roles']} roles, plan of {report['plan']['instances']} agent "
          f"instances, executed {report['plan']['executed']} in {report['seconds']}s")
    print(f"[swarm] {counts['model']} ran with a model, {counts['offline']} offline, "
          f"{counts['steps_ok']} tool steps ok, {counts['steps_failed']} failed, "
          f"{counts['checks_passed']}/{counts['checks_passed'] + counts['checks_failed']} checks "
          f"passed")
    print(f"[swarm] report: {report['run_dir']}/RUN.json")
    return 0 if counts["checks_failed"] == 0 else 1


def cmd_capacity(args) -> int:
    print(json.dumps(swarm.capacity_report(), indent=1))
    return 0


def cmd_verify(args) -> int:
    # The component's own verifier: no repository tooling, so a copy of sealed-factories/ dropped
    # into another repository can prove its own integrity. --repo-verifier runs the sealer instead.
    if getattr(args, "repo_verifier", False):
        sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))
        import seal_factories
        return seal_factories.verify()
    return sealcheck.verify()


def cmd_selfcheck(args) -> int:
    # The component rechecking itself with the factories it carries: integrity is `verify`, fitness is
    # this - and it repairs what it can, with its own tools, before deciding.
    import selfcheck  # noqa: PLC0415 - only this command needs it

    argv: list[str] = []
    if getattr(args, "repair", False):
        argv.append("--repair")
    if getattr(args, "require_functional", False):
        argv.append("--require-functional")
    if getattr(args, "json", False):
        argv.append("--json")
    if getattr(args, "workspace", ""):
        argv += ["--workspace", args.workspace]
    return selfcheck.main(argv)


def _seal_summary() -> dict:
    seal_file = SEALED / "SEAL.json"
    if not seal_file.is_file():
        return {"present": False}
    document = json.loads(seal_file.read_text(encoding="utf-8"))
    return {"present": True, "version": document.get("seal_version"),
            "factories": document.get("contents", {}).get("factories"),
            "payload_bytes": document.get("contents", {}).get("payload_bytes"),
            "sealed_at": document.get("sealed_at")}


#: Options that belong to every command. argparse only accepts them before the subcommand, which
#: means `cli.py --root x run` works and `cli.py run --root x` does not. Both are accepted here: a
#: component meant to be driven without reading its source should not care about argument order.
GLOBAL_OPTIONS = ("--root", "--gateway", "--with-mcp")


def _normalise_argv(argv: list[str]) -> list[str]:
    """Moves every global option, and its value, in front of the subcommand."""
    moved: list[str] = []
    rest: list[str] = []
    index = 0
    while index < len(argv):
        token = argv[index]
        if token in GLOBAL_OPTIONS:
            moved.append(token)
            if token != "--with-mcp" and index + 1 < len(argv) and not argv[index + 1].startswith("-"):
                moved.append(argv[index + 1])
                index += 2
                continue
        elif any(token.startswith(option + "=") for option in GLOBAL_OPTIONS):
            moved.append(token)
        else:
            rest.append(token)
        index += 1
    return moved + rest


def main() -> int:
    parser = argparse.ArgumentParser(description="Sealed factory runtime control surface")
    parser.add_argument("--root", default=".", help="workspace root (default: current directory)")
    parser.add_argument("--gateway", default="", help="unified gateway URL for model calls")
    parser.add_argument("--with-mcp", action="store_true",
                        help="also bind installed MCP servers into the tool registry")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("capabilities", help="what this machine can do")

    tools = subparsers.add_parser("tools", help="list tools")
    tools.add_argument("--json", action="store_true")

    call = subparsers.add_parser("call", help="call one tool")
    call.add_argument("tool")
    call.add_argument("--arguments", default="{}")

    subparsers.add_parser("mcp", help="serve the tools over MCP on stdio")
    subparsers.add_parser("mcp-probe", help="which catalogued MCP servers are installed")

    gateway = subparsers.add_parser("gateway", help="run the unified model and key gateway")
    gateway.add_argument("--port", type=int, default=8787)
    gateway.add_argument("--host", default="0.0.0.0")
    gateway.add_argument("--token", default="")
    gateway.add_argument("--once", action="store_true")

    init = subparsers.add_parser("init", help="scaffold a runnable project in any supported language")
    init.add_argument("--language", required=True)
    init.add_argument("--name", default="project")
    init.add_argument("--into", default=".")
    init.add_argument("--force", action="store_true")
    init.add_argument("--check", action="store_true", help="build and test the scaffold right away")

    run = subparsers.add_parser("run", help="run the agent swarm")
    run.add_argument("--task", default="Take this workspace one stage further.")
    run.add_argument("--agents", type=int, default=1, help="instances per role")
    run.add_argument("--workers", type=int, default=16)
    run.add_argument("--limit", type=int, default=swarm.DEFAULT_EXECUTE_LIMIT,
                     help="how many agent instances to execute")
    run.add_argument("--model-calls", type=int, default=swarm.DEFAULT_MODEL_CALLS)
    run.add_argument("--roles", default="", help="comma-separated role ids to run")

    subparsers.add_parser("capacity", help="how many agents this component holds")
    verify = subparsers.add_parser("verify", help="recompute every sealed payload hash")
    verify.add_argument("--repo-verifier", action="store_true",
                        help="use the repository's sealer (tools/factory/seal_factories.py) instead")

    selfcheck = subparsers.add_parser("selfcheck",
                                      help="recheck the set with the factories it carries, and repair")
    selfcheck.add_argument("--repair", action="store_true",
                           help="repair the findings that name a repair, then survey again")
    selfcheck.add_argument("--require-functional", action="store_true", dest="require_functional",
                           help="exit 1 unless nothing blocking remains")
    selfcheck.add_argument("--json", action="store_true")
    selfcheck.add_argument("--workspace", default="",
                           help="where to write self-check.json (default: the component's manifest "
                                "directory)")

    args = parser.parse_args(_normalise_argv(sys.argv[1:]))
    handlers = {"capabilities": cmd_capabilities, "tools": cmd_tools, "call": cmd_call,
                "mcp": cmd_mcp, "gateway": cmd_gateway, "mcp-probe": cmd_mcp_probe,
                "init": cmd_init, "run": cmd_run, "capacity": cmd_capacity, "verify": cmd_verify,
                "selfcheck": cmd_selfcheck}
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
