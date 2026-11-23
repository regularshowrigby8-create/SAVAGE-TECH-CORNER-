"""The checkers must catch real problems and stay quiet on honest documents."""
from __future__ import annotations

import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from nova import buttons, hallucination, zerokey


# --- anti-hallucination ------------------------------------------------------

class ClaimExtractionTest(unittest.TestCase):
    def test_ignores_paths_that_only_look_like_repos(self):
        # `github/workflows` and `docs/api` are paths. An earlier version asked
        # GitHub about them and reported 51 false contradictions.
        text = "The workflow is `.github/workflows/x.yml` and docs live in `docs/api`."
        claims = hallucination.extract_claims(text)
        self.assertEqual([c for c in claims if c.text.startswith("repo")], [])

    def test_ignores_fenced_code(self):
        text = "```\nsee `someone/notarepo` here\n```\n"
        self.assertEqual(hallucination.extract_claims(text), [])

    def test_does_not_flag_a_documented_absence(self):
        # The plan says these do not exist; flagging them would punish honesty.
        text = (
            "Three candidates were checked and **do not exist**:\n"
            "`apksig/apksig`, `google/android-emulator`.\n"
        )
        claims = [c for c in hallucination.extract_claims(text) if c.text.startswith("repo")]
        self.assertEqual(claims, [])

    def test_repo_in_a_url_is_a_claim(self):
        text = "Imported from https://github.com/chrisbanes/tivi today."
        claims = [c for c in hallucination.extract_claims(text) if c.text.startswith("repo")]
        self.assertEqual(len(claims), 1)
        self.assertIn("chrisbanes/tivi", claims[0].text)

    def test_upstream_path_is_not_a_local_claim(self):
        # The repo reference IS a claim and must be checked; the file inside that
        # repo is not a claim about our filesystem.
        text = "**Import.** `Stremio/stremio-addon-sdk` ships `docs/protocol.md`."
        claims = hallucination.extract_claims(text)
        self.assertEqual([c.text for c in claims], ["repo `Stremio/stremio-addon-sdk` exists"])


class FileClaimTest(unittest.TestCase):
    def test_proves_an_existing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "nova-tv" / "python" / "nova").mkdir(parents=True)
            (root / "nova-tv" / "python" / "nova" / "mod.py").write_text("x")
            claim = hallucination.Claim("file `nova/mod.py` exists", "doc.md", 1)
            result = hallucination.check_file_claim(claim, root)
            self.assertEqual(result.verdict, "proven")

    def test_contradicts_a_missing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            claim = hallucination.Claim("file `nope/missing.py` exists", "doc.md", 1)
            result = hallucination.check_file_claim(claim, Path(tmp))
            self.assertEqual(result.verdict, "contradicted")


class RepoClaimTest(unittest.TestCase):
    class _Github:
        def __init__(self, exists):
            self.exists = exists

        def repo(self, name):
            if not self.exists:
                raise RuntimeError("HTTP 404")
            return type("R", (), {"full_name": name})()

    def test_proves_an_existing_repo(self):
        claim = hallucination.Claim("repo `a/b` exists", "d.md", 1)
        self.assertEqual(hallucination.check_repo_claim(claim, self._Github(True)).verdict, "proven")

    def test_contradicts_a_404(self):
        claim = hallucination.Claim("repo `a/b` exists", "d.md", 1)
        self.assertEqual(hallucination.check_repo_claim(claim, self._Github(False)).verdict, "contradicted")

    def test_no_client_means_unverified_not_proven(self):
        claim = hallucination.Claim("repo `a/b` exists", "d.md", 1)
        self.assertEqual(hallucination.check_repo_claim(claim, None).verdict, "unverified")


# --- dead buttons ------------------------------------------------------------

def _ui_project(root: Path, body: str) -> Path:
    target = root / "app" / "src" / "main" / "java" / "ui"
    target.mkdir(parents=True, exist_ok=True)
    (target / "Screen.kt").write_text("package ui\n" + body)
    return root


class DeadButtonTest(unittest.TestCase):
    def test_flags_an_empty_handler(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = _ui_project(Path(tmp), "fun A() { Surface(onClick = { }) { Text(\"x\") } }\n")
            kinds = [f.kind for f in buttons.audit(root)]
            self.assertIn("empty-handler", kinds)

    def test_flags_a_card_with_no_handler(self):
        # `Card { }` has no parentheses, which an earlier regex missed entirely.
        with tempfile.TemporaryDirectory() as tmp:
            root = _ui_project(Path(tmp), "fun A() { Card { Text(\"looks pressable\") } }\n")
            kinds = [f.kind for f in buttons.audit(root)]
            self.assertIn("no-handler", kinds)

    def test_does_not_flag_a_card_with_a_handler(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = _ui_project(Path(tmp), "fun A() { Card(onClick = go) { Text(\"ok\") } }\n")
            kinds = [f.kind for f in buttons.audit(root)]
            self.assertNotIn("no-handler", kinds)

    def test_flags_an_enum_with_no_branch(self):
        body = (
            "enum class Route(val label: String) { HOME(\"H\"), SETTINGS(\"S\") }\n"
            "fun Pane() {\n"
            "  var route by remember { mutableStateOf(Route.HOME) }\n"
            "  Box { Home() }\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = _ui_project(Path(tmp), body)
            found = [f for f in buttons.audit(root) if f.kind == "unrouted-entries"]
            self.assertEqual(len(found), 1)
            self.assertIn("HOME", found[0].message)

    def test_clean_screen_reports_nothing(self):
        body = (
            "fun Pane() { Card(onClick = { go() }) { Text(\"ok\") } }\n"
            "fun go() { start() }\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = _ui_project(Path(tmp), body)
            self.assertEqual(buttons.audit(root), [])


# --- four models -------------------------------------------------------------

class _FakeZeroKey(BaseHTTPRequestHandler):
    models = ["deepseek-chat", "claude-sonnet-4", "gpt-4o", "qwen-max"]
    verdicts = {"deepseek-chat": "supported", "claude-sonnet-4": "supported",
                "gpt-4o": "supported", "qwen-max": "supported"}

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({"data": [{"id": m} for m in self.models]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length))
        verdict = self.verdicts.get(payload.get("model"), "uncertain")
        content = json.dumps({"verdict": verdict, "reason": "ok", "evidence": "e"})
        body = json.dumps({"choices": [{"message": {"content": content}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class FourModelTest(unittest.TestCase):
    def setUp(self):
        self.server = HTTPServer(("127.0.0.1", 0), _FakeZeroKey)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.port}/v1"

    def tearDown(self):
        self.server.shutdown()

    def test_finds_all_four_providers(self):
        found = zerokey.status(self.base)["providers_found"]
        self.assertEqual(found, ["deepseek", "claude", "chatgpt", "qwen"])

    def test_unanimous_agreement(self):
        consensus = zerokey.ZeroKey(self.base).verify("claim", "evidence")
        decision, why = consensus.decision()
        self.assertEqual(decision, "supported")
        self.assertTrue(consensus.unanimous)
        self.assertIn("all 4", why)

    def test_disagreement_is_reported_not_averaged(self):
        _FakeZeroKey.verdicts["qwen-max"] = "unsupported"
        try:
            consensus = zerokey.ZeroKey(self.base).verify("claim", "evidence")
            decision, _ = consensus.decision()
            self.assertEqual(decision, "disputed")
            self.assertFalse(consensus.unanimous)
        finally:
            _FakeZeroKey.verdicts["qwen-max"] = "supported"

    def test_unreachable_is_reported_not_faked(self):
        status = zerokey.status("http://127.0.0.1:1/v1", timeout=0.2)
        self.assertFalse(status["reachable"])
        self.assertEqual(status["models"], [])


class ReviewUiTest(unittest.TestCase):
    """The review must never invent four opinions."""

    def test_unreachable_returns_no_reviews(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "review_ui", __file__.replace("tests/test_verify.py", "scripts/review_ui.py")
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        out = module.review_ui(base_url="http://127.0.0.1:1/v1")
        self.assertFalse(out["reachable"])
        self.assertEqual(out["reviews"], {})
        self.assertIn("not running", out["hint"])

    def test_reachable_collects_all_four(self):
        import importlib.util

        _FakeZeroKey.verdicts.update({k: "supported" for k in _FakeZeroKey.verdicts})
        server = HTTPServer(("127.0.0.1", 0), _FakeZeroKey)
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            spec = importlib.util.spec_from_file_location(
                "review_ui2", __file__.replace("tests/test_verify.py", "scripts/review_ui.py")
            )
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            out = module.review_ui(base_url=f"http://127.0.0.1:{port}/v1")
            self.assertTrue(out["reachable"])
            self.assertEqual(len(out["answered"]), 4)
        finally:
            server.shutdown()


class ParseVerdictTest(unittest.TestCase):
    def test_reads_json_wrapped_in_prose(self):
        verdict, reason, _ = zerokey.parse_verdict(
            'Sure. {"verdict": "unsupported", "reason": "no evidence", "evidence": ""}'
        )
        self.assertEqual(verdict, "unsupported")
        self.assertEqual(reason, "no evidence")

    def test_non_json_is_uncertain(self):
        self.assertEqual(zerokey.parse_verdict("I think it is fine")[0], "uncertain")

    def test_unknown_verdict_is_uncertain(self):
        self.assertEqual(zerokey.parse_verdict('{"verdict": "probably"}')[0], "uncertain")


if __name__ == "__main__":
    unittest.main()
