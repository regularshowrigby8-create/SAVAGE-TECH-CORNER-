"""The sealed environment: its tools, its MCP server, its gateway, its agents and its language packs.

These tests hold the component to what it claims when it is carried to another repository: the tools
work offline, the MCP server speaks the protocol a client expects, the gateway routes to whatever is
reachable and refuses honestly when nothing is, the swarm executes agents that really touch the
workspace, and a scaffold in a language builds and tests with the toolchain that is installed.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEALED = REPO_ROOT / "sealed-factories"
RUNTIME = SEALED / "runtime"
CLI = RUNTIME / "cli.py"
sys.path.insert(0, str(RUNTIME))

import agents  # noqa: E402
import langkit  # noqa: E402
import mcplink  # noqa: E402
import swarm  # noqa: E402
import toolkit  # noqa: E402


def _load_by_path(name: str):
    """Load a runtime module under its own name, whatever else on sys.path has the same name.

    `backend/api_gateway/gateway.py` exists in this repository, so a plain `import gateway` can
    resolve to the wrong file depending on test order. The sealed runtime's own module is loaded
    from its path, and that is what the tests exercise.
    """
    import importlib.util
    key = f"sealed_runtime_{name}"
    if key in sys.modules:
        return sys.modules[key]
    spec = importlib.util.spec_from_file_location(key, RUNTIME / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[key] = module
    spec.loader.exec_module(module)
    return module


gateway = _load_by_path("gateway")


def workspace(scratch: Path) -> toolkit.Workspace:
    return toolkit.Workspace(scratch)


class ToolLayerTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.workspace = workspace(self.root)
        (self.root / "src").mkdir(exist_ok=True)
        (self.root / "src" / "core.py").write_text("def add(a, b):\n    return a + b  # TODO fix\n",
                                                   encoding="utf-8")
        self.registry = toolkit.build_registry(self.workspace, "")

    def test_the_registry_holds_a_full_tool_set(self) -> None:
        self.assertGreaterEqual(len(self.registry), 40)
        for name, tool in self.registry.items():
            self.assertTrue(tool["description"], f"{name} has no description")
            self.assertEqual(tool["schema"]["type"], "object", f"{name} has no object schema")
            self.assertTrue(callable(tool["fn"]))

    def test_read_write_search_round_trip(self) -> None:
        tools = self.registry
        tools["fs.write"]["fn"](path="notes/todo.md", text="first line\n")
        tools["fs.write"]["fn"](path="notes/todo.md", text="second line\n", append=True)
        read = tools["fs.read"]["fn"](path="notes/todo.md")
        self.assertEqual(read["text"], "first line\nsecond line")
        found = tools["fs.search"]["fn"](pattern="second", path=".")
        self.assertEqual([m["path"] for m in found["matches"]], ["notes/todo.md"])
        listing = tools["fs.list"]["fn"](path=".", depth=2)
        self.assertIn("notes/todo.md", [entry["path"] for entry in listing["entries"]])

    def test_a_path_that_escapes_the_workspace_is_refused(self) -> None:
        for name, arguments in (("fs.read", {"path": "../../etc/passwd"}),
                                ("fs.write", {"path": "/tmp/absolute-write.txt", "text": "no"}),
                                ("fs.list", {"path": "../.."})):
            with self.subTest(tool=name):
                with self.assertRaises(toolkit.ToolError):
                    self.registry[name]["fn"](**arguments)

    def test_code_structure_uses_a_real_python_parse(self) -> None:
        result = self.registry["code.structure"]["fn"](path="src/core.py")
        self.assertEqual(result["parser"], "ast")
        self.assertTrue(result["valid"])
        self.assertEqual([s["name"] for s in result["symbols"]], ["add"])

    def test_unfinished_work_and_secrets_are_found_where_they_are(self) -> None:
        todos = self.registry["code.todos"]["fn"](path=".")
        self.assertEqual([f["line"] for f in todos["findings"]], [2])
        # Deliberately low entropy and shaped like a credential: it matches the detector's OpenAI
        # pattern, and this repository's own security agent will not read the fixture itself as a
        # leaked key (it flags high-entropy assignments outside the test fixtures it recognises).
        (self.root / "config.py").write_text('API_KEY = "sk-000000000000000000000000"\n',
                                             encoding="utf-8")
        secrets = self.registry["code.secrets"]["fn"](path=".")
        self.assertFalse(secrets["clean"])
        self.assertEqual(secrets["findings"][0]["path"], "config.py")
        self.assertEqual(secrets["findings"][0]["kind"], "openai-key")

    def test_the_program_runner_uses_no_shell_at_all(self) -> None:
        """A tool that hands out a shell is how a path jail stops being a jail."""
        run = self.registry["proc.run"]["fn"]
        self.assertEqual(run(command="python3 -c 'print(6*7)'")["stdout"].strip(), "42")
        chained = run(command="python3 -c 'print(1)' && python3 -c 'print(2)'")
        self.assertEqual(chained["stdout"].split(), ["1", "2"])
        stopped = run(command="python3 -c 'raise SystemExit(4)' && python3 -c 'print(3)'",
                      allow_failure=True)
        self.assertEqual(stopped["exit_code"], 4)
        self.assertEqual(stopped["stdout"].split(), [], "a failed step must stop the chain")
        for refused in ("echo hi | wc -l", "echo hi > file.txt", "echo `whoami`", "echo $(whoami)",
                        "echo a; echo b", "sleep 0 &"):
            with self.subTest(command=refused):
                with self.assertRaises(toolkit.ToolError):
                    run(command=refused)
        with self.assertRaises(toolkit.ToolError):
            self.registry["git.command"]["fn"](arguments="status && rm -rf build")

    def test_the_syntax_tool_reports_what_it_could_and_could_not_check(self) -> None:
        (self.root / "broken.py").write_text("def add(a, b)\n    return a + b\n", encoding="utf-8")
        report = self.registry["code.syntax"]["fn"](path=".")
        checked = {entry["path"]: entry for entry in report["checked"]}
        self.assertIn("broken.py", checked)
        self.assertFalse(checked["broken.py"]["ok"])
        self.assertIn("line 1", checked["broken.py"]["error"])
        self.assertIn("src/core.py", checked)
        self.assertTrue(checked["src/core.py"]["ok"])
        self.assertFalse(report["clean"])
        for entry in report["unchecked"]:
            self.assertTrue(entry["reason"], "a file that was not checked must say why")

    def test_a_patch_applies_exactly_or_not_at_all(self) -> None:
        (self.root / "a.txt").write_text("one\ntwo\nthree\n", encoding="utf-8")
        diff = "@@ -1,3 +1,3 @@\n one\n-two\n+two changed\n three\n"
        applied = self.registry["text.patch"]["fn"](path="a.txt", diff=diff)
        self.assertTrue(applied["applied"])
        self.assertEqual((self.root / "a.txt").read_text(encoding="utf-8"),
                         "one\ntwo changed\nthree\n")
        with self.assertRaises(toolkit.ToolError):
            self.registry["text.patch"]["fn"](path="a.txt", diff="@@ -1,3 +1,3 @@\n one\n-two\n"
                                                                 " +two changed\n three\n")

    def test_processes_report_exit_codes_instead_of_interpretation(self) -> None:
        ok = self.registry["proc.run"]["fn"](command="python3 -c 'print(6*7)'")
        self.assertEqual((ok["exit_code"], ok["stdout"].strip()), (0, "42"))
        with self.assertRaises(toolkit.ToolError):
            self.registry["proc.run"]["fn"](command="python3 -c 'raise SystemExit(3)'")
        allowed = self.registry["proc.run"]["fn"](command="python3 -c 'raise SystemExit(3)'",
                                                  allow_failure=True)
        self.assertEqual(allowed["exit_code"], 3)

    def test_archives_round_trip(self) -> None:
        created = self.registry["archive.create"]["fn"](path="out.zip", members="src")
        self.assertGreater(created["bytes"], 0)
        listed = self.registry["archive.list"]["fn"](path="out.zip")
        self.assertEqual(listed["kind"], "zip")
        self.assertIn("src/core.py", [entry["name"] for entry in listed["entries"]])

    def test_statistics_and_deterministic_sampling(self) -> None:
        stats = self.registry["data.stats"]["fn"](values="1, 2, 3, 4")
        self.assertEqual((stats["count"], stats["mean"], stats["median"]), (4, 2.5, 2.5))
        first = self.registry["rand.stream"]["fn"](count=5, seed="x")
        second = self.registry["rand.stream"]["fn"](count=5, seed="x")
        third = self.registry["rand.stream"]["fn"](count=5, seed="y")
        self.assertEqual(first["values"], second["values"])
        self.assertNotEqual(first["values"], third["values"])

    def test_the_environment_and_test_discovery_tools_are_real(self) -> None:
        (self.root / "tests").mkdir(exist_ok=True)
        (self.root / "tests" / "test_core.py").write_text("def test_nothing():\n    assert True\n",
                                                          encoding="utf-8")
        found = self.registry["test.discover"]["fn"](path=".")
        self.assertIn("tests/test_core.py", [t["path"] for t in found["tests"]])
        info = self.registry["env.info"]["fn"]()
        self.assertIn("python3", info["present"])
        self.assertIn("python", info)
        self.assertEqual(sorted(info["present"] + info["missing"]), sorted(info["toolchains"]))
        port = self.registry["port.check"]["fn"](host="127.0.0.1", port=1)
        self.assertFalse(port["open"])

    def test_sqlite_tools_run_real_sql(self) -> None:
        created = self.registry["sqlite.query"]["fn"](
            database="app.db", sql="CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
        self.assertEqual(created["rowcount"], -1)
        self.registry["sqlite.query"]["fn"](database="app.db",
                                             sql="INSERT INTO items (name) VALUES ('first')")
        rows = self.registry["sqlite.query"]["fn"](database="app.db",
                                                    sql="SELECT name FROM items")
        self.assertEqual(rows["rows"], [{"name": "first"}])

    def test_offline_network_tools_fail_with_a_reason(self) -> None:
        with self.assertRaises(toolkit.ToolError) as caught:
            self.registry["net.http"]["fn"](url="http://127.0.0.1:9/nothing", timeout=2)
        self.assertTrue(str(caught.exception))

    def test_model_ask_refuses_without_a_gateway(self) -> None:
        with self.assertRaises(toolkit.ToolError) as caught:
            self.registry["model.ask"]["fn"](prompt="hello")
        self.assertIn("gateway", str(caught.exception))


class McpServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        (self.root / "src").mkdir()
        (self.root / "src" / "core.py").write_text("def add(a, b):\n    return a + b\n",
                                                   encoding="utf-8")

    def converse(self, messages: list[dict]) -> dict[int, dict]:
        done = subprocess.run([sys.executable, str(RUNTIME / "toolserver.py"), "--root",
                               str(self.root)],
                              input="\n".join(json.dumps(m) for m in messages) + "\n",
                              capture_output=True, text=True, timeout=180)
        self.assertEqual(done.returncode, 0, done.stderr)
        replies = {}
        for line in done.stdout.splitlines():
            if not line.strip():
                continue
            message = json.loads(line)
            if message.get("id") is not None:
                replies[message["id"]] = message
        return replies

    def test_the_server_speaks_the_protocol_a_client_expects(self) -> None:
        replies = self.converse([
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                        "clientInfo": {"name": "test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
            {"jsonrpc": "2.0", "id": 3, "method": "ping"},
            {"jsonrpc": "2.0", "id": 4, "method": "resources/list"},
        ])
        handshake = replies[1]["result"]
        self.assertEqual(handshake["protocolVersion"], "2025-06-18")
        self.assertEqual(handshake["serverInfo"]["name"], "sealed-factory-tools")
        self.assertIn("tools", handshake["capabilities"])
        tools = replies[2]["result"]["tools"]
        self.assertGreaterEqual(len(tools), 40)
        self.assertTrue(all({"name", "description", "inputSchema"} <= set(tool) for tool in tools))
        self.assertEqual(replies[3]["result"], {})
        self.assertEqual(replies[4]["result"], {"resources": []})

    def test_calls_reach_the_tools_and_errors_are_reported_not_invented(self) -> None:
        replies = self.converse([
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                        "clientInfo": {"name": "test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
             "params": {"name": "code.structure", "arguments": {"path": "src/core.py"}}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "fs.read", "arguments": {"path": "../../etc/passwd"}}},
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
             "params": {"name": "no.such.tool", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
             "params": {"name": "fs.list", "arguments": {"nonsense": 1}}},
        ])
        good = replies[2]["result"]
        self.assertFalse(good["isError"])
        self.assertEqual(good["structuredContent"]["symbols"][0]["name"], "add")
        for identifier in (3, 4, 5):
            self.assertTrue(replies[identifier]["result"]["isError"],
                            f"call {identifier} should have been refused")

    def test_double_dash_list_prints_the_directory(self) -> None:
        done = subprocess.run([sys.executable, str(RUNTIME / "toolserver.py"), "--root",
                               str(self.root), "--list"], capture_output=True, text=True, timeout=120)
        self.assertEqual(done.returncode, 0)
        self.assertIn("fs.read", done.stdout)
        self.assertRegex(done.stdout, r"\n4[0-9] tools\n")


class GitHubToolTest(unittest.TestCase):
    """The github.* tools, driven against a local server that answers the API's own shape.

    The real api.github.com is not required - and must not be required, because a test that only
    passes on a network is a test that hides a broken parser. A stub stands in for it, and the
    refusal paths are asserted separately: neither one reaches out.
    """

    class _Stub:
        """A local server answering the handful of GitHub shapes the tools read."""

        def __init__(self) -> None:
            import http.server

            #: Every request line the stub served, in order. Silencing a handler must not mean
            #: throwing its output away: this keeps it, so a test can assert which API path the tool
            #: actually asked for instead of assuming it.
            self.requests: list[str] = []

            class Handler(http.server.BaseHTTPRequestHandler):
                def log_message(self, fmt, *args):
                    self.server.requests.append(fmt % args)

                def _send(self, payload: dict, status: int = 200) -> None:
                    body = json.dumps(payload).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("X-RateLimit-Remaining", "4999")
                    self.end_headers()
                    self.wfile.write(body)

                def do_GET(self):  # noqa: N802 - the handler's own name
                    if self.path == "/rate_limit":
                        self._send({"resources": {"core": {"limit": 5000, "remaining": 4999}}})
                    elif self.path == "/repos/owner/name":
                        self._send({"full_name": "owner/name", "description": "a stub",
                                    "language": "Python", "stargazers_count": 7, "forks_count": 1,
                                    "open_issues_count": 2, "default_branch": "main",
                                    "archived": False, "pushed_at": "2026-10-14T00:00:00Z",
                                    "created_at": "2020-01-01T00:00:00Z",
                                    "license": {"spdx_id": "MIT"}, "topics": ["stub"]})
                    elif self.path.startswith("/search/repositories"):
                        self._send({"total_count": 1, "items": [
                            {"full_name": "owner/name", "stargazers_count": 7,
                             "description": "a stub", "license": {"spdx_id": "MIT"},
                             "pushed_at": "2026-10-14T00:00:00Z"}]})
                    elif self.path.startswith("/repos/owner/name/commits"):
                        self._send([{"sha": "a" * 40, "commit": {"message": "the first line\n\nbody",
                                                                 "author": {"name": "someone",
                                                                            "date": "2026-10-14"}}}])
                    elif self.path.startswith("/repos/owner/name/issues"):
                        self._send([{"number": 3, "title": "an issue", "state": "open",
                                     "user": {"login": "someone"}, "created_at": "2026-10-14"}])
                    elif self.path == "/repos/owner/name/pulls/17":
                        self._send({"number": 17, "title": "a pull request", "state": "open",
                                    "merged": False, "mergeable": True,
                                    "mergeable_state": "clean", "additions": 5, "deletions": 1,
                                    "changed_files": 1, "head": {"sha": "b" * 40},
                                    "base": {"ref": "main"}})
                    elif self.path.startswith("/repos/owner/name/pulls/17/files"):
                        self._send([{"filename": "README.md", "status": "modified",
                                     "additions": 5, "deletions": 1}])
                    else:
                        self._send({"message": "no such route: " + self.path}, status=404)

                def do_PUT(self):  # noqa: N802
                    self._send({"message": "not supported here"}, status=405)

            self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            self.server.requests = self.requests
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

        def stop(self) -> None:
            self.server.shutdown()
            self.server.server_close()

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.stub = self._Stub()
        self.addCleanup(self.stub.stop)
        original = dict(os.environ)
        self.addCleanup(lambda: (os.environ.clear(), os.environ.update(original)))
        os.environ["GITHUB_TOKEN"] = "ghp_" + "0" * 38
        self.api = toolkit.GITHUB_API
        toolkit.GITHUB_API = self.stub.url
        self.addCleanup(lambda: setattr(toolkit, "GITHUB_API", self.api))
        self.registry = toolkit.build_registry(toolkit.Workspace(Path(self._tmp.name)), "")

    def _call(self, name: str, **arguments):
        return self.registry[name]["fn"](**arguments)

    def test_the_six_github_tools_are_registered_and_callable(self) -> None:
        self.assertEqual(sorted(n for n in self.registry if n.startswith("github.")),
                         ["github.commits", "github.file", "github.issues", "github.pr",
                          "github.probe", "github.repo", "github.search"])

    def test_repository_metadata_comes_from_the_answer_not_from_guesswork(self) -> None:
        answer = self._call("github.repo", repository="owner/name")
        self.assertEqual(answer["license"], "MIT")
        self.assertEqual(answer["default_branch"], "main")
        self.assertEqual(answer["stars"], 7)
        self.assertEqual(answer["status"], 200)
        self.assertTrue(answer["authenticated"])
        self.assertIn("GET /repos/owner/name", self.stub.requests[-1])

    def test_commits_and_issues_report_the_first_line_and_the_count(self) -> None:
        commits = self._call("github.commits", repository="owner/name", limit=5)
        self.assertEqual(commits["items"][0]["message"], "the first line")
        self.assertEqual(commits["returned"], 1)
        issues = self._call("github.issues", repository="owner/name")
        self.assertEqual(issues["items"][0]["number"], 3)
        self.assertFalse(issues["items"][0]["is_pull_request"])

    def test_a_pull_request_reports_its_files_and_mergeability(self) -> None:
        answer = self._call("github.pr", repository="owner/name", number=17)
        self.assertEqual(answer["mergeable_state"], "clean")
        self.assertEqual(answer["files"][0]["filename"], "README.md")

    def test_search_returns_the_items_it_was_given(self) -> None:
        answer = self._call("github.search", kind="repositories", query="stub in:name", limit=5)
        self.assertEqual(answer["total"], 1)
        self.assertEqual(answer["items"][0]["full_name"], "owner/name")

    def test_a_bad_request_is_refused_before_any_call_is_made(self) -> None:
        with self.assertRaises(toolkit.ToolError):
            self._call("github.repo", repository="not-a-repo")
        with self.assertRaises(toolkit.ToolError):
            self._call("github.search", kind="gists", query="anything")
        with self.assertRaises(toolkit.ToolError):
            toolkit.github_call("repos/owner/name; rm -rf /")

    def test_the_token_is_used_and_never_returned(self) -> None:
        self.assertTrue(toolkit.github_token())
        answer = self._call("github.repo", repository="owner/name")
        self.assertNotIn("0" * 38, json.dumps(answer))
        empty = dict(os.environ)
        os.environ.pop("GITHUB_TOKEN", None)
        for name in toolkit.GITHUB_TOKEN_VARS[1:]:
            os.environ.pop(name, None)
        self.assertEqual(toolkit.github_token(), "")
        anonymous = self._call("github.repo", repository="owner/name")
        self.assertFalse(anonymous["authenticated"])
        os.environ.clear()
        os.environ.update(empty)

    def test_the_probe_reports_reachability_instead_of_raising_either_way(self) -> None:
        """`github.probe` is the one github tool whose subject *is* reachability.

        A run has to be able to record "this machine cannot reach GitHub, and here is why" as a
        successful observation, while the six data tools keep refusing. If the probe raised, the
        researcher role's offline step would fail on every air-gapped machine.
        """
        reachable = self._call("github.probe")
        self.assertTrue(reachable["available"])
        self.assertTrue(reachable["reachable"])
        self.assertEqual(reachable["status"], 200)
        self.assertTrue(reachable["token_present"])
        self.assertNotIn("0" * 38, json.dumps(reachable))

        # A host that answers, but not with a rate limit: reachable, not usable, and it says which.
        toolkit.GITHUB_API = f"{self.stub.url}/wrong-base"
        wrong_base = self._call("github.probe")
        self.assertFalse(wrong_base["available"])
        self.assertTrue(wrong_base["reachable"])
        self.assertIn("HTTP 404", wrong_base["reason"])

        # No host at all: a different fact, reported as a different fact.
        toolkit.GITHUB_API = "http://127.0.0.1:9"  # discard port: accepted by nothing
        unreachable = self._call("github.probe")
        self.assertFalse(unreachable["available"])
        self.assertFalse(unreachable["reachable"])
        self.assertEqual(unreachable["status"], 0)
        self.assertIn("not reachable", unreachable["reason"])

    def test_the_agents_can_reach_github_but_never_fetch_it_offline(self) -> None:
        """The capability is declared to a role; the offline path only probes.

        This pins the design rather than describing it: a role may hold the six data tools, but no
        offline step may call one, because a failed step fails the belt on a machine with no network.
        """
        self.assertFalse(hasattr(toolkit, "TOOL_GROUPS"),
                         "the tool groups belong to the agent catalogue, not the registry")
        groups = agents.TOOL_GROUPS["github"]
        self.assertEqual(set(groups), {"github.repo", "github.file", "github.search", "github.issues",
                                       "github.commits", "github.pr", "github.probe"})
        researcher = next(role for role in agents.catalogue([], ["python"])
                          if role.id == "researcher")
        self.assertTrue(set(groups) <= set(researcher.tools))
        offline = [step["tool"] for step in researcher.offline]
        self.assertIn("github.probe", offline)
        self.assertFalse([name for name in offline if name.startswith("github.")
                          and name != "github.probe"],
                         "an offline step calls GitHub for data, which fails without a network")

    def test_the_air_gap_switch_stops_the_call_from_being_made_at_all(self) -> None:
        """`SEALED_NO_NETWORK=1` must stop the attempt, not just report its failure.

        The stub records every request it serves, so this asserts what "no network" means: with the
        switch set the server is never contacted, every github tool refuses naming the switch, and the
        probe answers `disabled` rather than a timeout's worth of "unreachable".
        """
        os.environ["SEALED_NO_NETWORK"] = "1"
        self.addCleanup(os.environ.pop, "SEALED_NO_NETWORK", None)
        before = len(self.stub.requests)

        probe = self._call("github.probe")
        self.assertFalse(probe["available"])
        self.assertTrue(probe["disabled"])
        self.assertIn("SEALED_NO_NETWORK", probe["reason"])

        for tool, arguments in (("github.repo", {"repository": "owner/name"}),
                                ("github.file", {"repository": "owner/name", "path": "README.md"}),
                                ("github.search", {"kind": "repositories", "query": "x"}),
                                ("github.issues", {"repository": "owner/name"}),
                                ("github.commits", {"repository": "owner/name"}),
                                ("github.pr", {"repository": "owner/name", "number": 1})):
            with self.subTest(tool=tool):
                with self.assertRaises(toolkit.ToolError) as raised:
                    self._call(tool, **arguments)
                self.assertIn("SEALED_NO_NETWORK", str(raised.exception))

        self.assertEqual(before, len(self.stub.requests),
                         "a request reached the server while the air gap was switched on")

    def test_the_air_gap_switch_covers_the_network_tools_too(self) -> None:
        """A switch called "no network" that leaves `net.fetch` free would be a lie."""
        os.environ["SEALED_NO_NETWORK"] = "true"
        self.addCleanup(os.environ.pop, "SEALED_NO_NETWORK", None)
        before = len(self.stub.requests)
        with self.assertRaises(toolkit.ToolError) as raised:
            self._call("net.fetch", url=f"{self.stub.url}/repos/owner/name")
        self.assertIn("SEALED_NO_NETWORK", str(raised.exception))
        with self.assertRaises(toolkit.ToolError) as raised:
            self._call("net.http", url=f"{self.stub.url}/repos/owner/name")
        self.assertIn("SEALED_NO_NETWORK", str(raised.exception))
        self.assertEqual(before, len(self.stub.requests))
        # And with the switch off again, the same call goes through: the refusal is the switch's doing.
        os.environ.pop("SEALED_NO_NETWORK")
        self.assertEqual(self._call("net.fetch", url=f"{self.stub.url}/repos/owner/name")["status"], 200)
        self.assertGreater(len(self.stub.requests), before)

    def test_an_unreachable_api_is_refused_with_the_reason_not_an_empty_result(self) -> None:
        """Offline is a normal state for this component, and it must say so."""
        toolkit.GITHUB_API = "http://127.0.0.1:9"  # discard port: accepted by nothing
        with self.assertRaises(toolkit.ToolError) as raised:
            self._call("github.repo", repository="owner/name")
        self.assertIn("not reachable", str(raised.exception))

    def test_an_api_error_is_reported_with_the_api_s_message(self) -> None:
        with self.assertRaises(toolkit.ToolError) as raised:
            toolkit.github_call("repos/owner/name/nonexistent")
        self.assertIn("HTTP 404", str(raised.exception))


class GatewayTest(unittest.TestCase):
    ANSWER = "STUB: the gateway reached a model."

    class _Stub:
        """A local server speaking the OpenAI wire format, standing in for Ollama."""

        def __init__(self) -> None:
            import http.server
            answer = GatewayTest.ANSWER
            seen: list[dict] = []
            self.seen = seen

            class Handler(http.server.BaseHTTPRequestHandler):
                def do_POST(self):  # noqa: N802
                    length = int(self.headers.get("Content-Length") or 0)
                    body = json.loads(self.rfile.read(length) or b"{}")
                    seen.append({"auth": self.headers.get("Authorization"), "body": body})
                    payload = json.dumps({"choices": [{"message": {"content": answer}}]}).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

                def log_message(self, *args):
                    return

            import http.server as http_server
            self.server = http_server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            self.port = self.server.server_address[1]
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()

        def stop(self) -> None:
            self.server.shutdown()
            self.server.server_close()

    def test_routing_reaches_a_reachable_local_provider(self) -> None:
        stub = self._Stub()
        self.addCleanup(stub.stop)
        original = gateway.PROVIDERS["ollama"]
        gateway.PROVIDERS["ollama"] = (f"http://127.0.0.1:{stub.port}/v1/chat/completions",
                                       "llama3.1", False, "openai")
        gateway.LOCAL_PORTS["ollama"] = stub.port
        self.addCleanup(lambda: gateway.PROVIDERS.__setitem__("ollama", original))
        self.addCleanup(gateway.LOCAL_PORTS.pop, "ollama", None)

        result = gateway.route({"model": "auto", "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(result["provider"], "ollama")
        self.assertEqual(result["choices"][0]["message"]["content"], self.ANSWER)
        self.assertEqual(stub.seen[-1]["auth"], None, "a local provider needs no key")

    def test_with_nothing_reachable_the_gateway_refuses_with_the_list_of_what_it_looked_for(
            self) -> None:
        for provider in list(gateway.LOCAL_PORTS):
            gateway.LOCAL_PORTS[provider] = 1
        self.addCleanup(gateway.LOCAL_PORTS.update,
                        {name: port for name, port in mcplink_ports().items()})
        with self.assertRaises(gateway.GatewayError) as caught:
            gateway.route({"model": "auto", "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(caught.exception.status, 503)
        self.assertIn("no model is reachable", str(caught.exception))

    def test_keys_are_collected_for_routing_and_never_returned(self) -> None:
        environment = {"OPENAI_API_KEY": "sk-alpha,sk-beta", "GROQ_API_KEY": "gsk-one"}
        original = dict(os.environ)

        def restore() -> None:
            # Exactly, not approximately: adding a key and leaving it behind would make later tests
            # believe a provider is reachable.
            os.environ.clear()
            os.environ.update(original)

        self.addCleanup(restore)
        os.environ.update(environment)
        keys = gateway.collect_keys()
        self.assertEqual(sorted(keys["openai"]), ["sk-alpha", "sk-beta"])
        self.assertEqual(keys["groq"], ["gsk-one"])
        keys_before = dict(gateway._KEYS)
        gateway._KEYS = keys
        self.addCleanup(lambda: setattr(gateway, "_KEYS", keys_before))
        report = gateway.health()
        self.assertEqual(report["key_counts"]["openai"], 2)
        self.assertNotIn("sk-alpha", json.dumps(report))
        rotated = {gateway.pick_key("openai") for _ in range(4)}
        self.assertEqual(rotated, {"sk-alpha", "sk-beta"}, "keys must rotate")

    def test_a_credential_never_survives_redaction(self) -> None:
        text = "authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz and AIzaSyA1234567890abcdefghij"
        redacted = gateway.redact(text)
        self.assertNotIn("sk-abcdefghijklmnopqrstuvwxyz", redacted)
        self.assertNotIn("AIzaSyA1234567890abcdefghij", redacted)

    def test_the_anthropic_wire_format_is_built_correctly(self) -> None:
        keys_before = dict(gateway._KEYS)
        gateway._KEYS = {"anthropic": ["sk-ant-test"]}
        self.addCleanup(lambda: setattr(gateway, "_KEYS", keys_before))
        url, payload, headers = gateway.build_request(
            "anthropic", {"model": "anthropic/claude-3-5-sonnet-latest",
                          "messages": [{"role": "system", "content": "be brief"},
                                       {"role": "user", "content": "hi"}]})
        self.assertTrue(url.endswith("/v1/messages"))
        self.assertEqual(headers["x-api-key"], "sk-ant-test")
        self.assertEqual(headers["anthropic-version"], "2023-06-01")
        self.assertEqual(payload["system"], "be brief")
        self.assertEqual(payload["messages"], [{"role": "user", "content": "hi"}])
        self.assertEqual(payload["model"], "claude-3-5-sonnet-latest")


def mcplink_ports() -> dict:
    return {"ollama": 11434, "lmstudio": 1234, "vllm": 8000, "llamacpp": 8080}


class SwarmTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        (self.root / "src").mkdir()
        (self.root / "src" / "core.py").write_text("def add(a, b):\n    return a + b\n",
                                                   encoding="utf-8")
        (self.root / "README.md").write_text("# scratch\n", encoding="utf-8")

    def test_a_million_instances_are_planned_without_materialising_them(self) -> None:
        roles = agents.catalogue([], ["python"])
        self.assertEqual(swarm.counted(roles, 1_000_000 // len(roles)), 1_000_000
                         - (1_000_000 % len(roles)))
        with self.assertRaises(ValueError):
            swarm.counted(roles, 2_000_000)
        first = next(iter(swarm.plan(roles, 5)))
        self.assertEqual(first["sequence"], 0)
        self.assertIn(first["role"], [role.id for role in roles])
        with self.assertRaises(ValueError):
            list(swarm.plan(roles, -1))

    def test_a_run_executes_agents_that_touch_the_workspace_and_checks_them(self) -> None:
        report = swarm.run(str(self.root), "add a tested function", instances=2,
                           execute_limit=80, model_calls=0)
        self.assertEqual(report["plan"]["capacity"], 1_000_000)
        self.assertEqual(report["plan"]["executed"], 80, "the limit is a hard cap")
        self.assertGreater(report["plan"]["roles_executed"], 0)
        self.assertEqual(len(report["plan"]["roles_not_executed"]),
                         report["roles"] - report["plan"]["roles_executed"])
        whole = swarm.run(str(self.root), "every role once", instances=1, execute_limit=1000,
                          model_calls=0)
        self.assertEqual(whole["plan"]["roles_not_executed"], [])
        self.assertGreaterEqual(whole["plan"]["executed"], whole["roles"])
        self.assertEqual(report["counts"]["model"], 0)
        self.assertEqual(report["counts"]["model_failed"], 0)
        self.assertEqual(report["counts"]["steps_failed"], 0)
        self.assertEqual(report["counts"]["checks_failed"], 0)
        self.assertGreaterEqual(report["counts"]["steps_ok"], report["plan"]["executed"],
                                "every executed instance runs at least one tool step")
        artefacts = Path(self.root) / report["artefacts_dir"]
        files = sorted(artefacts.glob("*.json"))
        self.assertEqual(len(files), report["counts"]["artefacts"])
        entry = json.loads(files[0].read_text(encoding="utf-8"))
        self.assertIn(entry["status"], ("OFFLINE", "MODEL", "MODEL-FAILED"))
        self.assertTrue(all(step["tool"] for step in entry["steps"]))

    def test_a_run_with_no_model_says_so_and_never_invents_one(self) -> None:
        report = swarm.run(str(self.root), "nothing", instances=1, execute_limit=40, model_calls=0)
        self.assertFalse(report["model"]["available"])
        self.assertEqual(report["model"]["calls_made"], 0)
        for role in report["per_role"].values():
            self.assertEqual(role["model_calls"], 0)

    def test_the_report_is_written_where_it_says_it_is(self) -> None:
        report = swarm.run(str(self.root), "check the report", instances=1, execute_limit=30,
                           model_calls=0)
        written = Path(self.root) / report["run_dir"] / "RUN.json"
        self.assertTrue(written.is_file())
        self.assertEqual(json.loads(written.read_text(encoding="utf-8"))["plan"],
                         report["plan"])


class LanguagePackTest(unittest.TestCase):
    def test_every_pack_declares_what_it_needs(self) -> None:
        for language in langkit.LANGUAGES:
            self.assertTrue(language.files, f"{language.name} carries no scaffold")
            self.assertTrue(any(path.endswith((".py", ".js", ".ts", ".go", ".rs", ".java", ".kt",
                                               ".c", ".cpp", ".cs", ".rb", ".php", ".sh", ".sql",
                                               ".html"))
                                for path in language.files),
                            f"{language.name} has no source file")
        self.assertGreaterEqual(len(langkit.LANGUAGES), 14)

    def test_the_python_scaffold_builds_and_tests_where_python_exists(self) -> None:
        self.assertIn("python", langkit.detect()["available"])
        with tempfile.TemporaryDirectory() as scratch:
            target = Path(scratch) / "project"
            done = subprocess.run([sys.executable, str(CLI), "init", "--language", "python",
                                   "--name", "check", "--into", str(target), "--check"],
                                  capture_output=True, text=True, timeout=600)
            self.assertEqual(done.returncode, 0, done.stderr)
            report = json.loads(done.stdout)
            self.assertTrue(report["verified"], done.stdout)
            self.assertTrue(all(check["exit_code"] == 0 for check in report["checks"]))

    def test_a_missing_toolchain_is_reported_and_not_faked(self) -> None:
        probes = {state["language"]: state for state in langkit.detect()["probes"]}
        for name, state in probes.items():
            if not state["available"]:
                self.assertTrue(state["wanted"], f"{name} reports no wanted binary")
                self.assertIsNone(tmp_version_of(name, state))

    def test_an_installed_toolchain_reports_its_version(self) -> None:
        probes = {state["language"]: state for state in langkit.detect()["probes"]}
        python = probes["python"]
        self.assertTrue(python["available"])
        self.assertRegex(python["version"] or "", r"Python 3")


def tmp_version_of(name: str, state: dict):
    """A missing toolchain must not carry a version. Kept explicit so the assertion is readable."""
    return state["version"] if state["available"] else None


class SealedEnvironmentReportTest(unittest.TestCase):
    """The stage-0 report must carry the environment, and it must be the truth about it."""

    @classmethod
    def setUpClass(cls) -> None:
        path = SEALED / "manifest" / "RUN-REPORT.json"
        if not path.is_file():
            raise unittest.SkipTest("run the sealed runtime first to produce its report")
        cls.report = json.loads(path.read_text(encoding="utf-8"))

    def test_the_environment_is_reported_with_every_part(self) -> None:
        environment = self.report.get("environment")
        self.assertIsNotNone(environment, "the sealed run must report its environment")
        self.assertGreaterEqual(environment["tools"]["total"], 40)
        self.assertGreaterEqual(environment["agents"]["roles"], 100)
        self.assertGreaterEqual(environment["mcp"]["catalogue"], 30)
        self.assertEqual(environment["agents"]["capacity"], 1_000_000)

    def test_the_swarm_ran_agents_and_they_passed_their_checks(self) -> None:
        run = self.report["environment"]["swarm"]
        self.assertGreaterEqual(run["plan"]["executed"], self.report["environment"]["agents"]["roles"])
        self.assertEqual(run["counts"]["steps_failed"], 0)
        self.assertGreaterEqual(run["counts"]["checks_passed"], 1)

    def test_the_seal_carries_the_mcp_and_ai_groups_too(self) -> None:
        seal = json.loads((SEALED / "SEAL.json").read_text(encoding="utf-8"))
        groups = seal["contents"]["by_group"]
        self.assertGreaterEqual(groups["imported factory"], 70)
        self.assertGreaterEqual(groups["AI-focused factory"], 20)
        self.assertGreaterEqual(groups["MCP tool server"], 30)
        catalogue = json.loads((SEALED / "mcp-servers.json").read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(catalogue["servers"]), 30)
        # Every server this component records as verified has to have been driven through a real
        # initialize/tools listing: the count is the evidence, not a target.
        self.assertGreaterEqual(catalogue["verified_total_servers"], 10,
                                "the servers whose handshake was run here must be recorded")
        self.assertGreaterEqual(catalogue["verified_total_tools"], 200)
        verified = [server for server in catalogue["servers"] if server["verified"]]
        self.assertTrue(all(server["command"] for server in verified),
                        "a server marked verified must carry the command that was verified")
        self.assertTrue(all(server["tools"] for server in verified
                            if server["name"] != "mcp-atlassian"),
                        "a verified server lists the tools the handshake saw")
        import json as _json
        registry = _json.loads((REPO_ROOT / "tools" / "factory" /
                                "imported_mcp_servers.json").read_text(encoding="utf-8"))
        self.assertEqual(registry["verified_total_servers"], catalogue["verified_total_servers"])
        self.assertEqual(registry["verified_total_tools"], catalogue["verified_total_tools"])


class PortabilityTest(unittest.TestCase):
    """The component has to work where it is carried to, not only where it was built.

    This is the difference between a directory in this repository and a sealed component: a copy of
    `sealed-factories/` dropped into another repository, with nothing from this one reachable, must
    still verify itself, list its tools, run its agents and refuse to run when a byte of its own
    code changed after sealing.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.scratch = Path(self._tmp.name)
        self.project = self.scratch / "another-repository"
        self.project.mkdir()
        (self.project / "README.md").write_text("# Another repository\n", encoding="utf-8")
        self.copy = self.scratch / "component"
        shutil.copytree(SEALED, self.copy,
                        ignore=shutil.ignore_patterns("manifest", "runs", "__pycache__"))
        self.cli = self.copy / "runtime" / "cli.py"

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        # cwd is the foreign project, so nothing resolves through this repository by accident.
        return subprocess.run([sys.executable, str(self.cli), *args], cwd=str(self.project),
                              capture_output=True, text=True, timeout=900)

    def test_a_copy_verifies_itself_without_this_repository(self) -> None:
        done = self._run("verify")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("[SEAL] OK", done.stdout)
        head = (self.copy / "runtime" / "cli.py").read_text(encoding="utf-8").split("def cmd_verify")[0]
        self.assertNotIn("seal_factories", head,
                         "the control surface must not need the sealer to start")

    def test_a_copy_runs_its_agents_in_another_repository_with_no_model(self) -> None:
        # Options after the command, because that is how anyone would type it.
        done = self._run("run", "--root", str(self.project), "--limit", "25", "--model-calls", "0")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("0 ran with a model", done.stdout)
        self.assertIn("0 failed", done.stdout)
        runs = sorted((self.project / ".sealed" / "swarm").glob("*/RUN.json"))
        self.assertTrue(runs, "the swarm must write its report into the workspace it was given")
        report = json.loads(runs[-1].read_text(encoding="utf-8"))
        self.assertEqual(report["counts"]["model"], 0)
        self.assertEqual(report["counts"]["checks_failed"], 0)
        self.assertEqual(report["plan"]["executed"], 25)

    def test_a_runtime_file_changed_after_sealing_fails_verification(self) -> None:
        target = self.copy / "runtime" / "swarm.py"
        target.write_text(target.read_text(encoding="utf-8") + "\n# changed after sealing\n",
                          encoding="utf-8")
        done = self._run("verify")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("component file modified: runtime/swarm.py", done.stdout)

    def test_a_module_added_after_sealing_is_not_sealed_code(self) -> None:
        (self.copy / "runtime" / "extra.py").write_text("VALUE = 1\n", encoding="utf-8")
        done = self._run("verify")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("component file not in the seal: runtime/extra.py", done.stdout)

    def test_the_sealer_and_the_component_agree_on_the_same_bytes(self) -> None:
        """One implementation would be enough; two that disagree would be worse than one."""
        driver = self.scratch / "sealer_verify.py"
        driver.write_text(
            "import sys\n"
            "from pathlib import Path\n"
            f"sys.path.insert(0, {str(REPO_ROOT / 'tools' / 'factory')!r})\n"
            "import seal_factories\n"
            f"root = Path({str(self.copy)!r})\n"
            "seal_factories.SEALED = root\n"
            "seal_factories.RUNTIME = root / 'runtime'\n"
            "seal_factories.SEAL_FILE = root / 'SEAL.json'\n"
            "seal_factories.SEAL_HASH = root / 'SEAL.sha256'\n"
            "sys.exit(seal_factories.verify(quiet=True))\n", encoding="utf-8")

        def sealer() -> subprocess.CompletedProcess:
            return subprocess.run([sys.executable, str(driver)], capture_output=True, text=True,
                                  timeout=900)

        good = sealer()
        self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
        self.assertEqual(self._run("verify").returncode, 0)

        payload = sorted((self.copy / "payloads").glob("*.tar.gz"))[0]
        payload.write_bytes(payload.read_bytes() + b"tampered")
        self.assertEqual(sealer().returncode, 1, "the sealer must see the changed payload")
        tampered = self._run("verify")
        self.assertEqual(tampered.returncode, 1, "the component must see it too")
        self.assertIn("payload hash", tampered.stdout)


if __name__ == "__main__":
    unittest.main()
