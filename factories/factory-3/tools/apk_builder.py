#!/usr/bin/env python3
"""APK build stage for the Factory 3 pipeline.

Builds the Savage Browser Android application with the real Gradle build that owns it
(``shell/android_project``) and publishes the resulting debug APK to the final-product directory.

Design rules:
* the APK is *built*, never copied from a pre-built file that happens to sit in the repository - a
  file this checkout did not build says nothing about this checkout;
* when the Gradle build cannot run (no wrapper, no JDK, no Android SDK), this stage fails with the
  build's own output. It never falls back to an older artifact;
* the SHA-256 of the APK that was actually built is written next to it, so downstream verification
  has something to verify.

Output: factories/final-product/SavageBrowser-debug.apk (+ .sha256)
"""

from __future__ import annotations

import hashlib
import subprocess
import sys
from pathlib import Path

_curr = Path(__file__).resolve()
ROOT_DIR = _curr.parent.parent.parent


def find_root() -> Path:
    """Locates the repository root that contains both shell/ and factories/."""
    for candidate in (_curr.parent.parent.parent, _curr.parent.parent.parent.parent,
                      _curr.parent.parent):
        if (candidate / "shell" / "android_project" / "gradlew").is_file():
            return candidate
    return ROOT_DIR


ROOT_DIR = find_root()
ANDROID_PROJECT = ROOT_DIR / "shell" / "android_project"
SOURCE_APK = ANDROID_PROJECT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
FINAL_PRODUCT_DIR = ROOT_DIR / "factories" / "final-product"
SHELL_DIR = ROOT_DIR / "shell"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_debug_apk(output_apk_path: Path = FINAL_PRODUCT_DIR / "SavageBrowser-debug.apk") -> Path:
    """Runs Gradle and publishes the APK it produced to ``output_apk_path``."""
    wrapper = ANDROID_PROJECT / "gradlew"
    if not wrapper.is_file():
        raise FileNotFoundError(
            f"the Gradle wrapper is missing at {wrapper}; this stage cannot build an APK without it")

    command = [str(wrapper), ":app:assembleDebug", "--stacktrace", "--no-daemon"]
    print(f"[APK-BUILDER] {' '.join(command)} (cwd={ANDROID_PROJECT})")
    completed = subprocess.run(command, cwd=ANDROID_PROJECT, check=False)
    if completed.returncode != 0:
        raise RuntimeError(
            f"the Gradle build failed with exit code {completed.returncode}; no APK was published "
            f"and no older artifact is used as a substitute")

    if not SOURCE_APK.is_file():
        raise FileNotFoundError(
            f"Gradle reported success but {SOURCE_APK} does not exist; refusing to publish anything")

    output_apk_path.parent.mkdir(parents=True, exist_ok=True)
    output_apk_path.write_bytes(SOURCE_APK.read_bytes())
    digest = sha256_of(output_apk_path)
    output_apk_path.with_suffix(".apk.sha256").write_text(
        f"{digest}  {output_apk_path.name}\n", encoding="utf-8")
    print(f"[APK-BUILDER] built {SOURCE_APK} ({SOURCE_APK.stat().st_size} bytes)")
    print(f"[APK-BUILDER] published {output_apk_path} sha256={digest}")
    return output_apk_path


def create_signed_apk(output_apk_path: Path) -> Path:
    """Backwards-compatible name for the build stage used by factories/run_factories.py."""
    return build_debug_apk(output_apk_path)


def main() -> None:
    apk_path = build_debug_apk()
    size_kb = apk_path.stat().st_size / 1024
    print(f"[APK-BUILDER] Savage Browser APK: {apk_path} ({size_kb:.2f} KB) "
          f"sha256={sha256_of(apk_path)}")


if __name__ == "__main__":
    sys.exit(main())
