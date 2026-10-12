# Generated Artifacts Directory

This directory contains manifests and reports generated during CI and factory
pipeline runs.

**No APK/AAB binaries are tracked in this repository** ("delete all APK versions,
keep the architecture"). Android binaries are produced by CI
(`.github/workflows/android-build.yml`), published as CI artifacts / GitHub
Releases, and are git-ignored (`*.apk`, `*.aab`).

## Subdirectories:

- `debug/`: (empty) former location of debug APKs — retained for layout only;
  no binaries are committed.
- `archives/`: Folder change archives (`.zip` snapshots of source folders).
- `manifests/`: Formal release/pipeline manifests (`RELEASE_MANIFEST.json`).
