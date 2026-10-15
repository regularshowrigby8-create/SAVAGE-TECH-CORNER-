"""Whether a bad certificate can ever be accepted, checked where that decision is written.

The instrumented `SslPolicyTest` can hand the real `SavageWebViewClient` a real `SslError`, and it
does - but it cannot observe the framework `SslErrorHandler` call itself: `SslErrorHandler` has no
public constructor in the SDK, so an instrumented test can never subclass it and record what the
application did with it. That is why the fail-closed claim is pinned in two places that can be
checked exhaustively:

* `SslDecisionTest` (JVM) proves every framework error code resolves to CANCEL, and
* this check proves no application source can ever call the accept path, so there is no code left
  that could disagree with the decision.

Together they cover the requirement the instrumented test can only describe.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP_SOURCES = ROOT / "shell" / "android_project" / "app" / "src" / "main" / "java"

# The accept path of android.webkit.SslErrorHandler. Application code that contains it could accept
# a certificate the framework rejected; the browser must never contain it.
FORBIDDEN_CALL = re.compile(r"\bproceed\s*\(")
WEBVIEW_CLIENT = APP_SOURCES / "com" / "savage" / "soulbrowser" / "browser" / "SavageWebViewClient.java"
SSL_DECISION = APP_SOURCES / "com" / "savage" / "soulbrowser" / "browser" / "SslDecision.java"


def application_sources() -> list[Path]:
    return sorted(APP_SOURCES.rglob("*.java"))


class FailClosedSourceTest(unittest.TestCase):
    def test_the_application_sources_exist(self) -> None:
        sources = application_sources()
        self.assertGreater(len(sources), 20, "the Android sources must be where this check looks")

    def test_no_application_source_can_accept_a_rejected_certificate(self) -> None:
        offenders = []
        for source in application_sources():
            if FORBIDDEN_CALL.search(source.read_text(encoding="utf-8")):
                offenders.append(str(source.relative_to(ROOT)))
        self.assertEqual(
            [],
            offenders,
            "these sources call the SSL accept path, so a rejected certificate could be accepted",
        )

    def test_the_webview_client_cancels_and_reports(self) -> None:
        source = WEBVIEW_CLIENT.read_text(encoding="utf-8")
        self.assertIn("handler.cancel()", source)
        self.assertIn("host.onTlsError(", source)
        # The cancel must not depend on anything but the decision: no "unless this host" escape.
        self.assertNotIn("isTrusted", source)

    def test_the_decision_type_has_no_accept_outcome(self) -> None:
        source = SSL_DECISION.read_text(encoding="utf-8")
        self.assertNotIn("PROCEED", source, "an accept outcome would contradict the fail-closed rule")

    def test_the_instrumented_test_does_not_claim_an_unobservable_call(self) -> None:
        """The emulator test must not claim it observed the handler; it cannot."""
        source = (
            ROOT
            / "shell"
            / "android_project"
            / "app"
            / "src"
            / "androidTest"
            / "java"
            / "com"
            / "savage"
            / "soulbrowser"
            / "SslPolicyTest.java"
        ).read_text(encoding="utf-8")
        self.assertNotIn("extends SslErrorHandler", source)
        self.assertIn(
            "shell/tests/test_ssl_fail_closed.py",
            source,
            "the test must name the check that covers the part it cannot observe",
        )


if __name__ == "__main__":
    unittest.main()
