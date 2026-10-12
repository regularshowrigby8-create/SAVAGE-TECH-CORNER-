#!/usr/bin/env python3
"""Deprecated APK copy-forward tool.

Per the "delete all APK versions, keep the architecture" requirement, no APK
binary is tracked in this repository. The former behavior — locate an existing
signed APK and copy it into ``final-product/`` — is removed. It is replaced by
genuine source-driven builds performed by CI (``.github/workflows/android-build.yml``),
which compiles the Gradle project and passes the freshly-built APK to the
verification gates and release steps.

This module intentionally still *imports* cleanly (so mirrors/importers do not
crash), but every operational entry point raises a clear, fail-closed error.
"""

from __future__ import annotations

from pathlib import Path


def _find_repo_root(start: Path) -> Path:
    """Walk upward until we find the repo root (identified by shell/android_project)."""
    here = start
    for _ in range(10):
        if (here / "shell" / "android_project").exists():
            return here
        if here.parent == here:
            break
        here = here.parent
    return start


ROOT_DIR = _find_repo_root(Path(__file__).resolve().parent)

_DEPRECATION_MESSAGE = (
    "apk_builder is deprecated: repository no longer tracks any APK binary. "
    "Build the APK from shell/android_project via CI (.github/workflows/android-build.yml) "
    "instead of copying a prebuilt artifact."
)


# --- stdlib DEX integrity structure (kept importable for structural verification) ---
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
    """Pure-stdlib DEX structural integrity verification (kept for source-level gates)."""
    import struct

    errors: "list[str]" = []
    if len(dex) < 112 or dex[:4] != b"dex\n":
        return ["classes.dex is missing or has a corrupt Dalvik magic header"]
    try:
        file_size = struct.unpack_from("<I", dex, 32)[0]
        if file_size != len(dex):
            errors.append(
                f"classes.dex header file_size ({file_size}) != actual length ({len(dex)}): truncated or trailing-garbage body"
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
        if class_defs_size == 0 or sane < class_defs_size:
            errors.append(
                f"classes.dex class-def table unresolvable ({sane}/{class_defs_size} sane descriptors): type/string tables are corrupt"
            )
    except Exception as e:
        errors.append(f"classes.dex structural parse failed: {e}")
    return errors


# Deprecated operational entry points (fail-closed, explicit signatures).
def extract_signing_block(*args, **kwargs):
    raise NotImplementedError(f"extract_signing_block() is not available. {_DEPRECATION_MESSAGE}")


def inject_signing_block(*args, **kwargs):
    raise NotImplementedError(f"inject_signing_block() is not available. {_DEPRECATION_MESSAGE}")


def create_signed_apk(*args, **kwargs):
    raise NotImplementedError(f"create_signed_apk() is not available. {_DEPRECATION_MESSAGE}")


def build_signed_apk(*args, **kwargs):
    raise NotImplementedError(f"build_signed_apk() is not available. {_DEPRECATION_MESSAGE}")


def main(*args, **kwargs):
    raise NotImplementedError(f"main() is not available. {_DEPRECATION_MESSAGE}")


if __name__ == "__main__":
    main()
