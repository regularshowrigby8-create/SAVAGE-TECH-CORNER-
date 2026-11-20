#!/usr/bin/env python3
"""No-device tests: the emulator smoke check must not pass on missing evidence."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import nova_emulator_smoke as smoke


class SmokeEvidenceTests(unittest.TestCase):
    def test_only_observed_start_alive_process_and_marker_pass(self):
        self.assertEqual([], smoke.assess_observation("dev.nova.tv", "Status: ok\n", "123",
                                                       smoke.MARKER, ""))
        for launch, pid, log in (("", "123", smoke.MARKER), ("Status: ok", "", smoke.MARKER),
                                 ("Status: ok", "123", "no finder log")):
            self.assertTrue(smoke.assess_observation("dev.nova.tv", launch, pid, log, ""))

    def test_package_specific_fatal_and_anr_fail(self):
        common = ("dev.nova.mobile", "Status: ok", "123", smoke.MARKER)
        self.assertTrue(smoke.assess_observation(*common, "FATAL EXCEPTION: main\nProcess: dev.nova.mobile, PID: 123"))
        self.assertFalse(smoke.assess_observation(*common, "FATAL EXCEPTION: main\nProcess: other.app, PID: 123"))
        self.assertTrue(smoke.assess_observation("dev.nova.tv", "Status: ok", "123",
                                                  smoke.MARKER + "\nANR in dev.nova.tv (dev.nova.tv)", ""))
        self.assertTrue(smoke.assess_observation("dev.nova.tv", "Status: ok", "123",
                                                  smoke.MARKER + "\nNovaHarvester: server pool fill failed", ""))

    def test_run_case_records_observation_and_does_not_forge_marker(self):
        def fake_adb(*args, required=True):
            command = tuple(args)
            out = ""
            if command[0] == "install":
                out = "Success\n"
            elif command[:4] == ("shell", "am", "start", "-W"):
                out = "Status: ok\n"
            elif command[:2] == ("shell", "pidof"):
                out = "42\n"
            elif command[:2] == ("logcat", "-d"):
                out = "no finder actually started"
            return subprocess.CompletedProcess(args, 0, out, "")
        with tempfile.TemporaryDirectory() as tmp, patch.object(smoke, "adb", side_effect=fake_adb), \
             patch.object(smoke.time, "sleep"):
            root = Path(tmp)
            apk = root / "app.apk"
            apk.write_bytes(b"fixture")
            result = smoke.run_case("tv", "dev.nova.tv", "example.Activity", apk, 60, root)
            self.assertEqual("failed", result["status"])
            self.assertEqual(0, result["observed_seconds"])
            self.assertFalse(result["finder_marker"])
            self.assertIn("server-fill", result["errors"][0])

    def test_run_case_observes_full_interval_after_finder_marker(self):
        def fake_adb(*args, required=True):
            command = tuple(args)
            out = ""
            if command[0] == "install": out = "Success\n"
            elif command[:4] == ("shell", "am", "start", "-W"): out = "Status: ok\n"
            elif command[:2] == ("shell", "pidof"): out = "42\n"
            elif command[:2] == ("logcat", "-d"): out = smoke.MARKER
            return subprocess.CompletedProcess(args, 0, out, "")
        with tempfile.TemporaryDirectory() as tmp, patch.object(smoke, "adb", side_effect=fake_adb), \
             patch.object(smoke.time, "sleep") as sleep:
            root = Path(tmp)
            apk = root / "app.apk"
            apk.write_bytes(b"fixture")
            result = smoke.run_case("tv", "dev.nova.tv", "example.Activity", apk, 60, root)
            self.assertEqual("passed", result["status"])
            self.assertEqual(60, result["observed_seconds"])
            sleep.assert_called_once_with(60)

    def test_failed_install_does_not_claim_observation_time(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(smoke, "adb", side_effect=OSError("no adb")):
            result = smoke.run_case("tv", "dev.nova.tv", "example.Activity", Path(tmp) / "missing.apk", 90, Path(tmp))
            self.assertEqual("failed", result["status"])
            self.assertEqual(0, result["observed_seconds"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
