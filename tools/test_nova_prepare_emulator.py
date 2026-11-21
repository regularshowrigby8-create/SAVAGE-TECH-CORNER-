#!/usr/bin/env python3
"""Offline tests for the CI emulator SDK preparation; no real SDK is touched."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "nova_prepare_emulator.sh"


class EmulatorSdkPreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.sdk = root / "sdk"
        manager = self.sdk / "cmdline-tools/latest/bin/sdkmanager"
        manager.parent.mkdir(parents=True)
        manager.write_text(r'''#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SDK_CALLS"
if [ "$1" = "--licenses" ]; then
  [ "${FAIL_LICENSE:-0}" != 1 ]
  exit $?
fi
if [ "${FAIL_INSTALL:-0}" = 1 ]; then
  echo 'synthetic SDK failure' >&2
  exit 1
fi
if [ "$2" = "emulator" ] && [ "${FAIL_ONCE:-0}" = 1 ] && [ ! -e "$SDK_RETRIED" ]; then
  touch "$SDK_RETRIED"
  exit 1
fi
if [ "$2" = "system-images;android-34;android-tv;x86" ] && [ "${MISSING_IMAGE:-0}" != 1 ]; then
  mkdir -p "$ANDROID_HOME/system-images/android-34/android-tv/x86"
  touch "$ANDROID_HOME/system-images/android-34/android-tv/x86/source.properties"
fi
exit 0
''')
        manager.chmod(0o755)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        sleeper = bin_dir / "sleep"
        sleeper.write_text("#!/usr/bin/env bash\nexit 0\n")
        sleeper.chmod(0o755)
        self.calls = root / "calls"
        self.env = {
            **os.environ,
            "ANDROID_HOME": str(self.sdk),
            "SDK_CALLS": str(self.calls),
            "SDK_RETRIED": str(root / "retried"),
            "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
        }

    def run_script(self, **overrides):
        return subprocess.run(["bash", str(SCRIPT)], env={**self.env, **overrides},
                              text=True, capture_output=True, timeout=10)

    def test_success_installs_the_available_tv_image(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Android TV API 34 x86 image is installed", result.stdout)
        self.assertIn("--install system-images;android-34;android-tv;x86", self.calls.read_text())
        self.assertTrue((self.sdk / "system-images/android-34/android-tv/x86/source.properties").is_file())

    def test_transient_failure_is_retried(self):
        result = self.run_script(FAIL_ONCE="1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(2, self.calls.read_text().count("--install emulator\n"))

    def test_exhausted_retries_are_a_failure(self):
        result = self.run_script(FAIL_INSTALL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(3, self.calls.read_text().count("--install build-tools;37.0.0\n"))
        self.assertIn("after three attempts", result.stdout)

    def test_missing_image_metadata_is_a_failure(self):
        result = self.run_script(MISSING_IMAGE="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("image metadata is missing", result.stdout)

    def test_license_failure_does_not_attempt_installation(self):
        result = self.run_script(FAIL_LICENSE="1")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("--licenses\n", self.calls.read_text())
        self.assertIn("license acceptance failed", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
