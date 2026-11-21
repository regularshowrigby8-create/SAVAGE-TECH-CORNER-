#!/usr/bin/env python3
"""Offline evidence tests for the Nova APK pool packaging check."""
import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

import verify_nova_apk_pools as verifier


class ApkPoolVerificationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.tv = self.root / "tv.apk"
        self.mobile = self.root / "mobile.apk"
        self.server = {"entries": [{"manifestUrl": "https://addon.example/manifest.json",
                                     "baseUrl": "https://addon.example"}]}
        self.site = {"verification_method": verifier.site.VERIFICATION_METHOD, "entries": [{
            "url": "https://site.example",
            "templates": ["https://site.example/search?q={q}"],
            "verification": {"method": verifier.site.VERIFICATION_METHOD, "probe": "one piece",
                             "title_path": "/movie/one-piece", "control": "different-results",
                             "player_marker": "iframe"},
        }]}
        self.write_assets()
        self.write_apk(self.tv)
        self.write_apk(self.mobile)

    def write_assets(self):
        (self.assets / "server_pool.json").write_text(json.dumps(self.server))
        (self.assets / "site_pool.json").write_text(json.dumps(self.site))

    def write_apk(self, path, replacements=None, duplicate=None):
        replacements = replacements or {}
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as apk:
                for name in ("server_pool.json", "site_pool.json"):
                    content = replacements.get(name, (self.assets / name).read_bytes())
                    if content is not None:
                        apk.writestr("assets/" + name, content)
                    if name == duplicate:
                        apk.writestr("assets/" + name, content)

    def verify(self, allow_empty_sites=False):
        return verifier.verify_apks(self.assets, self.tv, self.mobile, allow_empty_sites)

    def test_both_exact_nonempty_assets_pass(self):
        report = self.verify()
        self.assertEqual({"server_pool.json": 1, "site_pool.json": 1}, report["counts"])
        self.assertEqual(64, len(report["apks"]["tv"]["sha256"]))

    def test_missing_or_stale_asset_fails(self):
        self.write_apk(self.mobile, {"site_pool.json": None})
        with self.assertRaisesRegex(verifier.VerificationError, "exactly once"):
            self.verify()
        self.write_apk(self.mobile, {"site_pool.json": b'{}'})
        with self.assertRaisesRegex(verifier.VerificationError, "does not match"):
            self.verify()

    def test_duplicate_zip_entry_fails(self):
        self.write_apk(self.tv, duplicate="server_pool.json")
        with self.assertRaisesRegex(verifier.VerificationError, "exactly once"):
            self.verify()

    def test_empty_site_is_only_allowed_for_explicit_smoke(self):
        self.site["entries"] = []
        self.write_assets()
        self.write_apk(self.tv)
        self.write_apk(self.mobile)
        with self.assertRaisesRegex(verifier.VerificationError, "pool is empty"):
            self.verify()
        self.assertEqual(0, self.verify(allow_empty_sites=True)["counts"]["site_pool.json"])

    def test_legacy_or_forged_site_evidence_is_rejected(self):
        self.site["entries"][0]["verification"].pop("control")
        self.write_assets()
        self.write_apk(self.tv)
        self.write_apk(self.mobile)
        with self.assertRaisesRegex(verifier.VerificationError, "verification evidence"):
            self.verify()
        self.site["verification_method"] = "legacy"
        self.write_assets()
        with self.assertRaisesRegex(verifier.VerificationError, "legacy"):
            self.verify()

    def test_unreadable_apk_fails(self):
        self.mobile.write_bytes(b"not an apk")
        with self.assertRaisesRegex(verifier.VerificationError, "unreadable"):
            self.verify()

    def test_duplicate_manifest_fails(self):
        self.server["entries"].append(self.server["entries"][0])
        self.write_assets()
        with self.assertRaisesRegex(verifier.VerificationError, "duplicate"):
            self.verify()


if __name__ == "__main__":
    unittest.main(verbosity=2)
