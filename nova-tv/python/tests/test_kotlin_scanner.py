"""Comment stripping must not treat `//` inside a string as a comment.

The old regex pass removed `//...` first, so `"https://a.example"` cut off the
rest of its line, the closing quote vanished, and every later quote paired with
the wrong partner. kotlin_lint then reported compiled, working files as having
unbalanced braces and unused imports, and appaudit/buttons lost code after any
URL literal.
"""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("kotlin_lint", ROOT / "ops" / "kotlin_lint.py")
lint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lint)

from nova import appaudit  # noqa: E402

SRC = '''import java.net.URLEncoder
val a = Stream(url = "https://a.example/1.m3u8") // trailing { comment
val q = URLEncoder.encode("x", "UTF-8")
val r = Regex("""^\\d{1,3}(\\.\\d{1,3}){3}$""")
val t = "code ${resp.code} for $url"
val c = '{'
/* outer /* nested { */ still comment } */
fun f() { }
'''


class ScannerTest(unittest.TestCase):
    def test_url_in_string_is_not_a_comment(self):
        code = lint.strip_comments_and_strings(SRC)
        self.assertIn("URLEncoder.encode", code)
        self.assertEqual(code.count("{"), code.count("}"), code)

    def test_template_identifiers_survive(self):
        self.assertIn("resp.code", lint.strip_comments_and_strings(SRC))

    def test_comments_are_removed(self):
        code = lint.strip_comments_and_strings(SRC)
        self.assertNotIn("trailing", code)
        self.assertNotIn("still comment", code)

    def test_real_imbalance_still_detected(self):
        code = lint.strip_comments_and_strings('fun f() { val s = "}" \n')
        self.assertNotEqual(code.count("{"), code.count("}"))

    def test_stray_escaped_quote_is_reported_outside_strings(self):
        self.assertEqual(lint.stray_escaped_quote_lines('val ok = "\\\"quoted\\\""\n'), [])
        self.assertEqual(lint.stray_escaped_quote_lines('val broken = foo(\\")\n'), [1])
        self.assertEqual(lint.stray_escaped_quote_lines('// \\" in a comment\n'), [])

    def test_appaudit_keeps_strings_drops_comments(self):
        out = appaudit._strip_comments(SRC)
        self.assertIn('"https://a.example/1.m3u8"', out)
        self.assertIn("URLEncoder.encode", out)
        self.assertNotIn("trailing", out)
        self.assertNotIn("still comment", out)


if __name__ == "__main__":
    unittest.main()
