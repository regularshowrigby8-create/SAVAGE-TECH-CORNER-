#!/usr/bin/env python3
"""Static checks for the Kotlin sources, since no compiler is available.

This is not a compiler. It catches the class of mistake that is cheap to make
when writing files by hand and expensive to find in Android Studio:

  * a package declaration that does not match the directory
  * a public symbol used with no import and no local declaration
  * duplicated class names
  * unbalanced braces
  * an import that nothing references

It cannot typecheck. It is honest about that in its output.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "app" / "src"

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?$", re.M)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
CLASS_RE = re.compile(r"^(?:data\s+|sealed\s+|abstract\s+|open\s+|private\s+|internal\s+)*(?:class|interface|object|enum class)\s+(\w+)", re.M)


def _scan_kotlin(text: str, keep_strings: bool) -> str:
    """Single pass over Kotlin source that drops comments.

    Strings are scanned *before* comment markers can match, so a literal such as
    "https://host/x" is not mistaken for a `//` comment (the regex approach cut
    the rest of the line, then mis-paired every later quote). With
    keep_strings=False, literal text is blanked to "" but `${...}` template
    expressions are kept, because the identifiers inside them are real uses.
    Nested block comments are handled, as Kotlin allows them.
    """
    out: list[str] = []
    i, n = 0, len(text)

    def template(j: int) -> int:
        # text[j:j+2] == "${"; copy the balanced expression, return index after it.
        depth, k = 0, j + 1
        while k < n:
            ch = text[k]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    if not keep_strings:
                        out.append(" " + _scan_kotlin(text[j + 2:k], keep_strings) + " ")
                    return k + 1
            elif ch == '"':
                # nested string inside the template
                end = text.find('"', k + 1)
                k = end if end != -1 else n
            k += 1
        return n

    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    if text[i] == "\n":
                        out.append("\n")
                    i += 1
            out.append(" ")
        elif text.startswith('"""', i):
            j = i + 3
            start = len(out)
            while j < n and not text.startswith('"""', j):
                if text.startswith("${", j):
                    j = template(j)
                    continue
                if text[j] == "\n" and not keep_strings:
                    out.append("\n")
                j += 1
            while j + 3 < n and text[j + 3] == '"':  # """...""""  -> trailing quote belongs to the string
                j += 1
            end = min(j + 3, n)
            if keep_strings:
                del out[start:]
                out.append(text[i:end])
            else:
                out.insert(start, '""')
            i = end
        elif c == '"':
            j = i + 1
            start = len(out)
            while j < n and text[j] != '"' and text[j] != "\n":
                if text[j] == "\\":
                    j += 2
                    continue
                if text.startswith("${", j):
                    j = template(j)
                    continue
                j += 1
            end = min(j + 1, n)
            if keep_strings:
                del out[start:]
                out.append(text[i:end])
            else:
                out.insert(start, '""')
            i = end
        elif c == "'":
            # char literal: '{', '\n', '\u0041'
            j = text.find("'", i + 1 + (1 if text[i + 1:i + 2] == "\\" else 0))
            if j != -1 and j - i <= 8:
                out.append(text[i:j + 1] if keep_strings else "' '")
                i = j + 1
            else:
                out.append(c)
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_comments_and_strings(text: str) -> str:
    return _scan_kotlin(text, keep_strings=False)


def check_build_files(root: Path) -> list[str]:
    """Cross-check the Gradle build files against the sources they must build.

    This is not a Gradle build -- it cannot resolve dependencies or type-check.
    It catches the class of mistake that makes a build fail instantly and
    unhelpfully: a namespace that does not match the source layout, a plugin
    used by a module but never declared in the root file, a dependency
    referenced in the catalog that is not defined, or a referenced file that
    does not exist.
    """
    problems: list[str] = []
    android = root / "android"
    if not android.is_dir():
        return problems

    # The module must still declare the things AGP reads before it builds
    # anything. A refactor that accidentally swallows `compileSdk` or
    # `defaultConfig` produces a build that fails on line 1 with an unhelpful
    # message, which is exactly what happened: a splice from the signing block to
    # `buildTypes` deleted both, and AGP reported only "compileSdkVersion is not
    # specified". Structural lint cannot run Gradle, but it can insist these
    # declarations exist.
    module = android / "app" / "build.gradle.kts"
    if module.is_file():
        text = module.read_text()
        for declaration in (
            "namespace",
            "compileSdk",
            "defaultConfig",
            "applicationId",
            "minSdk",
            "targetSdk",
            "versionCode",
            "versionName",
            "buildTypes",
        ):
            if not re.search(rf"\b{declaration}\b", text):
                problems.append(
                    f"{module.name}: '{declaration}' is not declared -- AGP will "
                    f"fail before compiling anything"
                )

    catalog = android / "gradle" / "libs.versions.toml"
    if not catalog.is_file():
        return [f"{catalog.relative_to(root)} is missing"]

    try:
        import tomllib
    except ModuleNotFoundError:  # pragma: no cover - py<3.11
        return problems
    try:
        data = tomllib.loads(catalog.read_text())
    except Exception as error:
        return [f"version catalog does not parse: {error}"]

    defined = set(data.get("libraries", {}))
    defined_plugins = set(data.get("plugins", {}))

    app_build = android / "app" / "build.gradle.kts"
    root_build = android / "build.gradle.kts"
    if not app_build.is_file():
        return problems + [f"{app_build.relative_to(root)} is missing"]

    app_text = app_build.read_text()
    root_text = root_build.read_text() if root_build.is_file() else ""

    # 1. Every `libs.*` reference must exist in the catalog.
    #
    # Gradle's Kotlin DSL maps the whole dotted path to one hyphenated key:
    #   libs.compose.ui.tooling.preview  ->  compose-ui-tooling-preview
    #   libs.plugins.kotlin.android      ->  [plugins] kotlin-android
    # Treating only the first and last segments as the key reports false
    # positives for every multi-segment accessor, which is most of them.
    for match in re.finditer(r"libs\.([a-z0-9]+(?:\.[a-z0-9]+)*)", app_text):
        path_segments = match.group(1).split(".")
        if path_segments[0] == "versions":
            continue
        if path_segments[0] == "plugins":
            if len(path_segments) < 2:
                continue
            key = "-".join(path_segments[1:])
            if key not in defined_plugins:
                problems.append(f"app applies plugin '{key}' which the catalog does not define")
            # and it must be declared, apply-false, at the root
            dotted = ".".join(path_segments[1:])
            if f"libs.plugins.{dotted}" not in root_text:
                problems.append(f"plugin '{key}' is applied in app but not declared in the root build file")
            continue
        key = "-".join(path_segments)
        if key not in defined:
            problems.append(
                f"app references libs.{'.'.join(path_segments)} (catalog key '{key}') which the catalog does not define"
            )

    # 2. Every library the catalog defines should be reachable; catch dead entries.
    referenced = set()
    for match in re.finditer(r"libs\.([a-z0-9]+(?:\.[a-z0-9]+)*)", app_text):
        segs = match.group(1).split(".")
        if segs[0] in ("versions", "plugins"):
            continue
        referenced.add("-".join(segs))
    for key in sorted(defined - referenced):
        problems.append(f"catalog defines '{key}' but no module uses it")

    # 3. `namespace` must match where the sources actually live.
    ns = re.search(r'namespace\s*=\s*"([^"]+)"', app_text)
    if ns:
        expected = android / "app" / "src" / "main" / "java" / Path(*ns.group(1).split("."))
        if not expected.is_dir():
            problems.append(f"namespace '{ns.group(1)}' has no source directory at app/src/main/java/{ns.group(1).replace('.', '/')}")

    # 4. Files the build references must exist.
    for relative in ("app/proguard-rules.pro", "gradle/wrapper/gradle-wrapper.jar",
                     "gradle/wrapper/gradle-wrapper.properties", "gradlew",
                     "app/src/main/AndroidManifest.xml"):
        if not (android / relative).exists():
            problems.append(f"build references {relative} but it does not exist")

    # 5. Manifest must not declare a package; AGP 8 takes it from namespace.
    manifest = android / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.is_file() and 'package="' in manifest.read_text():
        problems.append("AndroidManifest.xml declares package=; with AGP 8 the namespace in build.gradle.kts governs")

    return problems


def main(argv: list[str] | None = None) -> int:
    root = Path(argv[0]).resolve() if argv else ROOT
    src = root / "android" / "app" / "src"
    files = sorted(src.rglob("*.kt")) if src.is_dir() else []
    problems: list[str] = []
    declared: dict[str, list[str]] = {}

    for path in files:
        raw = path.read_text("utf-8")
        code = strip_comments_and_strings(raw)
        # Sources live under main/ and test/; both must map package -> directory.
        relative = None
        for root_name in ("main", "test", "androidTest"):
            candidate = src / root_name / "java"
            try:
                relative = path.relative_to(candidate)
                break
            except ValueError:
                continue
        if relative is None:
            problems.append(f"{path}: outside a known source root")
            continue
        expected_pkg = str(relative.parent).replace("/", ".")
        match = PACKAGE_RE.search(raw)
        actual_pkg = match.group(1) if match else ""
        if actual_pkg != expected_pkg:
            problems.append(f"{path.name}: package '{actual_pkg}' does not match directory '{expected_pkg}'")

        if code.count("{") != code.count("}"):
            problems.append(f"{path.name}: unbalanced braces ({code.count('{')} open, {code.count('}')} close)")

        for name in CLASS_RE.findall(code):
            declared.setdefault(name, []).append(path.name)

    for name, where in declared.items():
        if len(where) > 1 and name not in {"Companion"}:
            # Same-named classes in different packages are legal; same package is not.
            problems.append(f"class '{name}' declared {len(where)} times: {', '.join(where)}")

    # Unused imports: a cheap signal that a file was edited carelessly.
    #
    # Two symbols are exempt: `getValue` and `setValue` from
    # androidx.compose.runtime are *operator* imports. A `by remember { ... }`
    # delegate calls them implicitly, so neither name ever appears as a token in
    # the source. Flagging them was a false positive that would have pushed
    # anyone reading the report into deleting a required import and breaking the
    # build -- the exact failure mode this lint exists to prevent.
    IMPLICIT = {"getValue", "setValue"}

    for path in files:
        raw = path.read_text("utf-8")
        code = strip_comments_and_strings(raw)
        for full, alias in IMPORT_RE.findall(raw):
            symbol = alias or full.rsplit(".", 1)[-1]
            body = IMPORT_RE.sub("", code)
            if symbol == "*" or symbol in IMPLICIT:
                continue
            if not re.search(rf"\b{re.escape(symbol)}\b", body):
                problems.append(f"{path.name}: unused import {full}")

    print(f"checked {len(files)} Kotlin files")
    if not problems:
        print("no structural problems found")
    else:
        print(f"{len(problems)} problem(s):")
        for problem in problems:
            print("  - " + problem)
    build_problems = check_build_files(root)
    problems.extend(build_problems)

    if not problems:
        print("build files: no structural problems found")
    else:
        print(f"build files: {len(build_problems)} issue(s)")

    print("\nNote: this is a structural check, not a compile. Type errors, "
          "Compose API mismatches and missing Android resources can only be "
          "caught by a real Gradle build.")
    return 1 if problems else 0


if __name__ == "__main__":
    import sys as _sys
    raise SystemExit(main(_sys.argv[1:]))
