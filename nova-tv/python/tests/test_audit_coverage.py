"""The audit must fail when this turn's fixes are broken.

`qa_translated.py` reported 170 proven / 0 contradicted while covering *none*
of the work done to the player, the seek bar, the cookie jar or the harvest
loops -- a green that meant "not looked at", not "correct". These tests pin the
checks that closed that gap, and they pin them in the direction that matters:
each fix is applied to a temporary copy of the tree, broken, and the audit is
required to notice.

If a future edit weakens one of these checks into a token search (the failure
mode found while writing them: `.cookieJar(jar)` matched the KDoc that explains
the bug, and `detectTapGestures` matched the comment that removed it), the
matching test here goes red.
"""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

# tests/ -> python/ -> nova-tv/ (which is the audit's own root).
PYTHON_DIR = Path(__file__).resolve().parent.parent
NOVA = PYTHON_DIR.parent
sys.path.insert(0, str(PYTHON_DIR))

from nova import translated_check  # noqa: E402

TV = Path("android/app/src/main/java/dev/nova/tv")
MOBILE = Path("android/app/src/mobile/java/dev/nova/tv/mobile")


def contradictions(root: Path) -> list[str]:
    """Names of every contradicted check for the tree at [root]."""
    checks = translated_check.run_all(root)
    return [c.name for c in checks if c.verdict == "contradicted"]


class AuditCoversThisTurnsWork(unittest.TestCase):
    """The real tree is green, and the checks exist at all."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.checks = translated_check.run_all(NOVA)
        cls.names = {c.name for c in cls.checks}

    def test_audit_is_clean_on_the_real_tree(self) -> None:
        bad = [c.name for c in self.checks if c.verdict == "contradicted"]
        self.assertEqual([], bad, f"real tree has contradictions: {bad}")

    def test_the_checks_this_turn_added_are_present(self) -> None:
        # A check that quietly disappears is the same false green as one that
        # never existed, so the names are asserted, not just the count.
        required = {
            "imported player engine is present and attributed",
            "third-party notice names the imported player",
            "intro timestamps come from a real keyless source",
            "intro window is never guessed from the media",
            "a skip is only offered for usable data",
            "skip-intro is wired on both surfaces",
            "mobile seek bar uses one gesture loop",
            "absolute seek exists for a scrub bar",
            "seeks stop short of the end",
            "TV scrub bar handles the D-pad",
            "finder keeps a session cookie jar",
            "cookie jar is declared before the client it feeds",
            "server harvest keeps crawling instead of one batch",
            "addon harvest keeps crawling instead of one batch",
            "website pool receives the site, not the media origin",
            "FinderBrowser renders through a browser engine",
            "finder browser really renders pages",
            "finder browser is given a context to render in",
            "finder browser serialises concurrent renders",
            "server harvest stops when a frontier round finds nothing",
            "addon harvest stops when a frontier round finds nothing",
            "website harvest reports why a round found nothing",
            "website rejection report reaches the engine",
            "Sources screen can show why the website pool is empty",
            "finder flight recorder exists and can be dumped",
            "website crawl records render, fetch, probe and extract",
            "addon crawl counts every drop reason",
            "browser records whether it rendered or had no context",
            "diagnostics screen shows the trace and can copy it",
        }
        missing = required - self.names
        self.assertEqual(set(), missing, f"audit lost checks: {sorted(missing)}")

    def test_audit_covers_more_than_files_and_wiring(self) -> None:
        # The original 170 were almost entirely "file exists / symbol is
        # referenced". This is the floor that stops the report drifting back to
        # a file listing.
        self.assertGreaterEqual(len(self.checks), 198)


class AuditCatchesBrokenFixes(unittest.TestCase):
    """Break each fix in a scratch copy; the audit must go red."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.tree = self.tmp / "nova-tv"
        shutil.copytree(NOVA, self.tree, ignore=shutil.ignore_patterns("__pycache__"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _mutate(self, relative: Path, old: str, new: str, *, all_occurrences: bool = False) -> None:
        path = self.tree / relative
        text = path.read_text()
        self.assertIn(old, text, f"mutation anchor missing in {relative}: {old!r}")
        count = -1 if all_occurrences else 1
        path.write_text(text.replace(old, new, count))

    def _assert_caught(self, expected: str) -> None:
        found = contradictions(self.tree)
        self.assertIn(expected, found, f"audit stayed green; contradictions were {found}")

    # -- the website finder -------------------------------------------------

    def test_cookie_jar_removal_is_caught(self) -> None:
        # The whole "returns nothing" fix. Note the KDoc above the jar still
        # says `.cookieJar(jar)` after this deletion, which is what made an
        # earlier version of this check pass on broken code.
        self._mutate(
            TV / "finder/translated/FinderHttp.kt",
            "        .cookieJar(jar)\n",
            "",
        )
        self._assert_caught("finder keeps a session cookie jar")

    def test_single_batch_harvest_is_caught_for_both_finders(self) -> None:
        self._mutate(
            TV / "finder/servers/ServerFinder.kt",
            "while (System.currentTimeMillis() - started < harvestBudgetMs) {",
            "if (true) {",
        )
        self._assert_caught("server harvest keeps crawling instead of one batch")

        self._mutate(
            TV / "finder/addons/AddonFinder.kt",
            "while (System.currentTimeMillis() - started < harvestBudgetMs) {",
            "if (true) {",
        )
        self._assert_caught("addon harvest keeps crawling instead of one batch")

    def test_feeding_media_origins_to_the_website_pool_is_caught(self) -> None:
        self._mutate(
            TV / "finder/servers/ServerFinder.kt",
            "return fromRef.ifEmpty { baseUrl }",
            "return baseUrl",
        )
        self._assert_caught("website pool receives the site, not the media origin")

    # -- seeking ------------------------------------------------------------

    def test_rival_gesture_detector_is_caught(self) -> None:
        # Two detectors on one pointer stream is the original bug.
        self._mutate(
            MOBILE / "MobilePlayer.kt",
            "awaitEachGesture {",
            "awaitEachGesture {\n"
            "                        detectHorizontalDragGestures { _, _ -> }",
        )
        self._assert_caught("mobile seek bar uses one gesture loop")

    def test_drag_that_stops_tracking_the_finger_is_caught(self) -> None:
        self._mutate(
            MOBILE / "MobilePlayer.kt",
            "                            x = change.position.x\n"
            "                            dragFraction = (x / widthPx).coerceIn(0f, 1f)",
            "                            x = change.position.x",
        )
        self._assert_caught("mobile seek bar uses one gesture loop")

    def test_removing_the_tv_dpad_keys_is_caught(self) -> None:
        self._mutate(
            TV / "ui/player/PlayerSurface.kt",
            "AndroidKeyEvent.KEYCODE_DPAD_LEFT",
            "AndroidKeyEvent.KEYCODE_0",
            all_occurrences=True,
        )
        self._mutate(
            TV / "ui/player/PlayerSurface.kt",
            "AndroidKeyEvent.KEYCODE_DPAD_RIGHT",
            "AndroidKeyEvent.KEYCODE_1",
            all_occurrences=True,
        )
        self._assert_caught("TV scrub bar handles the D-pad")

    # -- skip intro ---------------------------------------------------------

    def test_invented_intro_data_is_caught(self) -> None:
        # Replacing both real endpoints with a placeholder is what "made up the
        # timestamps" would look like. All occurrences, because the file's KDoc
        # also names them.
        self._mutate(
            TV / "player/IntroSkip.kt",
            "theintrodb.org",
            "invalid.test",
            all_occurrences=True,
        )
        self._assert_caught("intro timestamps come from a real keyless source")

    def test_zeroed_confidence_floor_is_caught(self) -> None:
        self._mutate(
            TV / "player/IntroSkip.kt",
            "const val MIN_CONFIDENCE = 0.5",
            "const val MIN_CONFIDENCE = 0.0",
        )
        self._assert_caught("a skip is only offered for usable data")

    def test_removing_the_start_guard_is_caught(self) -> None:
        self._mutate(
            TV / "player/NovaPlayer.kt",
            "position >= IntroLookup.INTRO_START_GUARD_MS",
            "position >= 0L",
        )
        self._assert_caught("a skip is only offered for usable data")

    def test_never_installing_the_window_is_caught(self) -> None:
        self._mutate(
            TV / "ui/screens/NovaPlayerHost.kt",
            "player.setIntroSegment(segment)",
            "player.setIntroSegment(null)",
        )
        self._assert_caught("skip-intro is wired on both surfaces")

    # -- the imported player ------------------------------------------------

    def test_stripping_the_import_attribution_is_caught(self) -> None:
        self._mutate(
            TV / "player/engine/exoplayer/ExoPlayerEngine.kt",
            "// Imported from ARVIO (https://github.com/ProdigyV21/ARVIO, commit 31ce7d7),",
            "// local file",
        )
        self._assert_caught("imported player engine is present and attributed")


    # -- the browser that renders, and the frontier that stops ---------------
    def test_audit_catches_a_stubbed_browser(self) -> None:
        # The exact defect the user reported: render() returning nothing while
        # every other check stayed green.
        self._mutate(
            TV / "finder/translated/FinderBrowser.kt",
            "val html = load(url, referer, waitMs.toLong(), net)",
            'val html = ""',
        )
        self._assert_caught("finder browser really renders pages")

    def test_audit_catches_a_browser_with_no_context(self) -> None:
        # No context means `available` is false, so every render degrades to "".
        self._mutate(TV / "NovaApplication.kt", "FinderBrowser(this)", "FinderBrowser()")
        self._assert_caught("finder browser is given a context to render in")

    def test_audit_catches_ungated_renders(self) -> None:
        self._mutate(TV / "finder/translated/FinderBrowser.kt", "gate.withLock {", "run {")
        self._assert_caught("finder browser serialises concurrent renders")

    def test_audit_catches_a_spinning_server_frontier(self) -> None:
        self._mutate(
            TV / "finder/servers/ServerFinder.kt",
            "store.pop(64).filter { visited.add(it) }",
            "store.pop(64)",
        )
        self._assert_caught("server harvest stops when a frontier round finds nothing")

    def test_audit_catches_a_missing_barren_stop(self) -> None:
        self._mutate(
            TV / "finder/addons/AddonFinder.kt",
            "if (barren >= BARREN_ROUNDS) break",
            "",
            all_occurrences=True,
        )
        self._assert_caught("addon harvest stops when a frontier round finds nothing")


    # -- a zero must be explainable ------------------------------------------
    def test_audit_catches_a_silent_website_round(self) -> None:
        # The whole reason three fixes were guesses: an empty round that says
        # nothing. Removing one reason must be noticed.
        self._mutate(
            TV / "finder/servers/ServerFinder.kt",
            'note("unreachable")',
            '/* removed */',
        )
        self._assert_caught("website harvest reports why a round found nothing")

    def test_audit_catches_the_reason_dropped_by_the_engine(self) -> None:
        self._mutate(
            TV / "finder/SourceEngine.kt",
            "lastServerRejections = runCatching { crawl.rejectionReport() }.getOrNull() ?: lastServerRejections",
            "/* reason discarded */",
        )
        self._assert_caught("website rejection report reaches the engine")

    def test_audit_catches_a_toast_only_diagnosis(self) -> None:
        self._mutate(
            TV / "ui/screens/NovaViewModel.kt",
            "val siteRejections: String = \"\",",
            "",
        )
        self._assert_caught("Sources screen can show why the website pool is empty")


    # -- the flight recorder --------------------------------------------------
    def test_audit_catches_a_missing_recorder(self) -> None:
        self._mutate(
            TV / "finder/diag/FinderTrace.kt",
            "fun dump(): String",
            "fun dumpDisabled(): String",
        )
        self._assert_caught("finder flight recorder exists and can be dumped")

    def test_audit_catches_a_silent_website_crawl(self) -> None:
        # The exact failure mode that cost three rounds: a crawl that runs and
        # says nothing about what it saw.
        self._mutate(
            TV / "finder/servers/ServerFinder.kt",
            '"extract $url -> ${extracted.size} candidate(s)"',
            '"extract"',
        )
        self._assert_caught("website crawl records render, fetch, probe and extract")

    def test_audit_catches_untraced_addon_drops(self) -> None:
        self._mutate(
            TV / "finder/addons/AddonFinder.kt",
            'FinderTrace.count("addon.drop.$key")',
            "/* not traced */",
        )
        self._assert_caught("addon crawl counts every drop reason")

    def test_audit_catches_a_silent_browser(self) -> None:
        self._mutate(
            TV / "finder/translated/FinderBrowser.kt",
            'FinderTrace.count("browser.created")',
            "/* silent */",
        )
        self._assert_caught("browser records whether it rendered or had no context")

    def test_audit_catches_an_unreachable_trace(self) -> None:
        # A recorder nobody can read off the device is worth nothing.
        self._mutate(
            Path("android/app/src/mobile/java/dev/nova/tv/mobile/MobileDiagnostics.kt"),
            "copyToClipboard(context, dump)",
            "/* no clipboard */",
        )
        self._assert_caught("diagnostics screen shows the trace and can copy it")


if __name__ == "__main__":
    unittest.main()
