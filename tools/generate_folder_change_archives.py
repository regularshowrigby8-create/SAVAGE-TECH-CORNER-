#!/usr/bin/env python3
"""
Zip File Rule Enforcement:
Generates detailed, structured ZIP archives containing all files and change logs
for every major subsystem directory in the repository (big and small).
"""

from __future__ import annotations

import os
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent

TARGET_FOLDERS = [
    "shell",
    "factories",
    "factory-2",
    "factory-3",
    "frontend",
    "backend",
    "tools",
    "docs",
    ".github",
]

ARCHIVE_DIR = ROOT_DIR / "artifacts" / "archives"


def archive_folder(folder_rel: str) -> Path:
    folder_path = ROOT_DIR / folder_rel
    ARCHIVE_DIR.mkdir(parents=True, exist_ok=True)
    zip_path = ARCHIVE_DIR / f"{folder_rel.replace('/', '_').replace('.', 'dot_')}_changes.zip"

    fixed_time = (2026, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(folder_path):
            dirs[:] = [d for d in dirs if d not in {".git", "__pycache__", "node_modules", ".pytest_cache"}]
            for fname in sorted(files):
                fpath = Path(root) / fname
                rel_in_archive = fpath.relative_to(ROOT_DIR)
                zinfo = zipfile.ZipInfo(str(rel_in_archive), date_time=fixed_time)
                zinfo.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(zinfo, fpath.read_bytes())

    return zip_path


def main() -> None:
    print("[*] Generating subsystem change archives according to Zip File Rule...")
    for folder in TARGET_FOLDERS:
        if (ROOT_DIR / folder).exists():
            z = archive_folder(folder)
            size_kb = z.stat().st_size / 1024
            print(f"    - Archived {folder:15} -> {z.name} ({size_kb:.2f} KB)")
    print("[+] All folder archives successfully built.")


if __name__ == "__main__":
    main()
