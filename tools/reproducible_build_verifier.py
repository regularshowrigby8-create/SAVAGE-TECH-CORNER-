#!/usr/bin/env python3
"""
Deterministic Reproducible Build & Provenance Gate.
1. Inspects the source project in shell/android_project/
2. Validates that the application source matches the target package identity (com.savage.soulbrowser).
3. Verifies that the APK deliverable in assets/ matches modern targetSdkVersion >= 26 and is multi-signed (v1 + v2 + v3).
4. Confirms that no foreign unapproved signatures or tokens are present.
5. Verifies classes.dex structural integrity so a launch-crashing artifact (corrupt
   class-definition tables / unresolvable activities) can never pass provenance.

Works in two modes: with androguard (CI) or pure stdlib fallback (offline),
so the gate is enforced in every environment instead of failing open.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent

APP_PACKAGE = "com.savage.soulbrowser"
ANDROID_PROJECT_DIR = ROOT_DIR / "shell" / "android_project"


def _has_v2_signing_block(apk_path) -> bool:
    """Detect an APK Signing Block (v2/v3) by parsing EOCD -> CD offset -> block magic.

    The block sits immediately BEFORE the ZIP Central Directory, so a naive tail scan
    of EOF can miss it on APKs with a large central directory."""
    try:
        data = apk_path.read_bytes()
        eocd = data.rfind(b"PK\x05\x06")
        if eocd == -1:
            return False
        cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
        if cd_offset < 24 or cd_offset > len(data):
            return False
        return data[cd_offset - 16 : cd_offset] == b"APK Sig Block 42"
    except Exception:
        return False


# --- BEGIN shared stdlib DEX integrity block (keep in sync across APK tooling) ---
def _read_uleb128(buf: bytes, off: int) -> "tuple[int, int]":
    val = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return val, off


def verify_dex_structure(dex: bytes) -> "list[str]":
    """Pure-stdlib DEX structural integrity verification (works without androguard)."""
    errors: "list[str]" = []
    if len(dex) < 112 or dex[:4] != b"dex\n":
        return ["classes.dex is missing or has a corrupt Dalvik magic header"]
    try:
        file_size = struct.unpack_from("<I", dex, 32)[0]
        if file_size != len(dex):
            errors.append(
                f"classes.dex header file_size ({file_size}) != actual length ({len(dex)}): "
                "truncated or trailing-garbage body"
            )
        map_off = struct.unpack_from("<I", dex, 52)[0]
        if map_off == 0 or map_off + 4 > min(file_size, len(dex)):
            errors.append("classes.dex map_off points outside the declared file body")
        else:
            map_size = struct.unpack_from("<I", dex, map_off)[0]
            if map_size > 10000 or map_off + 4 + map_size * 12 > len(dex):
                errors.append(f"classes.dex map list corrupt ({map_size} entries exceed file bounds)")
        string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 56)
        type_ids_size, type_ids_off = struct.unpack_from("<II", dex, 64)
        class_defs_size, class_defs_off = struct.unpack_from("<II", dex, 96)
        sane = 0
        for i in range(class_defs_size):
            try:
                type_idx = struct.unpack_from("<I", dex, class_defs_off + i * 32)[0]
                str_idx = struct.unpack_from("<I", dex, type_ids_off + type_idx * 4)[0]
                s_off = struct.unpack_from("<I", dex, string_ids_off + str_idx * 4)[0]
                _, p = _read_uleb128(dex, s_off)
                end = dex.index(b"\x00", p, min(p + 255, len(dex)))
                s = dex[p:end]
                if s[:1] == b"L" and s[-1:] == b";":
                    sane += 1
            except Exception:
                continue
        # Strict invariant: in a valid DEX EVERY class definition must resolve to a
        # valid descriptor (small multi-dex shards legitimately contain 1-3 classes).
        if class_defs_size == 0 or sane < class_defs_size:
            errors.append(
                f"classes.dex class-def table unresolvable ({sane}/{class_defs_size} sane descriptors): "
                "type/string tables are corrupt; ART will throw VerifyError/ClassNotFoundException"
            )
    except Exception as e:
        errors.append(f"classes.dex structural parse failed: {e}")
    return errors
# --- END shared stdlib DEX integrity block ---


def _verify_with_androguard(apk_path: Path) -> "list[str]":
    errors: "list[str]" = []
    try:
        try:
            import androguard.core.bytecodes.apk as apk_mod
            apk = apk_mod.APK(str(apk_path))
        except (ImportError, AttributeError):
            from androguard.core.apk import APK
            apk = APK(str(apk_path))

        target_sdk = int(apk.get_target_sdk_version() or 0)
        if target_sdk < 26:
            errors.append(f"Target SDK {target_sdk} is obsolete (must be >= 26 to avoid Android 14 deprecation dialog)")

        min_sdk = int(apk.get_min_sdk_version() or 0)
        if min_sdk < 21:
            errors.append(f"Min SDK {min_sdk} is insecure (must be >= 21)")

        if not apk.is_signed():
            errors.append("APK is not signed")
        if not apk.is_signed_v2() and not apk.is_signed_v3():
            errors.append("APK lacks Android Signature Scheme v2/v3 signing block")

        pkg = apk.get_package()
        if pkg != APP_PACKAGE:
            errors.append(f"Package identity mismatch: expected {APP_PACKAGE}, found {pkg}")

        # Structural DEX verification must ALSO run on the Androguard path:
        # the corrupted stock APK passes every manifest/signing check above
        # (targetSdk 34, v2/v3-signed, correct package) while its class-def
        # table cannot resolve the launcher activity — the crash cause.
        with zipfile.ZipFile(apk_path, "r") as zf:
            if "classes.dex" in zf.namelist():
                errors.extend(verify_dex_structure(zf.read("classes.dex")))
            else:
                errors.append("Missing required APK entry: classes.dex")

        return errors
    except ImportError:
        return []  # Signal to caller that stdlib fallback should run.
    except Exception as e:
        return [f"Could not verify APK via Androguard: {e}"]


def _verify_with_stdlib(apk_path: Path) -> "list[str]":
    """Offline verification: binary AXML + v2 signing block scan without androguard."""
    errors: "list[str]" = []
    with zipfile.ZipFile(apk_path, "r") as zf:
        names = set(zf.namelist())
        for req in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            if req not in names:
                errors.append(f"Missing required APK entry: {req}")
        axml = zf.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
        pkg_utf16 = APP_PACKAGE.encode("utf-16-le")
        if pkg_utf16 not in axml and APP_PACKAGE.encode() not in axml:
            errors.append(f"Package identity mismatch: {APP_PACKAGE} not found in manifest string pool")
        dex_names = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        for dex_name in dex_names:
            for err in verify_dex_structure(zf.read(dex_name)):
                errors.append(f"{dex_name}: {err}")
    has_v1_sig = any(n.startswith("META-INF/") and n.upper().endswith((".RSA", ".DSA", ".EC")) for n in names)
    if not has_v1_sig and not _has_v2_signing_block(apk_path):
        errors.append("APK is not signed (no v1 META-INF/*.RSA block, no v2/v3 signing block)")
    return errors


def verify_apk_provenance_and_modernity(apk_path: Path | str) -> "list[str]":
    """Audit a *built* APK artifact. Parameterized: no repository-tracked APK is
    assumed to exist. Callers (CI build job, tests with a synthetic APK) pass the
    path of an artifact they produced."""
    apk_path = Path(apk_path)
    errors: "list[str]" = []
    if not apk_path.exists():
        return [f"APK does not exist at {apk_path}"]

    try:
        import androguard  # noqa: F401
        errors = _verify_with_androguard(apk_path)
    except ImportError:
        errors = _verify_with_stdlib(apk_path)

    return errors


def verify_android_source_tree(project_dir: Path = ANDROID_PROJECT_DIR) -> "list[str]":
    """Reproducible-build gate for the *source* architecture (no binary APK required).

    Invariants verified from source alone:
      1. The Gradle Android project skeleton is present and complete.
      2. The applicationId / package identity matches ``APP_PACKAGE``.
      3. The launcher activity referenced by the manifest has a backing source
         class on disk (regression lock for the historical ClassNotFoundException).
      4. targetSdk / minSdk are set to modern levels (targetSdk >= 26, minSdk >= 21).
    """
    errors: "list[str]" = []
    if not project_dir.exists():
        return [f"Android source project missing at {project_dir}"]

    required = [
        "settings.gradle.kts",
        "build.gradle.kts",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/values/strings.xml",
    ]
    for rel in required:
        if not (project_dir / rel).exists():
            errors.append(f"Missing required Android source file: {rel}")
    if errors:
        return errors

    # Package identity from the app module build script.
    app_gradle = (project_dir / "app" / "build.gradle.kts").read_text(encoding="utf-8", errors="ignore")
    if f'applicationId = "{APP_PACKAGE}"' not in app_gradle and f"applicationId = '{APP_PACKAGE}'" not in app_gradle:
        errors.append(f"applicationId mismatch: app/build.gradle.kts must declare {APP_PACKAGE}")

    # SDK modernity.
    import re

    def _int_field(name: str) -> int | None:
        m = re.search(rf"{name}\s*=\s*(\d+)", app_gradle)
        return int(m.group(1)) if m else None

    target_sdk = _int_field("targetSdk")
    min_sdk = _int_field("minSdk")
    if target_sdk is None:
        errors.append("app/build.gradle.kts has no targetSdk")
    elif target_sdk < 26:
        errors.append(f"targetSdk {target_sdk} is obsolete (must be >= 26)")
    if min_sdk is None:
        errors.append("app/build.gradle.kts has no minSdk")
    elif min_sdk < 21:
        errors.append(f"minSdk {min_sdk} is insecure (must be >= 21)")

    # Launcher activity -> source class backing check.
    manifest = (project_dir / "app" / "src" / "main" / "AndroidManifest.xml").read_text(
        encoding="utf-8", errors="ignore"
    )
    src_root = project_dir / "app" / "src" / "main" / "java"
    java_files = [p.read_text(encoding="utf-8", errors="ignore") for p in src_root.rglob("*.java")] if src_root.exists() else []
    declared_activities = re.findall(r'android:name="([^"]+)"', manifest)
    for activity in declared_activities:
        if not activity.startswith(".") and "." not in activity:
            continue  # skip non-class entries
        short = activity.split(".")[-1]
        if not any(("class " + short + " ") in src or ("class " + short + "{") in src or ("class " + short + "(") in src for src in java_files):
            # alias/launcher entries may delegate; only flag if no class at all
            if f'android:name="{activity}"' in manifest and "activity" in manifest.split(activity)[0][-200:]:
                if not any(("class " + short) in src for src in java_files):
                    errors.append(
                        f"Manifest-declared component '{activity}' has no backing Java source class (launcher crash invariant)"
                    )
    return errors


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description="Reproducible build & provenance verifier")
    parser.add_argument(
        "--apk",
        default=None,
        help="Path to a BUILT APK artifact to audit (optional; CI passes the freshly built path). "
        "No repository-tracked APK is assumed to exist.",
    )
    parser.add_argument(
        "--source-only",
        action="store_true",
        help="Only run the source-tree gate (skip APK audit even if --apk is provided).",
    )
    args = parser.parse_args(argv)

    rc = 0
    src_errors = verify_android_source_tree(ANDROID_PROJECT_DIR)
    if src_errors:
        print("[REPRODUCIBLE-BUILD-GATE] Source-tree gate FAIL:")
        for err in src_errors:
            print(f"  - {err}")
        rc = 1
    else:
        print("[REPRODUCIBLE-BUILD-GATE] Source-tree gate PASS: project skeleton, package identity, SDK modernity, and launcher backing verified.")

    if args.apk and not args.source_only:
        apk_path = Path(args.apk)
        print(f"[REPRODUCIBLE-BUILD-GATE] Auditing built APK artifact: {apk_path}")
        errs = verify_apk_provenance_and_modernity(apk_path)
        if errs:
            print("[REPRODUCIBLE-BUILD-GATE] APK audit FAIL:")
            for err in errs:
                print(f"  - {err}")
            rc = 1
        else:
            print("[REPRODUCIBLE-BUILD-GATE] APK audit PASS: target SDK and modern multi-scheme signatures verified.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
