"""The agent catalogue: who works in the sealed factory, what each one may touch, and what it does
when no model is reachable.

Three sources, merged into one catalogue:

* **core roles** - the jobs a software factory needs whatever it is building: intake, architecture,
  implementation per language, tests, security, performance, documentation, packaging, release;
* **imported roles** - the worker and agent names the 72 sealed factories define, read from their
  vendored records, so the catalogue inherits the vocabulary of the ecosystem it was drawn from;
* **topologies** - the multi-agent shapes those factories use (hierarchical, crew, graph, handoff,
  squad, workflow, swarm, debate, reflexion). The swarm scheduler uses them to decide how instances
  relate, who checks whom, and when a result needs a second opinion.

Every role declares an `offline` path: the tool calls it makes when no model answers. That is what
makes the component plug-and-work - with no model it still produces real artefacts (metrics, scans,
scaffolds, test runs, reports), and with a model it produces those *and* the model's work.
"""

from __future__ import annotations

from dataclasses import dataclass, field

TOOL_GROUPS = {
    "read": ("fs.list", "fs.read", "fs.stat", "fs.search", "fs.hash", "code.metrics", "code.todos",
             "code.secrets", "code.structure", "text.json", "text.toml", "text.csv", "text.regex",
             "text.html", "archive.list", "git.command"),
    "write": ("fs.write", "fs.mkdir", "fs.copy", "fs.move", "fs.delete", "text.patch"),
    "run": ("proc.run",),
    "net": ("net.fetch", "net.http"),
    "state": ("memory.store", "memory.recall", "plan.write", "plan.update", "sqlite.query"),
    "model": ("model.ask",),
    "github": ("github.repo", "github.file", "github.search", "github.issues", "github.commits",
               "github.pr", "github.probe"),
}

TOPOLOGIES = {
    "hierarchical": "a lead splits the work and merges the results of its workers",
    "crew": "specialists run in sequence, each handing its artefact to the next",
    "graph": "roles are nodes; work flows along the edges and can revisit a node",
    "handoff": "a role finishes and hands the conversation to the role that owns the next part",
    "squad": "a router picks the cheapest capable specialist for each request",
    "workflow": "a fixed pipeline of roles, each with an entrance and an exit condition",
    "swarm": "many identical workers take items from one queue",
    "debate": "two roles argue a change and a third decides",
    "reflexion": "a role critiques its own output and revises it once",
    "maker-checker": "every artefact is produced by one role and verified by another that did not "
                     "make it",
}


@dataclass
class Role:
    id: str
    title: str
    mission: str
    tools: tuple[str, ...]
    offline: tuple[dict, ...] = ()
    checks: tuple[dict, ...] = ()
    topology: str = "workflow"
    model_preference: str = "auto"
    source: str = "core"
    tags: tuple[str, ...] = field(default_factory=tuple)


def _tool_calls(*names: str) -> tuple[str, ...]:
    resolved: list[str] = []
    for name in names:
        resolved.extend(TOOL_GROUPS[name])
    return tuple(dict.fromkeys(resolved))


CORE_ROLES: tuple[Role, ...] = (
    Role("intake", "Intake", "Turn the request into a written brief with acceptance criteria.",
         _tool_calls("read", "write", "state"), offline=(
             {"tool": "plan.write", "args": {"items": "read the workspace|write the brief|record "
                                                       "acceptance criteria|hand to architecture"}},
             {"tool": "fs.write", "args": {"path": ".sealed/brief.md",
                                           "text": "# Brief\n\nWritten by the intake role from the "
                                                   "workspace as it stands.\n"}},
             {"tool": "code.metrics", "args": {"path": ".", "limit": 60}},
         ), checks=({"tool": "fs.stat", "args": {"path": ".sealed/brief.md"}},),
         topology="workflow"),
    Role("architect", "Architect", "Decide the module boundaries and the file layout.",
         _tool_calls("read", "write", "state"), offline=(
             {"tool": "code.structure", "args": {"path": "README.md"}},
             {"tool": "memory.store", "args": {"key": "architecture", "value":
                                               "derived from the workspace tree and its metrics",
                                               "tags": "architecture"}},
         ), checks=({"tool": "memory.recall", "args": {"key": "architecture"}},),
         topology="hierarchical"),
    Role("implementer-python", "Implementer (Python)", "Write and change Python code.",
         _tool_calls("read", "write", "run", "state"),
         offline=({"tool": "code.metrics", "args": {"path": ".", "limit": 40}},),
         checks=({"tool": "code.structure", "args": {"path": "README.md"}},),
         topology="maker-checker", tags=("language:python",)),
    Role("implementer-javascript", "Implementer (JavaScript/TypeScript)",
         "Write and change JavaScript and TypeScript code.",
         _tool_calls("read", "write", "run", "state"),
         offline=({"tool": "fs.search", "args": {"pattern": "\\b(function|class|export)\\b",
                                                 "glob": "*.js", "limit": 40}},),
         checks=({"tool": "code.syntax", "args": {"path": ".", "limit": 3}},),
         topology="maker-checker", tags=("language:javascript",)),
    Role("implementer-systems", "Implementer (systems languages)",
         "Write and change Go, Rust, C and C++ code.",
         _tool_calls("read", "write", "run", "state"),
         offline=({"tool": "fs.search", "args": {"pattern": "\\bfunc |\\bfn |int main",
                                                 "limit": 40}},),
         checks=({"tool": "proc.run", "args": {"command": "true", "allow_failure": True}},),
         topology="maker-checker", tags=("language:go", "language:rust", "language:c")),
    Role("implementer-jvm", "Implementer (JVM)",
         "Write and change Java and Kotlin code, including Android sources.",
         _tool_calls("read", "write", "run", "state"),
         offline=({"tool": "fs.search", "args": {"pattern": "\\b(class|interface|fun) ",
                                                 "glob": "*.kt", "limit": 40}},),
         checks=({"tool": "fs.search", "args": {"pattern": "class ", "limit": 5}},),
         topology="maker-checker", tags=("language:java", "language:kotlin")),
    Role("test-author", "Test author", "Write tests that would fail if the code were wrong.",
         _tool_calls("read", "write", "run"),
         offline=({"tool": "code.todos", "args": {"path": ".", "limit": 40}},),
         checks=({"tool": "code.todos", "args": {"path": "."}},), topology="maker-checker"),
    Role("verifier", "Verifier", "Run the test suites and report exactly what happened.",
         _tool_calls("read", "run"), offline=(
             {"tool": "proc.run", "args": {"command": "python3 -m unittest discover -s . -p "
                                                       "'test_*.py' -t .", "allow_failure": True,
                                           "timeout": 300}},
         ), checks=({"tool": "fs.list", "args": {"path": ".", "depth": 1}},), topology="debate"),
    Role("security", "Security reviewer", "Look for credentials, unsafe defaults and open surfaces.",
         _tool_calls("read"), offline=({"tool": "code.secrets", "args": {"path": "."}},),
         checks=({"tool": "code.secrets", "args": {"path": "."}},), topology="maker-checker"),
    Role("performance", "Performance reviewer", "Find the work that grows faster than the input.",
         _tool_calls("read", "run"), offline=(
             {"tool": "code.metrics", "args": {"path": ".", "limit": 80}},),
         checks=({"tool": "code.metrics", "args": {"path": "."}},), topology="reflexion"),
    Role("documenter", "Documenter", "Write the README, the runbook and the changelog.",
         _tool_calls("read", "write"), offline=(
             {"tool": "fs.write", "args": {"path": ".sealed/active-roles.md",
                                           "text": "# Roles that ran\n\nWritten by the documenter "
                                                   "role.\n", "append": True}},
         ), checks=({"tool": "fs.stat", "args": {"path": ".sealed/active-roles.md"}},),
         topology="crew"),
    Role("packager", "Packager", "Produce the artefact the consumer installs.",
         _tool_calls("read", "run", "write"), offline=(
             {"tool": "fs.list", "args": {"path": ".", "depth": 2}},),
         checks=({"tool": "fs.list", "args": {"path": ".", "depth": 1}},),
         topology="workflow"),
    Role("release", "Release", "Record what shipped, its hash and its provenance.",
         _tool_calls("read", "write", "state"), offline=(
             {"tool": "memory.store", "args": {"key": "release", "value": "no artefact was built in "
                                                                          "this run", "tags":
                                               "release"}},
         ), checks=({"tool": "memory.recall", "args": {"key": "release"}},), topology="workflow"),
    Role("researcher", "Researcher", "Read the outside world when the network allows it.",
         _tool_calls("read", "net", "state", "github"), offline=(
             # The GitHub API tools are declared for this role, so an agent driving it with a model
             # can reach GitHub: repository metadata, files, search, issues, commits and pull
             # requests. The offline path calls only `github.probe`, which reports reachability and
             # never raises - a fetch here would fail the step on every machine without a network,
             # and a belt that only passes where GitHub is up is not a belt that works offline.
             {"tool": "github.probe", "args": {}},
             {"tool": "memory.recall", "args": {"query": "architecture"}},),
         checks=({"tool": "time.now", "args": {}},), topology="squad"),
    Role("critic", "Critic", "Attack the strongest claim in the artefact.",
         _tool_calls("read"), offline=(
             {"tool": "code.todos", "args": {"path": ".", "limit": 20}},),
         checks=({"tool": "code.todos", "args": {"path": "."}},), topology="debate"),
)


def imported_roles(records: list[dict]) -> list[Role]:
    """Roles named by the sealed factories themselves, from their vendored records.

    A vendored MCP server becomes a *tool* role: an agent whose job is the capability that server
    provides, executed through the native tool of the same name whether or not the server is
    installed.
    """
    roles: list[Role] = []
    seen: set[str] = set()
    for record in records:
        if record.get("_group") == "mcp-vendor":
            identifier = record.get("id", "")
            if not identifier or f"mcp-{identifier}" in seen:
                continue
            seen.add(f"mcp-{identifier}")
            launch = record.get("launch") or {}
            verified = [tool for server in (launch.get("verified") or [])
                        for tool in server.get("tools", [])]
            roles.append(Role(
                id=f"mcp-{identifier}",
                title=f"Tool role: {record.get('full_name', identifier)}",
                mission=(f"Provide what {record.get('full_name', identifier)} provides: "
                         f"{record.get('what', '')[:120]}"),
                tools=_tool_calls("read", "write"),
                offline=({"tool": "env.info", "args": {}},),
                checks=({"tool": "test.discover", "args": {"path": "."}},),
                topology="squad",
                source="mcp-server" if launch.get("launch_verified") else "mcp-import",
                tags=tuple(f"tool:{name}" for name in verified[:6]),
            ))
            continue
        name = record.get("full_name", "")
        identifier = record.get("id", "")
        if not identifier or identifier in seen:
            continue
        seen.add(identifier)
        providers = record.get("providers") or []
        entry = (record.get("entrypoints") or ["the repository root"])[0]
        roles.append(Role(
            id=f"imported-{identifier}",
            title=f"Imported factory: {name}",
            mission=f"Apply the structure of {name} to this workspace, as its sealed slice defines "
                    f"it ({entry}).",
            tools=_tool_calls("read", "write"),
            offline=({"tool": "fs.stat", "args": {"path": "."}},),
            checks=({"tool": "code.metrics", "args": {"path": "."}},),
            topology="crew",
            source="imported",
            tags=tuple(f"provider:{p}" for p in providers),
        ))
    return roles


def catalogue(records: list[dict] | None = None, languages: list[dict | str] | None = None
              ) -> list[Role]:
    """Core roles, one specialist per installed language, and the imported factories' roles.

    `languages` is what `langkit.detect()` found: a probe record per language, or a bare name. When
    the machine's own probe names the binary that answered (`node` for javascript, `cc` for c), that
    is what the role probes with - a specialist that runs `javascript --version` would only ever
    record a missing program.
    """
    roles = list(CORE_ROLES)
    for probe in languages or []:
        name = probe["language"] if isinstance(probe, dict) else probe
        binary = probe.get("binary") if isinstance(probe, dict) else None
        wanted = probe.get("wanted") or [] if isinstance(probe, dict) else []
        offline = ({"tool": "proc.run",
                    "args": {"command": f"{binary} --version", "allow_failure": True}}
                   if binary else {"tool": "fs.list", "args": {"path": ".", "depth": 1}})
        roles.append(Role(
            id=f"language-{name}",
            title=f"Language specialist ({name})",
            mission=f"Build, check and package {name} work with the toolchain installed here"
                    + (f" ({binary})." if binary else "; this language is a project layout, not a "
                                                       "program, so the role works on files."),
            tools=_tool_calls("read", "write", "run"),
            offline=(offline,),
            checks=({"tool": "time.now", "args": {}},),
            topology="squad", source="language",
            tags=(f"language:{name}",) + tuple(f"binary:{b}" for b in wanted[:1]),
        ))
    roles.extend(imported_roles(records or []))
    return roles


def by_id(roles: list[Role]) -> dict[str, Role]:
    return {role.id: role for role in roles}


def describe(roles: list[Role]) -> dict:
    return {"roles": len(roles),
            "core": sum(1 for r in roles if r.source == "core"),
            "language": sum(1 for r in roles if r.source == "language"),
            "imported": sum(1 for r in roles if r.source == "imported"),
            "mcp_verified": sum(1 for r in roles if r.source == "mcp-server"),
            "mcp_catalogue": sum(1 for r in roles if r.source == "mcp-import"),
            "topologies": sorted({r.topology for r in roles}),
            "entries": [{"id": r.id, "title": r.title, "topology": r.topology,
                         "tools": len(r.tools), "offline_steps": len(r.offline),
                         "source": r.source} for r in roles]}
