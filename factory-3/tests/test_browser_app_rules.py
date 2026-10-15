import unittest
import zipfile
from pathlib import Path
import sys

_curr = Path(__file__).resolve()
TOOLS_DIR = _curr.parent.parent / "tools"
sys.path.insert(0, str(TOOLS_DIR))

from browser_app_rules import verify_browser_app_rules


def build_apk(path: Path, *, manifest: bytes, dex_files=None, signed: bool = True,
              resource_table: bool = True) -> Path:
    """Builds an archive with the structure the rules inspect.

    The identity rule reads the declared package with a real APK parser when one is installed, so
    these fixtures exercise the fallback path that scans the compiled manifest and the dex files.
    """
    dex_files = dex_files if dex_files is not None else {"classes.dex": b"\x03\x00\x00\x00dex"}
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("AndroidManifest.xml", manifest)
        for name, blob in dex_files.items():
            zf.writestr(name, blob)
        if resource_table:
            zf.writestr("resources.arsc", b"\x02\x00\x0c\x00arsc")
        if signed:
            zf.writestr("META-INF/CERT.RSA", b"\x30\x82\x00\x00signature")
    return path


FILLER = b"\x00" * 60_000  # keeps the archive above the 50 KB anti-stub threshold
PACKAGE = "com.savage.soulbrowser"
HOST_PATH = PACKAGE.replace(".", "/")


class TestBrowserAppRules(unittest.TestCase):
    def test_current_apk_passes_rules(self):
        # Locate project root whether running from factories/factory-3 or root mirror
        curr = Path(__file__).resolve()
        # Find root containing 'factories' or 'shell'
        root = curr.parents[2]
        if not (root / "factories").exists() and curr.parents[3].exists():
            root = curr.parents[3]

        apk_path = root / "factories" / "final-product" / "SavageBrowser-debug.apk"
        if not apk_path.exists():
            apk_path = root / "shell" / "final-product" / "SavageBrowser-debug.apk"

        self.assertTrue(apk_path.exists(), f"APK path does not exist: {apk_path}")
        res = verify_browser_app_rules(apk_path)
        self.assertTrue(res["passed"], f"Violations: {res.get('violations')}")
        self.assertGreaterEqual(res["artifact_size_bytes"], 50000)


class TestIdentityRule(unittest.TestCase):
    """The provenance rule must accept a real Savage APK and reject anything else.

    It previously searched the raw bytes of the compiled manifest and of `classes.dex` for the
    substring 'savage', which is both too weak (a foreign build that happens to mention the word
    would pass) and too brittle (a string pool does not have to be UTF-8). These tests pin the
    behaviour the pipeline depends on.
    """

    def setUp(self):
        self.dir = Path(__file__).resolve().parent / "__fixtures__"
        self.dir.mkdir(exist_ok=True)

    def tearDown(self):
        for leftover in self.dir.glob("*.apk"):
            leftover.unlink()

    def apk(self, name: str, **kwargs) -> Path:
        return build_apk(self.dir / name, **kwargs)

    def test_a_utf8_manifest_naming_the_package_passes(self):
        path = self.apk(
            "utf8.apk",
            manifest=b'<manifest package="' + PACKAGE.encode() + b'"/>' + FILLER,
        )
        self.assertTrue(verify_browser_app_rules(path)["passed"])

    def test_a_utf16_manifest_naming_the_package_passes(self):
        """A compiled manifest's string pool may be UTF-16; the bytes must not decide the outcome."""
        path = self.apk(
            "utf16.apk",
            manifest=PACKAGE.encode("utf-16-le") + FILLER,
        )
        self.assertTrue(verify_browser_app_rules(path)["passed"])

    def test_dex_bytecode_with_the_host_path_passes(self):
        path = self.apk(
            "dex.apk",
            manifest=b"<manifest/>" + FILLER,
            dex_files={"classes.dex": b"\x03\x00" + HOST_PATH.encode() + b"/ui/MainActivity"},
        )
        self.assertTrue(verify_browser_app_rules(path)["passed"])

    def test_multi_dex_is_inspected_as_a_whole(self):
        """A violation must not hide in classes2.dex, and the identity may live there too."""
        violating = self.apk(
            "multi_violation.apk",
            manifest=b"<manifest/>" + FILLER,
            dex_files={
                "classes.dex": b"\x03\x00" + HOST_PATH.encode(),
                "classes2.dex": b"\x03\x00acr/browser",
            },
        )
        result = verify_browser_app_rules(violating)
        self.assertFalse(result["passed"])
        self.assertTrue(any("acr.browser" in violation for violation in result["violations"]))

    def test_a_foreign_package_is_rejected(self):
        path = self.apk(
            "foreign.apk",
            manifest=b'<manifest package="acr.browser.lightning"/>' + FILLER,
            dex_files={"classes.dex": b"\x03\x00Lacr/browser/lightning/MainActivity;"},
        )
        result = verify_browser_app_rules(path)
        self.assertFalse(result["passed"])
        self.assertTrue(any("identity" in violation for violation in result["violations"]))

    def test_the_rejection_names_what_was_searched(self):
        path = self.apk("nothing.apk", manifest=FILLER)
        violation = verify_browser_app_rules(path)["violations"][0]
        self.assertIn(PACKAGE, violation)
        self.assertIn("classes.dex", violation)

    def test_structure_violations_are_still_reported(self):
        unsigned = self.apk("unsigned.apk", manifest=PACKAGE.encode() + FILLER, signed=False)
        self.assertFalse(verify_browser_app_rules(unsigned)["passed"])
        missing_table = self.apk(
            "no_arsc.apk", manifest=PACKAGE.encode() + FILLER, resource_table=False)
        self.assertTrue(any("resources.arsc" in violation
                            for violation in verify_browser_app_rules(missing_table)["violations"]))
        no_dex = self.apk("no_dex.apk", manifest=PACKAGE.encode() + FILLER, dex_files={})
        self.assertTrue(any("classes.dex" in violation
                            for violation in verify_browser_app_rules(no_dex)["violations"]))


if __name__ == "__main__":
    unittest.main()
