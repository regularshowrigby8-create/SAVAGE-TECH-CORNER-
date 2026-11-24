"""The structural lint must catch the type errors CI actually reported.

Every case below is a real compile failure from run 45238681629 (or the shape
it would have produced), where `kotlin_lint.py` said "no structural problems
found" and the Gradle build then failed. The lint is not a compiler, but it
must not be a false green either: these shapes are checked, and the shapes it
must *not* flag are checked too, because a lint that cries wolf gets ignored.
"""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("kotlin_lint_shapes", ROOT / "ops" / "kotlin_lint.py")
lint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lint)

FILE = Path("Sample.kt")


def problems(code: str) -> list[str]:
    return lint.check_compile_shapes(FILE, code, code)


class DelayTest(unittest.TestCase):
    def test_nested_double_delay_is_flagged(self):
        # ServerFinder/FinderHttp, verbatim from the failing build.
        self.assertTrue(problems("delay(((2.0.pow(attempt)) + Random.nextDouble()) * 1000L)"))
        self.assertTrue(problems("delay(((2.0.pow(attempt)) * 0.8 + Random.nextDouble()) * 1000L)"))

    def test_flat_double_delay_is_flagged(self):
        self.assertTrue(problems("delay((2.0 * 3) * 1000L)"))

    def test_long_delays_are_not_flagged(self):
        self.assertEqual([], problems("delay(ra.toLong() * 1000L)"))
        self.assertEqual([], problems("delay((((2.0.pow(a)) + r) * 1000.0).toLong())"))
        self.assertEqual([], problems("delay(500L)"))

    def test_line_number_is_reported(self):
        found = lint.double_delay_arguments("val a = 1\ndelay((2.0 * 3) * 1000L)")
        self.assertEqual(2, found[0][0])


class StringCountTest(unittest.TestCase):
    def test_string_receiver_count_is_flagged(self):
        self.assertTrue(problems('val hits = r.text.count("animeId")'))
        self.assertTrue(problems('val hits = body.count("x")'))

    def test_store_count_is_not_flagged(self):
        # PoolStore really declares count(status: String? = null): a valid call,
        # and flagging it was 7 false positives on working code.
        self.assertEqual([], problems('store.count("alive") >= cap'))
        self.assertEqual([], problems('val n = store.count("dead") / 20'))


class DecodeTest(unittest.TestCase):
    def test_opt_in_decode_is_flagged(self):
        self.assertTrue(problems("val s = body.decodeToString(Charsets.UTF_8, ignoreMalformed = true)"))

    def test_plain_decode_is_not_flagged(self):
        self.assertEqual([], problems("val s = String(body, Charsets.UTF_8)"))
        self.assertEqual([], problems("val s = body.decodeToString()"))


class NullableLambdaTest(unittest.TestCase):
    def test_guard_then_lambda_use_is_flagged(self):
        # FinderHttp.solve, verbatim.
        code = '''if (browser == null) return
        runCatching { browser.mint(url, referer) }'''
        self.assertTrue(problems(code))

    def test_bound_local_is_not_flagged(self):
        code = '''val page = browser ?: return
        runCatching { page.mint(url, referer) }'''
        self.assertEqual([], problems(code))


class CurrentTreeTest(unittest.TestCase):
    def test_the_repository_has_no_flagged_shapes(self):
        src = ROOT / "android" / "app" / "src"
        offenders: list[str] = []
        for path in sorted(src.rglob("*.kt")):
            code = lint.strip_comments_and_strings(path.read_text("utf-8"))
            offenders.extend(lint.check_compile_shapes(path, "", code))
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
