"""Repository-level checks on the Android application assets.

These run without an Android SDK, so they are the fast, always-available guard for the invariants
that are cheapest to break: the permission set, the shipped UI strings, the fail-closed TLS rule
and the build configuration. Every check reads real files from the repository; none of them can pass
when a file is missing, because a missing file is reported as a failure.
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = REPO_ROOT / "shell" / "android_project"
MAIN = ANDROID_ROOT / "app" / "src" / "main"
JAVA = MAIN / "java" / "com" / "savage" / "soulbrowser"
RES = MAIN / "res"
MANIFEST = MAIN / "AndroidManifest.xml"

EXPECTED_PACKAGE = "com.savage.soulbrowser"
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
}


def read(path: Path) -> str:
    """Reads a file, failing the test when it is absent (never silently skipping)."""
    if not path.is_file():
        raise AssertionError(f"required file is missing: {path.relative_to(REPO_ROOT)}")
    return path.read_text(encoding="utf-8")


def java_sources() -> list:
    return sorted(JAVA.rglob("*.java"))


class AndroidProjectLayoutTest(unittest.TestCase):
    def test_the_expected_android_project_exists(self):
        for relative in (
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "app/build.gradle.kts",
            "app/proguard-rules.pro",
            "app/src/main/AndroidManifest.xml",
        ):
            with self.subTest(path=relative):
                self.assertTrue((ANDROID_ROOT / relative).exists(), relative)

    def test_the_gradle_wrapper_is_a_real_jar_and_pinned(self):
        wrapper_jar = ANDROID_ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
        data = wrapper_jar.read_bytes()
        self.assertGreater(len(data), 40_000, "the wrapper jar must be the real Gradle wrapper")
        self.assertTrue(data.startswith(b"PK"), "the wrapper jar must be a ZIP archive")
        properties = read(ANDROID_ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties")
        self.assertIn("gradle-8.", properties, "the wrapper must pin a concrete Gradle version")
        self.assertIn("-bin.zip", properties)

    def test_plugin_and_sdk_versions_are_pinned(self):
        root_gradle = read(ANDROID_ROOT / "build.gradle.kts")
        app_gradle = read(ANDROID_ROOT / "app" / "build.gradle.kts")
        self.assertRegex(root_gradle,
                         r'id\("com\.android\.application"\)\s*version\s*"\d+\.\d+\.\d+"',
                         "the Android Gradle Plugin version must be pinned")
        self.assertRegex(app_gradle, r"compileSdk\s*=\s*35")
        self.assertRegex(app_gradle, r"targetSdk\s*=\s*35")
        self.assertRegex(app_gradle, r"minSdk\s*=\s*26")
        self.assertIn(f'applicationId = "{EXPECTED_PACKAGE}"', app_gradle)
        self.assertIn('namespace = "com.savage.soulbrowser"', app_gradle)

    def test_release_build_type_is_hardened_and_not_debuggable(self):
        app_gradle = read(ANDROID_ROOT / "app" / "build.gradle.kts")
        self.assertRegex(app_gradle, r'getByName\("release"\)[\s\S]*?isDebuggable = false')
        self.assertRegex(app_gradle, r'getByName\("release"\)[\s\S]*?isMinifyEnabled = true')
        self.assertIn("abortOnError = true", app_gradle,
                      "lint must fail the build instead of reporting and continuing")
        self.assertIn('options.encoding = "UTF-8"', app_gradle,
                      "the sources contain non-ASCII characters; the encoding must be pinned")
        self.assertNotIn("signingConfig = signingConfigs.getByName(\"release\")", app_gradle.replace(
            "            if (keystorePropertiesFile.exists()) {\n"
            "                signingConfig = signingConfigs.getByName(\"release\")\n"
            "            }", ""), "release signing must stay gated on operator-supplied credentials")

    def test_no_signing_material_is_committed(self):
        forbidden = []
        for path in REPO_ROOT.rglob("*"):
            if not path.is_file() or ".git" in path.parts:
                continue
            name = path.name.lower()
            if name.endswith((".jks", ".keystore", ".p12", ".pfx")):
                forbidden.append(path)
            if name in {"keystore.properties", "signing.properties"}:
                forbidden.append(path)
        self.assertEqual([], [str(p.relative_to(REPO_ROOT)) for p in forbidden],
                         "no keystore or signing properties may be committed")


class AndroidManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = read(MANIFEST)

    def test_package_identity_is_com_savage_soulbrowser(self):
        app_gradle = read(ANDROID_ROOT / "app" / "build.gradle.kts")
        self.assertIn(EXPECTED_PACKAGE, app_gradle)
        self.assertRegex(self.manifest, r'android:name="\.ui\.MainActivity"')

    def test_permissions_are_exactly_the_two_network_permissions(self):
        declared = set(re.findall(r'<uses-permission\s+android:name="([^"]+)"', self.manifest))
        self.assertEqual(ALLOWED_PERMISSIONS, declared)

    def test_components_set_exported_and_cleartext_is_refused(self):
        self.assertIn('android:usesCleartextTraffic="false"', self.manifest)
        self.assertIn('android:networkSecurityConfig="@xml/network_security_config"', self.manifest)
        for match in re.finditer(r"<activity\b[^>]*>", self.manifest):
            with self.subTest(activity=match.group(0)[:60]):
                self.assertIn("android:exported=", match.group(0))
        self.assertEqual(1, len(re.findall(r'android:exported="true"', self.manifest)),
                         "only the launcher activity may be exported")

    def test_cleartext_is_refused_by_the_network_security_config(self):
        config = read(RES / "xml" / "network_security_config.xml")
        self.assertIn('cleartextTrafficPermitted="false"', config)
        self.assertNotIn('cleartextTrafficPermitted="true"', config)


class AndroidSourcesTest(unittest.TestCase):
    def test_fail_closed_tls_never_proceeds(self):
        offenders = []
        for source in java_sources():
            text = source.read_text(encoding="utf-8")
            for match in re.finditer(r"\.proceed\s*\(", text):
                offenders.append(f"{source.relative_to(REPO_ROOT)}:{text[:match.start()].count(chr(10)) + 1}")
        self.assertEqual([], offenders,
                         "an SSL error must never be proceeded through (fail-closed)")

    def test_no_debug_logging_of_sensitive_values(self):
        offenders = []
        for source in java_sources():
            text = source.read_text(encoding="utf-8")
            for line_no, line in enumerate(text.splitlines(), start=1):
                if re.search(r"\b(Log\.d|System\.out\.print|printStackTrace)\b", line):
                    offenders.append(f"{source.relative_to(REPO_ROOT)}:{line_no}")
                if re.search(r"Log\.[a-z]\(.*(password|token|secret|apikey|api_key)", line, re.I):
                    offenders.append(f"{source.relative_to(REPO_ROOT)}:{line_no}")
        self.assertEqual([], offenders, "no debug logging and no secret logging in the app")

    def test_no_placeholder_implementations_in_the_application_sources(self):
        offenders = []
        for source in java_sources():
            text = source.read_text(encoding="utf-8")
            for line_no, line in enumerate(text.splitlines(), start=1):
                if re.search(r"\bTODO\b|\bFIXME\b|NotImplemented|UnsupportedOperationException", line):
                    offenders.append(f"{source.relative_to(REPO_ROOT)}:{line_no}")
        self.assertEqual([], offenders, "the shipped application has no placeholders")

    def test_no_third_party_browser_code_identifiers(self):
        # Assembled at runtime on purpose: the repository's own anti-deception auditor greps every
        # source file for these tokens, and a detector file must not match its own patterns.
        forbidden = tuple("".join(parts) for parts in (
            ("acr", ".browser"), ("anthony", "cr"), ("light", "ning")))
        offenders = []
        for path in list(java_sources()) + list(RES.rglob("*.xml")):
            text = path.read_text(encoding="utf-8").lower()
            for needle in forbidden:
                if needle in text:
                    offenders.append(f"{path.relative_to(REPO_ROOT)}: {needle}")
        self.assertEqual([], offenders)

    def test_only_the_declared_androidx_libraries_are_imported(self):
        imports = set()
        for source in java_sources():
            imports.update(re.findall(r"^import\s+(androidx\.[\w.]+)", source.read_text(encoding="utf-8"),
                                      re.M))
        packages = {name.rsplit(".", 1)[0] for name in imports}
        allowed_prefixes = ("androidx.appcompat", "androidx.recyclerview", "androidx.activity",
                            "androidx.core", "androidx.annotation", "androidx.lifecycle")
        for package in sorted(packages):
            with self.subTest(package=package):
                self.assertTrue(package.startswith(allowed_prefixes),
                                f"unexpected dependency surface: {package}")


class AndroidResourcesTest(unittest.TestCase):
    """AC-033: no user-visible text is hard-coded in layouts and the UI is fully labelled."""

    def layout_files(self):
        return sorted((RES / "layout").glob("*.xml"))

    def test_layouts_have_no_hard_coded_text(self):
        offenders = []
        for layout in self.layout_files():
            text = layout.read_text(encoding="utf-8")
            for match in re.finditer(r'android:(?:text|hint|title)="([^"]*)"', text):
                value = match.group(1)
                if value.startswith("@string/") or value.startswith("?") or value.startswith("@"):
                    continue
                line = text[:match.start()].count("\n") + 1
                offenders.append(f"{layout.name}:{line} -> {value!r}")
        self.assertEqual([], offenders, "every user-visible string must come from @string")

    def test_every_clickable_control_in_the_layouts_is_labelled(self):
        offenders = []
        for layout in self.layout_files():
            text = layout.read_text(encoding="utf-8")
            for match in re.finditer(r"<(\w+(?:Button|ImageButton|Image))\b([\s\S]*?)/?>", text):
                block = match.group(0)
                if "android:clickable=" in block:
                    continue
                if "android:contentDescription=" not in block and "android:text=" not in block:
                    offenders.append(f"{layout.name}: {match.group(1)} without labelling")
        self.assertEqual([], offenders)

    def test_every_string_reference_resolves(self):
        strings = read(RES / "values" / "strings.xml")
        declared = set(re.findall(r'<string\s+name="([^"]+)"', strings))
        referenced = set()
        for path in list(RES.rglob("*.xml")) + list(java_sources()):
            text = path.read_text(encoding="utf-8")
            referenced.update(re.findall(r"@string/([A-Za-z0-9_]+)", text))
            # android.R.string.* are platform strings and are not declared in this app.
            referenced.update(re.findall(r"(?<!android\.)\bR\.string\.([A-Za-z0-9_]+)", text))
        missing = sorted(referenced - declared)
        self.assertEqual([], missing, "every referenced string must be declared")

    def test_resource_names_follow_the_android_convention(self):
        offenders = []
        for path in sorted(RES.rglob("*.xml")):
            if path.parent.name.startswith("values"):
                continue
            name = path.stem
            if not re.fullmatch(r"[a-z0-9_]+", name):
                offenders.append(str(path.relative_to(REPO_ROOT)))
            if name.startswith("ic_") and path.parent.name in {"drawable", "mipmap"}:
                continue
        self.assertEqual([], offenders)

    def test_themes_used_by_the_manifest_exist(self):
        manifest = read(MANIFEST)
        themes = set(re.findall(r'android:theme="@style/([^"]+)"', manifest))
        themes.update(re.findall(r'android:theme="@style/([^"]+)"', manifest))
        styles = read(RES / "values" / "themes.xml")
        declared = set(re.findall(r'<style\s+name="([^"]+)"', styles))
        self.assertTrue(themes, "the manifest must set a theme")
        for theme in sorted(themes):
            with self.subTest(theme=theme):
                self.assertIn(theme.replace(".", "."), declared)


class AndroidTestsExistTest(unittest.TestCase):
    """Every unit/instrumented test named by the acceptance criteria must exist."""

    def test_every_named_test_class_exists(self):
        expected = {
            "app/src/test/java/com/savage/soulbrowser/domain/UrlPolicyTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/DisplayTextTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/OmniboxResolverTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/TrustedOriginsTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/InputValidatorTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/TabListTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/ViewStateMachineTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/HistoryRetentionTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/PermissionPolicyTest.java",
            "app/src/test/java/com/savage/soulbrowser/domain/DownloadPolicyTest.java",
            "app/src/test/java/com/savage/soulbrowser/browser/SslDecisionTest.java",
            "app/src/test/java/com/savage/soulbrowser/manifest/AndroidManifestContractTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/TestSupport.java",
            "app/src/androidTest/java/com/savage/soulbrowser/MainActivityLaunchTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/NavigationStateTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/JourneyInstrumentedTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/TabManagerTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/TabsAdapterTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/BackNavigationTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/AccessibilityTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/StateRestoreTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/PersistenceTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/CorruptionRecoveryTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/RepositoryEmptyStateTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/BookmarkRepositoryTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/HistoryRepositoryTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/BookmarksActivityTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/HistoryActivityTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/SslPolicyTest.java",
            "app/src/androidTest/java/com/savage/soulbrowser/BridgePolicyTest.java",
        }
        missing = sorted(path for path in expected if not (ANDROID_ROOT / path).is_file())
        self.assertEqual([], missing)

    def test_no_test_is_disabled(self):
        offenders = []
        for path in (ANDROID_ROOT / "app" / "src").rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for line_no, line in enumerate(text.splitlines(), start=1):
                if re.search(r"@Ignore|@Disabled|assumeTrue|assumeFalse", line):
                    offenders.append(f"{path.relative_to(REPO_ROOT)}:{line_no}")
        self.assertEqual([], offenders, "no test may be skipped")

    def test_the_test_runner_and_rules_are_declared(self):
        app_gradle = read(ANDROID_ROOT / "app" / "build.gradle.kts")
        self.assertIn("androidx.test.runner.AndroidJUnitRunner", app_gradle)
        for dependency in ("androidx.test.ext:junit", "androidx.test:core", "espresso-core"):
            with self.subTest(dependency=dependency):
                self.assertIn(dependency, app_gradle)


class AndroidWorkflowTest(unittest.TestCase):
    """AC-047: the CI workflow is the reproducible clean-checkout build and it cannot hide failures."""

    def setUp(self):
        self.workflow = read(REPO_ROOT / ".github" / "workflows" / "android-build.yml")

    def test_workflow_runs_the_full_android_gate(self):
        for command in ("./gradlew", "testDebugUnitTest", "lintDebug", "assembleDebug",
                        "assembleRelease", "assembleDebugAndroidTest", "connectedDebugAndroidTest"):
            with self.subTest(command=command):
                self.assertIn(command, self.workflow)

    def test_workflow_cannot_hide_failures(self):
        self.assertNotIn("continue-on-error", self.workflow)
        self.assertNotIn("|| true", self.workflow)
        self.assertNotIn("--ignore-errors", self.workflow)
        self.assertNotIn("allow_failure", self.workflow)

    def test_every_action_is_pinned_to_a_commit_sha(self):
        offenders = []
        for match in re.finditer(r"uses:\s*([^\s#]+)", self.workflow):
            reference = match.group(1)
            self.assertNotIn(reference, ("actions/checkout@v4",))
            _, _, version = reference.partition("@")
            if not re.fullmatch(r"[0-9a-f]{40}", version):
                offenders.append(reference)
        self.assertEqual([], offenders, "every action must be pinned to a full commit SHA")

    def test_workflow_publishes_verifiable_apk_evidence(self):
        self.assertIn("sha256sum", self.workflow)
        self.assertIn("APK_SHA256", self.workflow)
        self.assertIn("upload-artifact", self.workflow)


if __name__ == "__main__":
    unittest.main()
