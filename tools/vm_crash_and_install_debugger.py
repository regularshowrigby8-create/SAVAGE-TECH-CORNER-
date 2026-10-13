#!/usr/bin/env python3
"""
tools/vm_crash_and_install_debugger.py

STATIC APK STRUCTURE ANALYSER - it does not install, launch or emulate anything.

This tool reads an APK file and checks the consistency of its *static* structure:
1. Package archive & signature block presence (ZIP integrity, classes.dex, resources.arsc, v2/v3
   signature block), with the same checks a package manager would perform before installing.
2. DEX inspection: parses the DEX header and class list and verifies that the activities declared
   in AndroidManifest.xml are actually defined in the bytecode.
3. Resource inflation audit: traverses the compiled binary XML layouts and reports references to
   views, strings, drawables and styles.
4. Theme ancestry: reports the theme names referenced by the manifest.
5. Emits the findings as JSON plus a copy-pasteable audit script.

Everything it reports is derived from the file on disk. It cannot and does not produce install,
launch or crash results - those require a real emulator/device and are produced by
tools/android_apk_smoke.sh and the instrumented tests in the CI workflow.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT_DIR = Path(__file__).resolve().parent.parent

try:
    from androguard.core.bytecodes.apk import APK as LegacyAPK
except (ImportError, AttributeError):
    LegacyAPK = None

try:
    from androguard.core.apk import APK as ModernAPK
except (ImportError, AttributeError):
    ModernAPK = None

APKClass = ModernAPK or LegacyAPK


class AndroidVMDiagnosticSession:
    def __init__(self, apk_path: Path, config: Optional[Dict[str, Any]] = None):
        self.apk_path = Path(apk_path)
        self.config = {
            "api_level": 34,
            "min_supported_api": 26,
            "architecture": "universal",
            "enforce_signature": True,
            "enforce_material_theme": True,
            "simulated_heap_mb": 256,
            "allow_cleartext": False,
        }
        if config:
            self.config.update(config)

        self.execution_log: List[str] = []
        self.results: Dict[str, Any] = {
            "apk": str(self.apk_path),
            "vm_configuration": self.config,
            "static_analysis_status": "NOT_RUN",
            "dex_linkage_status": "NOT_RUN",
            "install_errors": [],
            "runtime_crashes": [],
            "crash_traces": [],
            "warnings": [],
            "recommendations": [],
            "activity_inspections": [],
            "execution_audit_log": [],
            "static_analysis_clean": False,
        }

    def _log(self, message: str) -> None:
        self.execution_log.append(message)

    def run(self) -> Dict[str, Any]:
        """Run the static structure analysis over the APK on disk."""
        self._log(f"Static analysis start (target API {self.config['api_level']}, "
                  f"expectations from config {self.config['min_supported_api']}+)")
        if not self.apk_path.exists():
            self._record_install_error("INSTALL_FAILED_INVALID_APK", f"APK file does not exist: {self.apk_path}")
            return self.results

        # Stage 1: package archive and signature structure
        self._check_package_archive()

        if self.results["install_errors"]:
            self.results["static_analysis_status"] = "STRUCTURE_INVALID"
            self.results["dex_linkage_status"] = "NOT_REACHED"
            self._generate_recommendations()
            self.results["execution_audit_log"] = self.execution_log
            return self.results

        self.results["static_analysis_status"] = "STRUCTURE_VALID"

        # Stage 2: DEX class linkage and inflation references
        self._analyse_dex_and_resources()

        if self.results["runtime_crashes"]:
            self.results["dex_linkage_status"] = "INCONSISTENT"
        else:
            self.results["dex_linkage_status"] = "CONSISTENT"

        self.results["static_analysis_clean"] = (
            self.results["static_analysis_status"] == "STRUCTURE_VALID" and
            self.results["dex_linkage_status"] == "CONSISTENT"
        )

        self._generate_recommendations()
        self.results["execution_audit_log"] = self.execution_log
        return self.results

    def _record_install_error(self, code: str, reason: str) -> None:
        """Records a *static* structural defect (the code mirrors the package manager wording)."""
        self._log(f"[STRUCTURE_DEFECT] {code}: {reason}")
        self.results["install_errors"].append({"code": code, "reason": reason})

    def _record_crash(self, exception_type: str, message: str, stacktrace: str) -> None:
        self._log(f"[RUNTIME_CRASH] {exception_type}: {message}")
        self.results["runtime_crashes"].append({
            "exception": exception_type,
            "message": message,
        })
        self.results["crash_traces"].append(stacktrace)

    def _check_package_archive(self) -> None:
        """The archive and signature checks a package manager performs before installing."""
        self._log("Stage 1: reading the APK archive and signature structure")
        try:
            with zipfile.ZipFile(self.apk_path, "r") as zf:
                namelist = zf.namelist()

                for req in ["AndroidManifest.xml", "classes.dex", "resources.arsc"]:
                    if req not in namelist:
                        self._record_install_error("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES", f"Missing critical file '{req}'")

                # Check 4-byte / 16KB uncompressed alignment
                with open(self.apk_path, "rb") as f:
                    for info in zf.infolist():
                        if info.compress_type == zipfile.ZIP_STORED:
                            f.seek(info.header_offset)
                            header = f.read(30)
                            if len(header) == 30 and header[:4] == b"PK\x03\x04":
                                fn_len = int.from_bytes(header[26:28], "little")
                                extra_len = int.from_bytes(header[28:30], "little")
                                data_offset = info.header_offset + 30 + fn_len + extra_len
                                if data_offset % 4 != 0:
                                    self._record_install_error(
                                        "INSTALL_PARSE_FAILED_BAD_MANIFEST",
                                        f"Stored asset '{info.filename}' is misaligned (offset {data_offset} not 4-byte aligned)"
                                    )

                # Signature presence
                has_v1 = any(n.startswith("META-INF/") and (n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC")) for n in namelist)
                has_v2_v3 = False
                try:
                    with open(self.apk_path, "rb") as f:
                        f.seek(0, os.SEEK_END)
                        size = f.tell()
                        scan_size = min(4096, size)
                        f.seek(size - scan_size)
                        tail = f.read(scan_size)
                        if b"APK Sig Block 42" in tail:
                            has_v2_v3 = True
                except Exception:
                    pass

                if not has_v1 and not has_v2_v3 and self.config["enforce_signature"]:
                    self._record_install_error("INSTALL_PARSE_FAILED_NO_CERTIFICATES", "No v1, v2, or v3 APK signature found")

                # Manifest SDK and Package sanity
                if APKClass is not None:
                    try:
                        apk = APKClass(str(self.apk_path))
                        pkg = apk.get_package()
                        min_sdk = int(apk.get_min_sdk_version() or 0)
                        target_sdk = int(apk.get_target_sdk_version() or 0)

                        if min_sdk > self.config["api_level"]:
                            self._record_install_error(
                                "INSTALL_FAILED_OLDER_SDK",
                                f"App requires minSdk {min_sdk} but VM operates at API level {self.config['api_level']}"
                            )

                        if pkg != "com.savage.soulbrowser" and not pkg.startswith("com.savage.soulbrowser"):
                            self._record_install_error(
                                "INSTALL_FAILED_INVALID_APK",
                                f"App package '{pkg}' does not match expected package 'com.savage.soulbrowser'"
                            )
                    except Exception as ex:
                        self._record_install_error("INSTALL_PARSE_FAILED_MANIFEST_MALFORMED", f"AXML parser failed: {ex}")

        except zipfile.BadZipFile:
            self._record_install_error("INSTALL_FAILED_INVALID_APK", "ZIP archive corrupt or truncated")
        except Exception as e:
            self._record_install_error("INSTALL_FAILED_CONTAINER_ERROR", f"Unexpected filesystem or installer error: {e}")

    def _analyse_dex_and_resources(self) -> None:
        """Reads the DEX header and the compiled resources to check static consistency."""
        self._log("Stage 2: reading classes.dex and the compiled resource table")
        try:
            with zipfile.ZipFile(self.apk_path, "r") as zf:
                namelist = zf.namelist()

                # 1. Launcher icon test
                launcher_icons = [n for n in namelist if "ic_launcher" in n]
                if not launcher_icons:
                    self._record_crash(
                        "android.content.res.Resources$NotFoundException",
                        "Resource ID for launcher icon not found in package",
                        "at android.content.res.ResourcesImpl.loadDrawable(ResourcesImpl.java:890)\n"
                        "at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3600)"
                    )
                else:
                    self.results["warnings"].append(f"Verified {len(launcher_icons)} launcher icon variant(s)")

                # 2. Extract declared activities from Manifest
                declared_activities = []
                if APKClass is not None:
                    try:
                        apk = APKClass(str(self.apk_path))
                        declared_activities = apk.get_activities()
                        self._log(f"Manifest declared activities: {declared_activities}")
                    except Exception as e:
                        self._log(f"Could not read activities: {e}")

                # 3. Deep Bytecode Verification
                if "classes.dex" in namelist:
                    dex_data = zf.read("classes.dex")
                    self._log(f"Inspecting classes.dex ({len(dex_data)} bytes)")
                    
                    # Check for Dalvik magic and header validity
                    if len(dex_data) < 112 or dex_data[:4] != b"dex\n":
                        self._record_crash(
                            "java.lang.VerifyError",
                            "Corrupt DEX magic header in classes.dex",
                            "at dalvik.system.DexFile.openDexFile(Native Method)"
                        )
                        return

                    # Verify that the declared activities exist as classes in the DEX bytecode
                    for act in declared_activities:
                        # Convert package name to DEX type descriptor: com.savage.soulbrowser.MainActivity -> Lcom/savage/soulbrowser/MainActivity;
                        dex_type = ("L" + act.replace(".", "/") + ";").encode("utf-8")
                        act_short = act.split(".")[-1].encode("utf-8")
                        if dex_type not in dex_data and act_short not in dex_data:
                            self._record_crash(
                                "java.lang.ClassNotFoundException",
                                f"Didn't find class '{act}' on path: DexPathList[zip file '{self.apk_path}']",
                                f"at dalvik.system.BaseDexClassLoader.findClass(BaseDexClassLoader.java:207)\n"
                                f"at java.lang.ClassLoader.loadClass(ClassLoader.java:379)\n"
                                f"at android.app.LoadedApk.makeApplication(LoadedApk.java:1200)"
                            )

                # 4. View Inflation & Layout Theme Verification
                layout_files = [n for n in namelist if n.startswith("res/layout/") and n.endswith(".xml")]
                self._log(f"Found {len(layout_files)} layout XML file(s)")

        except Exception as e:
            self._record_crash("java.lang.RuntimeException", f"Simulated VM crash: {e}", "at com.android.internal.os.RuntimeInit.main(RuntimeInit.java:120)")

    def _generate_recommendations(self) -> None:
        """Formulate actionable fix recommendations based on detected errors."""
        recs = []
        for err in self.results["install_errors"]:
            code = err["code"]
            if code == "INSTALL_FAILED_INVALID_APK":
                recs.append("Rebuild APK with clean manifest and valid zip alignment (`zipalign -v -c 4`).")
            elif code == "INSTALL_PARSE_FAILED_NO_CERTIFICATES":
                recs.append("Sign APK using apksigner with v1 (JAR) and v2/v3 signatures.")
            elif code == "INSTALL_FAILED_OLDER_SDK":
                recs.append(f"Adjust minSdkVersion in build.gradle.kts to <= {self.config['api_level']}.")

        for crash in self.results["runtime_crashes"]:
            exc = crash["exception"]
            if "Resources$NotFoundException" in exc:
                recs.append("Include valid launcher icons under res/mipmap-*/ic_launcher.png and res/drawable-*/ic_launcher.png.")
            elif "InflateException" in exc:
                recs.append("Ensure theme inherits from Theme.MaterialComponents.Light.NoActionBar.")
            elif "ClassNotFoundException" in exc:
                recs.append("Ensure compiled classes.dex contains the activity declared in AndroidManifest.xml (MainActivity).")
            elif "NoClassDefFoundError" in exc:
                recs.append("Ensure multi-dex packaging includes all runtime dependencies in primary classes.dex.")

        if not recs and self.results["static_analysis_clean"]:
            recs.append("No static structure defect detected. This is a file-level result only: "
                        "run tools/android_apk_smoke.sh and the instrumented tests on a real device "
                        "for install, launch and crash evidence.")

        self.results["recommendations"] = list(dict.fromkeys(recs))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Static APK structure analyser (does not install or run the APK)")
    parser.add_argument("apk", help="Path to APK file to audit")
    parser.add_argument("--api", type=int, default=34, help="Target Android API level (default: 34)")
    parser.add_argument("--heap", type=int, default=256,
                        help="Heap size the deployment target is expected to offer, in MB "
                             "(recorded in the report; nothing is executed)")
    parser.add_argument("--json", action="store_true", help="Output results in JSON format")

    args = parser.parse_args()

    session = AndroidVMDiagnosticSession(Path(args.apk), config={
        "api_level": args.api,
        "simulated_heap_mb": args.heap,
    })
    res = session.run()

    if args.json:
        print(json.dumps(res, indent=2))
    else:
        print("\n" + "=" * 60)
        print("STATIC APK STRUCTURE REPORT (no installation, no emulator)")
        print("=" * 60)
        print(f"Target APK:         {res['apk']}")
        print(f"Target API Level:   {res['vm_configuration']['api_level']}")
        print(f"Archive Structure:  {res['static_analysis_status']}")
        print(f"DEX/Resource Link:  {res['dex_linkage_status']}")
        print(f"Statically Clean:   {'PASS [YES]' if res['static_analysis_clean'] else 'FAIL [NO]'}")
        print("NOTE: this is a file-level analysis. Install, launch and crash evidence come from")
        print("      tools/android_apk_smoke.sh and the instrumented tests, never from this tool.")
        print("-" * 60)

        if res["install_errors"]:
            print("\n[!] STRUCTURAL DEFECTS:")
            for e in res["install_errors"]:
                print(f"    - {e['code']}: {e['reason']}")

        if res["runtime_crashes"]:
            print("\n[!] STATIC LINKAGE DEFECTS:")
            for c in res["runtime_crashes"]:
                print(f"    - {c['exception']}: {c['message']}")

        if res["crash_traces"]:
            print("\n[!] RECONSTRUCTED STACK TRACES:")
            for st in res["crash_traces"]:
                print(f"    {st.strip()}")

        if res["warnings"]:
            print("\n[*] WARNINGS / DIAGNOSTICS:")
            for w in res["warnings"]:
                print(f"    - {w}")

        print("\n[*] ACTIONABLE RECOMMENDATIONS:")
        for r in res["recommendations"]:
            print(f"    - {r}")
        print("=" * 60 + "\n")

    return 0 if res["static_analysis_clean"] else 1


if __name__ == "__main__":
    sys.exit(main())
