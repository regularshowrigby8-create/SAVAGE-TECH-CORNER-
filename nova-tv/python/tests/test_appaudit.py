"""The auditor must flag real defects and stay quiet on live code."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from nova import appaudit


def make_project(root: Path, files: dict[str, str]) -> Path:
    for relative, content in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    return root


KOTLIN = "app/src/main/java/dev/nova/tv"


class DeadTypeTest(unittest.TestCase):
    def test_flags_a_type_nothing_references(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {
                    f"{KOTLIN}/NovaPlayer.kt": "package dev.nova.tv\nclass NovaPlayer {\n}\n",
                    f"{KOTLIN}/Home.kt": "package dev.nova.tv\nclass Home {\n}\n",
                },
            )
            android = root
            names = {f.symbol for f in appaudit.find_dead_types(android, appaudit.collect(android))}
            self.assertIn("NovaPlayer", names)

    def test_does_not_flag_a_referenced_type(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {
                    f"{KOTLIN}/NovaPlayer.kt": "package dev.nova.tv\nclass NovaPlayer\n",
                    f"{KOTLIN}/Home.kt": "package dev.nova.tv\nfun start() { NovaPlayer() }\n",
                },
            )
            android = root
            names = {f.symbol for f in appaudit.find_dead_types(android, appaudit.collect(android))}
            self.assertNotIn("NovaPlayer", names)

    def test_manifest_reference_counts_as_use(self):
        # Activities are named only in the manifest, as ".ui.NovaActivity".
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {
                    f"{KOTLIN}/ui/NovaActivity.kt": "package dev.nova.tv.ui\nclass NovaActivity\n",
                    "app/src/main/AndroidManifest.xml":
                        '<activity android:name=".ui.NovaActivity" />',
                },
            )
            android = root
            names = {f.symbol for f in appaudit.find_dead_types(android, appaudit.collect(android))}
            self.assertNotIn("NovaActivity", names)

    def test_test_classes_are_not_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {"app/src/test/java/dev/nova/tv/ThingTest.kt": "package dev.nova.tv\nclass ThingTest\n"},
            )
            android = root
            self.assertEqual(appaudit.find_dead_types(android, appaudit.collect(android)), [])


class WriteOnlyStateTest(unittest.TestCase):
    def test_flags_state_that_is_only_assigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {
                    # Shaped like the real code: one property per line inside a
                    # state holder, assigned through copy().
                    f"{KOTLIN}/Home.kt": (
                        "package dev.nova.tv\n"
                        "data class State(\n"
                        "    val loading: Boolean = false,\n"
                        "    val opened: String? = null\n"
                        ")\n"
                        "fun set(s: State) = s.copy(opened = \"x\")\n"
                    ),
                },
            )
            android = root
            names = {
                f.symbol
                for f in appaudit.find_write_only_properties(appaudit.collect(android))
            }
            self.assertIn("opened", names)

    def test_does_not_flag_a_read_property(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(
                Path(tmp),
                {
                    f"{KOTLIN}/Home.kt": (
                        "package dev.nova.tv\n"
                        "data class State(val opened: String? = null)\n"
                        "fun show(s: State) = println(s.opened)\n"
                    ),
                },
            )
            android = root
            names = {
                f.symbol
                for f in appaudit.find_write_only_properties(appaudit.collect(android))
            }
            self.assertNotIn("opened", names)

    def test_interior_read_counts(self):
        # `val id get() = manifest.id` reads manifest from inside its own file.
        self_reading = (
            "package dev.nova.tv\n"
            "data class Addon(val manifest: M, val id: String get() = manifest.id)\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(Path(tmp), {f"{KOTLIN}/Addon.kt": self_reading})
            android = root
            names = {
                f.symbol
                for f in appaudit.find_write_only_properties(appaudit.collect(android))
            }
            self.assertNotIn("manifest", names)


class DeadRouteTest(unittest.TestCase):
    def test_flags_a_route_that_never_selects_content(self):
        screen = (
            "package dev.nova.tv.ui\n"
            "var route by remember { mutableStateOf(Route.HOME) }\n"
            "Rail(current = route) { route = it }\n"
            "Box { when { loading -> Spinner() else -> Home() } }\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(Path(tmp), {f"{KOTLIN}/ui/NovaApp.kt": screen})
            found = appaudit.find_unrouted_screens(root, appaudit.collect(root))
            self.assertEqual([f.symbol for f in found], ["route"])
            self.assertEqual(found[0].severity, appaudit.CRITICAL)

    def test_does_not_flag_a_route_that_switches_content(self):
        screen = (
            "package dev.nova.tv.ui\n"
            "var route by remember { mutableStateOf(Route.HOME) }\n"
            "when (route) {\n"
            "  Route.HOME -> Home()\n"
            "  else -> Settings()\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(Path(tmp), {f"{KOTLIN}/ui/NovaApp.kt": screen})
            self.assertEqual(appaudit.find_unrouted_screens(root, appaudit.collect(root)), [])


class CollectTest(unittest.TestCase):
    def test_returns_nothing_for_a_missing_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(appaudit.collect(Path(tmp)), {})

    def test_reads_files_under_app_src(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_project(Path(tmp), {f"{KOTLIN}/A.kt": "package x\nclass A\n"})
            self.assertEqual(len(appaudit.collect(root)), 1)


class SummaryTest(unittest.TestCase):
    def test_counts_by_severity(self):
        findings = [
            appaudit.Finding("dead-route", appaudit.CRITICAL, "a.kt", 1, "r", "m", "s"),
            appaudit.Finding("dead-type", appaudit.HIGH, "b.kt", 2, "T", "m", "s"),
        ]
        summary = appaudit.summarise(findings)
        self.assertEqual(summary["total"], 2)
        self.assertEqual(summary["by_severity"]["critical"], 1)
        self.assertEqual(summary["worst"]["symbol"], "r")

    def test_plan_is_numbered(self):
        findings = [appaudit.Finding("dead-route", appaudit.CRITICAL, "a.kt", 1, "r", "m", "do x")]
        plan = appaudit.plan(findings)
        self.assertTrue(plan[0].startswith("1. "))
        self.assertIn("a.kt:1", plan[0])


if __name__ == "__main__":
    unittest.main()
