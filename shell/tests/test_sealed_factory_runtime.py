"""The sealed imported factory set: it is complete, honest, ordered first, and removable.

These tests hold the component to the promises made when it was built: every factory sealed and
hash-checked, licences respected, no invented model output, and - the point of sealing it - the
repository works exactly as well without it.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEALED = REPO_ROOT / "sealed-factories"
SEAL_FILE = SEALED / "SEAL.json"
RUNTIME = REPO_ROOT / "tools" / "factory" / "sealed_factory_runtime.py"
BELT = REPO_ROOT / "scripts" / "conveyor_belt.js"

sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))
import sealed_factory_runtime  # noqa: E402


def seal() -> dict:
    return json.loads(SEAL_FILE.read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


_RUN_REPORT: dict = {}


def runtime_report() -> dict:
    """Runs the sealed set once per test module and returns the report it wrote.

    The report is machine output and is not committed (sealed-factories/manifest/ is ignored), so
    every test that needs it asks for a run rather than trusting a file that happens to be there.
    """
    if not _RUN_REPORT:
        done = subprocess.run([sys.executable, str(RUNTIME), "run", "--max-model-calls", "0"],
                              capture_output=True, text=True, cwd=REPO_ROOT, timeout=900)
        if done.returncode != 0:
            raise AssertionError(f"the sealed set failed to run: {done.stdout}{done.stderr}")
        report_path = SEALED / "manifest" / "RUN-REPORT.json"
        if not report_path.is_file():
            raise AssertionError("the sealed run wrote no report")
        _RUN_REPORT.update(json.loads(report_path.read_text(encoding="utf-8")))
    return _RUN_REPORT


class SealedComponentTest(unittest.TestCase):
    def test_the_seal_verifies_with_its_own_verifier(self) -> None:
        done = subprocess.run([sys.executable, str(RUNTIME), "verify"], capture_output=True,
                              text=True, cwd=REPO_ROOT, timeout=600)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("[SEAL] OK", done.stdout)

    def test_every_payload_hash_in_the_seal_matches_the_file(self) -> None:
        document = seal()
        self.assertGreaterEqual(len(document["factories"]), 50)
        for factory in document["factories"]:
            payload = SEALED / factory["payload"]
            self.assertTrue(payload.is_file(), f"{factory['id']}: payload missing")
            self.assertEqual(factory["payload_sha256"], digest(payload),
                             f"{factory['id']}: payload changed")

    def test_the_seal_document_hash_matches_the_seal_file(self) -> None:
        recorded = (SEALED / "SEAL.sha256").read_text(encoding="utf-8").split()[0]
        self.assertEqual(recorded, digest(SEAL_FILE))

    def test_the_seal_counts_agree_with_its_own_records(self) -> None:
        document = seal()
        contents = document["contents"]
        self.assertEqual(contents["factories"], len(document["factories"]))
        self.assertEqual(contents["factories"],
                         contents["content_redistributed"] + contents["blueprint_only"])
        self.assertEqual(contents["payload_bytes"],
                         sum(f["payload_bytes"] for f in document["factories"]))
        self.assertEqual(contents["with_payload"], len(document["factories"]))
        self.assertGreaterEqual(contents["ai_worker_factories"], 25)

    def test_every_record_carries_the_provenance_of_the_factory(self) -> None:
        for factory in seal()["factories"]:
            for field in ("id", "full_name", "url", "license", "commit", "payload",
                          "payload_sha256", "payload_bytes", "files", "bytes",
                          "content_redistributed", "import_class", "snapshot"):
                self.assertIn(field, factory, f"{factory.get('id')} is missing {field}")
            self.assertTrue(factory["url"].startswith("https://github.com/"))
            self.assertRegex(factory["commit"], r"^[0-9a-f]{7,40}$")
            self.assertIsInstance(factory["content_redistributed"], bool)

    def test_a_licence_that_forbids_redistribution_never_carries_content(self) -> None:
        document = seal()
        for factory in document["factories"]:
            if factory["content_redistributed"]:
                self.assertIn(factory["license"], sealed_factory_runtime.seal_factories
                              .PERMISSIVE_LICENCES,
                              f"{factory['id']}: {factory['license']} may not be redistributed")
            else:
                record = json.loads((SEALED / "vendor" / f"{factory['id']}.json")
                                    .read_text(encoding="utf-8"))
                for entry in record["vendored_files"]:
                    self.assertNotIn("_content", entry,
                                     f"{factory['id']}: content redistributed without a licence")

    def test_a_published_licence_is_kept_with_the_component(self) -> None:
        for factory in seal()["factories"]:
            if factory["content_redistributed"] or factory["license"] in ("NOASSERTION", "NONE"):
                continue
            licences = list((SEALED / "licenses").glob(f"{factory['id']}-LICENSE*"))
            self.assertTrue(licences, f"{factory['id']}: {factory['license']} text is not kept")

    def test_attribution_names_every_factory_and_both_bases(self) -> None:
        text = (SEALED / "ATTRIBUTION.md").read_text(encoding="utf-8")
        for factory in seal()["factories"]:
            self.assertIn(f"[{factory['full_name']}]({factory['url']})", text)
        self.assertIn("content redistributed", text)
        self.assertIn("blueprint only", text)

    def test_the_component_runs_before_the_current_factories(self) -> None:
        position = seal()["pipeline_position"]
        self.assertEqual(position["order"], 0)
        for factory in ("factory-1-intake", "factory-2-debug-agents", "factory-3-android-tools",
                        "factory-4-release"):
            self.assertIn(factory, position["runs_before"])
        self.assertEqual(position["feeds_into"], "conveyor-belt")

    def test_the_runtime_accounts_for_every_sealed_factory(self) -> None:
        report = runtime_report()
        self.assertEqual(report["status"], "RUN")
        self.assertEqual(len(report["factories"]), seal()["contents"]["factories"])
        summary = report["summary"]
        self.assertEqual(summary["unreadable"], 0)
        self.assertEqual(summary["no_structure"] + summary["operational_plan"]
                         + summary["operational_model"] + summary["model_failed"],
                         len(report["factories"]))
        self.assertEqual(report["capabilities"]["model_available"],
                         bool(report["capabilities"]["local_models"]
                              or report["capabilities"]["keyed_providers"]))
        for entry in report["factories"]:
            self.assertNotIn(entry["status"], ("", "OK", "PASS"), "statuses must name what happened")
            if entry["status"] == "MODEL-FAILED":
                self.assertTrue(entry.get("error"), f"{entry['id']}: failure without a reason")

    def test_the_factories_that_cannot_run_are_exactly_the_ones_whose_content_is_not_carried(
            self) -> None:
        """NO-STRUCTURE and blueprint-only are the same set: a slice with content always runs."""
        report = runtime_report()
        if report["capabilities"]["model_available"]:
            self.skipTest("this machine has a model, so blueprint-only slices may still be called")
        no_structure = {e["id"] for e in report["factories"] if e["status"] == "NO-STRUCTURE"}
        blueprint_only = {f["id"] for f in seal()["factories"] if not f["content_redistributed"]}
        self.assertEqual(no_structure, blueprint_only,
                         "every factory whose content this component carries must run")
        self.assertEqual(len(report["factories"]) - len(no_structure),
                         report["summary"]["operational_plan"])

    def test_a_factory_is_never_reported_as_run_when_its_slice_holds_nothing(self) -> None:
        for entry in runtime_report()["factories"]:
            has_definitions = any(entry.get(kind) > 0
                                  for kind in ("stages", "roles", "gates", "prompts", "headings"))
            if not has_definitions:
                self.assertEqual(entry["status"], "NO-STRUCTURE",
                                 f"{entry['id']}: reported {entry['status']} without definitions")

    def test_no_model_output_is_ever_invented(self) -> None:
        offline = {"local_models": [], "keyed_providers": [], "model_available": False}
        answer = sealed_factory_runtime.call_model(offline, "prompt", timeout=1)
        self.assertFalse(answer["ok"])
        self.assertIsNone(answer["provider"])
        self.assertIn("no model is reachable", answer["error"])
        self.assertEqual(sealed_factory_runtime._extract_text({}), "")
        self.assertEqual(sealed_factory_runtime._extract_text({"choices": []}), "")

    def test_requiring_a_model_fails_when_nothing_is_reachable(self) -> None:
        done = subprocess.run([sys.executable, str(RUNTIME), "run", "--require-model"],
                              capture_output=True, text=True, cwd=REPO_ROOT, timeout=900)
        reachable = sealed_factory_runtime.detect_capabilities()["model_available"]
        if reachable:
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        else:
            self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
            self.assertIn("--require-model", done.stderr)

    def test_a_tampered_payload_stops_the_run(self) -> None:
        with tempfile.TemporaryDirectory() as scratch:
            copy = Path(scratch) / "sealed-factories"
            shutil.copytree(SEALED, copy)
            target = copy / seal()["factories"][0]["payload"]
            target.write_bytes(target.read_bytes() + b"tampered")
            environment = dict(**__import__("os").environ)
            done = subprocess.run([sys.executable, "-c", (
                "import json,sys;sys.path.insert(0, r'%s');import seal_factories;"
                "seal_factories.SEALED=__import__('pathlib').Path(r'%s');"
                "seal_factories.SEAL_FILE=seal_factories.SEALED/'SEAL.json';"
                "sys.exit(seal_factories.verify())" % (REPO_ROOT / "tools" / "factory", copy))],
                capture_output=True, text=True, cwd=REPO_ROOT, timeout=600, env=environment)
            self.assertEqual(done.returncode, 1, done.stdout + done.stderr)


class OnlineModelPathTest(unittest.TestCase):
    """The online path, exercised against a local server that speaks the same API.

    This proves the wiring - detection, request, response, artefact on disk - and nothing more: the
    server is a stub, so the test asserts the recorded provider and that the artefact equals exactly
    what the server answered, which is precisely the honesty this component is supposed to have.
    """

    ANSWER = "STUB MODEL ANSWER: stage 1 intake, stage 2 verify, no result was computed."

    @staticmethod
    def _handler_for(kind: str):
        import http.server

        answer = OnlineModelPathTest.ANSWER

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - the interface of BaseHTTPRequestHandler
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length) or b"{}")
                assert payload.get("messages") or payload.get("prompt")
                if kind == "ollama":
                    body = {"response": answer}
                else:
                    body = {"choices": [{"message": {"role": "assistant", "content": answer}}]}
                encoded = json.dumps(body).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def log_message(self, *args):  # keep the test output clean
                return

        return Handler

    def test_a_reachable_local_model_makes_a_factory_run_against_it(self) -> None:
        import http.server
        import threading

        candidate = None
        for name, host, port, _url in sealed_factory_runtime.LOCAL_ENDPOINTS:
            try:
                server = http.server.ThreadingHTTPServer((host, port), self._handler_for(name))
            except OSError:
                continue
            candidate = (name, server)
            break
        if candidate is None:
            self.skipTest("every local model port is already taken on this machine")

        name, server = candidate
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            report = subprocess.run([sys.executable, str(RUNTIME), "run", "--max-model-calls", "1"],
                                    capture_output=True, text=True, cwd=REPO_ROOT, timeout=900)
            self.assertEqual(report.returncode, 0, report.stdout + report.stderr)
            run_report = json.loads((SEALED / "manifest" / "RUN-REPORT.json")
                                    .read_text(encoding="utf-8"))
            self.assertTrue(run_report["capabilities"]["model_available"])
            self.assertEqual(run_report["model_calls"], 1)
            answered = [entry for entry in run_report["factories"]
                        if entry["status"] == "OPERATIONAL-MODEL"]
            self.assertEqual(len(answered), 1, "exactly the factories that were called must report it")
            entry = answered[0]
            self.assertEqual(entry["provider"], name)
            artefact = REPO_ROOT / entry["artifacts"][-1]
            self.assertEqual(artefact.read_text(encoding="utf-8"), self.ANSWER,
                             "the artefact must be what the model answered, byte for byte")
            others = [e for e in run_report["factories"] if e["id"] != entry["id"]]
            for other in others:
                self.assertNotEqual(other["status"], "OPERATIONAL-MODEL")
                self.assertNotIn("model-output.md", " ".join(other.get("artifacts", [])))
        finally:
            server.shutdown()
            server.server_close()

    def test_a_refusing_provider_is_recorded_as_a_failure_not_a_success(self) -> None:
        import http.server
        import threading

        class Refusing(http.server.BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - the interface of BaseHTTPRequestHandler
                body = b'{"error": {"message": "quota exhausted"}}'
                self.send_response(429)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                return

        candidate = None
        for name, host, port, _url in sealed_factory_runtime.LOCAL_ENDPOINTS:
            try:
                server = http.server.ThreadingHTTPServer((host, port), Refusing)
            except OSError:
                continue
            candidate = (name, server)
            break
        if candidate is None:
            self.skipTest("every local model port is already taken on this machine")

        _name, server = candidate
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            done = subprocess.run([sys.executable, str(RUNTIME), "run", "--max-model-calls", "1"],
                                  capture_output=True, text=True, cwd=REPO_ROOT, timeout=900)
            self.assertEqual(done.returncode, 0, "a refused call is reported, not fatal")
            run_report = json.loads((SEALED / "manifest" / "RUN-REPORT.json")
                                    .read_text(encoding="utf-8"))
            failed = [e for e in run_report["factories"] if e["status"] == "MODEL-FAILED"]
            self.assertEqual(len(failed), 1)
            self.assertIn("429", failed[0]["error"])
            self.assertIn("quota exhausted", failed[0]["error"],
                          "the provider's own words must reach the report")
            self.assertEqual(run_report["summary"]["operational_model"], 0)
        finally:
            server.shutdown()
            server.server_close()


class RemovabilityTest(unittest.TestCase):
    def test_deleting_the_component_leaves_a_working_repository(self) -> None:
        """The whole component is one directory. Without it the runtime says so and exits zero."""
        with tempfile.TemporaryDirectory() as scratch:
            tree = Path(scratch)
            (tree / "tools" / "factory").mkdir(parents=True)
            for tool in ("sealed_factory_runtime.py", "seal_factories.py"):
                shutil.copy(REPO_ROOT / "tools" / "factory" / tool, tree / "tools" / "factory" / tool)
            done = subprocess.run([sys.executable, str(tree / "tools" / "factory" /
                                                      "sealed_factory_runtime.py"), "run"],
                                  capture_output=True, text=True, cwd=tree, timeout=600)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertIn("ABSENT", done.stdout)
            self.assertFalse((tree / "sealed-factories").exists(),
                             "the absent path must not recreate the component")

    def test_the_component_owns_no_file_outside_its_directory(self) -> None:
        """Nothing under shell/, factories/, factory-2/ or factory-3/ depends on the sealed set."""
        removal = seal()["removal"]
        owned = {(REPO_ROOT / p).resolve() for p in removal["also_remove"]}
        owned |= {(REPO_ROOT / p).resolve() for p in removal["tests"]}
        owned |= {(REPO_ROOT / p).resolve() for p in removal["docs"]}
        self.assertIn(Path(__file__).resolve(), owned,
                      "this test file is part of the component and must be listed for removal")
        offenders = []
        for tree in ("shell", "factories", "factory-2", "factory-3"):
            for path in (REPO_ROOT / tree).rglob("*"):
                if not path.is_file() or path.suffix not in (".py", ".js", ".sh", ".kt", ".java",
                                                             ".xml", ".gradle", ".json"):
                    continue
                if path.resolve() in owned:
                    continue
                if "sealed-factories" in path.read_text(encoding="utf-8", errors="ignore"):
                    offenders.append(str(path.relative_to(REPO_ROOT)))
        self.assertEqual(offenders, [], "these files would break if the component were removed")

    def test_the_belt_runs_the_sealed_set_first_and_survives_its_absence(self) -> None:
        source = BELT.read_text(encoding="utf-8")
        # The code blocks, not the file header: `// Stage 0` runs before `// Stage 1` in the belt.
        station_zero = source.index("// Stage 0: The sealed factory set")
        station_one = source.index("// Stage 1: Sanitation & Hygiene")
        self.assertLess(station_zero, source.index("runStage(0,"))
        self.assertLess(source.index("runStage(0,"), source.index("runStage(1,"))
        self.assertLess(station_zero, station_one, "the sealed set must run before station 1")
        self.assertIn("sealed component ABSENT", source)
        self.assertIn("sealed_factory_runtime.py verify", source)
        self.assertIn("sealed_factory_runtime.py run", source)
        banner = source[source.rindex("const banner"):]
        self.assertIn("${sealedState}", banner, "the banner must state what actually happened")
        self.assertIn("ALL 6 CONNECTED STAGES PASSED", banner)
        self.assertIn('"SEALED SET ABSENT"', source)
        self.assertIn('"SEALED SET RAN"', source)

    def test_the_removal_instructions_match_the_files_that_exist(self) -> None:
        removal = seal()["removal"]
        self.assertFalse((REPO_ROOT / removal["delete"].rstrip("/")).is_dir() is False,
                         "the sealed component must exist while it is sealed")
        for group in ("also_remove", "tests", "docs"):
            for path in removal[group]:
                self.assertTrue((REPO_ROOT / path).is_file(),
                                f"{path} is named for removal but missing")
        for keep in removal["leaves_intact"]:
            self.assertTrue((REPO_ROOT / keep).exists(), f"{keep} must survive removal")
        self.assertIn(removal["belt_station"], "the Stage 0 block in scripts/conveyor_belt.js, which "
                      "reports ABSENT without it")


if __name__ == "__main__":
    unittest.main()
