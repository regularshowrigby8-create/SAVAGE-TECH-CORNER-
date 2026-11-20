"""Rules that read the two shipped APKs.

These answer questions the source cannot: what is actually inside the artefact
the user installs, what identity it has, and whether it can be debugged at all
once it crashes on a device.

The findings here are facts, not opinions -- every one is read from the zip, the
binary manifest or the dex tables, and the report quotes the value found.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from . import dex
from .findings import ERROR, INFO, WARN, Finding


@dataclass
class PackageFacts:
    path: Path
    apk: dex.Apk

    @property
    def label(self) -> str:
        return f"apk:{self.path.name}"


def read_packages(paths: Sequence[Path]) -> List[PackageFacts]:
    return [PackageFacts(path=p, apk=dex.read_apk(p)) for p in paths]


# ---------------------------------------------------------------------------
# Identity and installability
# ---------------------------------------------------------------------------

def rule_identity(facts: PackageFacts) -> List[Finding]:
    out: List[Finding] = []
    m = facts.apk.manifest
    label = facts.label
    pkg = m.get("package", "")
    if not pkg:
        out.append(Finding(id="APK_NO_PACKAGE", severity=ERROR, file=label, line=0,
                           message="the binary manifest declares no package name",
                           rule="package identity"))
    app_name = m.get("application", {}).get("name", "")
    if not app_name:
        out.append(Finding(id="APK_NO_APPLICATION", severity=ERROR, file=label, line=0,
                           message="no android:name on <application>: NovaApplication is never constructed",
                           rule="application class"))
    activities = m.get("activities", [])
    if not activities:
        out.append(Finding(id="APK_NO_LAUNCHER", severity=ERROR, file=label, line=0,
                           message="no <activity> declared: nothing can be launched",
                           rule="launcher"))
    out.append(Finding(
        id="APK_IDENTITY", severity=INFO, file=label, line=0,
        message=(f"package {pkg or '?'}, application {app_name or '?'}, "
                 f"activities {', '.join(activities) or 'none'}, "
                 f"sdk {m.get('min_sdk', '?')}-{m.get('target_sdk', '?')}, {facts.apk.size} bytes, "
                 f"sha256 {facts.apk.sha256[:16]}…"),
        evidence="\n".join(f"{p}" for p in m.get("permissions", [])),
        rule="package identity",
    ))
    return out


def rule_native_libs(facts: PackageFacts) -> List[Finding]:
    """FFmpeg is only useful if its ABI folder is present for the device."""
    out: List[Finding] = []
    libs = facts.apk.native_libs
    if not any("ffmpeg" in l for l in libs):
        return out
    abis = sorted({l.split("/")[1] for l in libs})
    out.append(Finding(
        id="APK_NATIVE_LIBS", severity=INFO, file=facts.label, line=0,
        message=f"bundled native libraries for {', '.join(abis)} ({len(libs)} files)",
        evidence="\n".join(libs[:12]),
        rule="native libraries",
    ))
    return out


# ---------------------------------------------------------------------------
# Debuggability -- the reason a crash report is unreadable today
# ---------------------------------------------------------------------------

def rule_debuggability(facts: PackageFacts) -> List[Finding]:
    """Can a stack trace from this APK be mapped back to source?

    A minified release build without its ``mapping.txt`` turns every frame into
    ``dev.nova.tv.finder.pool.a.b()``. That is not a build problem until
    something crashes, which is exactly the situation this report exists for.
    """
    out: List[Finding] = []
    if not facts.apk.has_mapping:
        out.append(Finding(
            id="APK_NO_MAPPING", severity=WARN, file=facts.label, line=0,
            message=("minified release with no mapping.txt next to it: an obfuscated stack "
                     "trace from this APK cannot be traced back to a source file"),
            rule="debuggability",
            fix=("Publish app/build/outputs/mapping/*/mapping.txt beside the APK (and keep it "
                 "for the build), or retrace with R8 before reading a crash report."),
        ))
    return out


# ---------------------------------------------------------------------------
# Where the pool code actually is: names R8 kept
# ---------------------------------------------------------------------------

POOL_MARKERS = ("server_pool.json", "NovaTV-Server-Pool/1.0")


def rule_pool_presence(facts: PackageFacts) -> List[Finding]:
    """Is the server pool and its harvester in the shipped artifact?

    R8 is told to keep serializers, which is why ``PoolEntry`` survives under its
    real name while the harvester does not. This check records which of the two
    happened, so "the pool is in the build" is never assumed.
    """
    out: List[Finding] = []
    strings = set()
    for d in facts.apk.dex:
        strings.update(d.strings)
    present = [m for m in POOL_MARKERS if m in strings]
    if not present:
        out.append(Finding(
            id="APK_NO_POOL", severity=ERROR, file=facts.label, line=0,
            message="the server-pool code is absent from this APK: the pool cannot be filled",
            rule="pool presence",
        ))
        return out
    out.append(Finding(
        id="APK_POOL_PRESENT", severity=INFO, file=facts.label, line=0,
        message=f"server pool present in the artifact (markers: {', '.join(present)})",
        rule="pool presence",
    ))
    return out


# ---------------------------------------------------------------------------
# Harvester reachability: the URLs the crawler will actually ask
# ---------------------------------------------------------------------------

_DIRECTORY_HOSTS = ("stremio-addons.net", "api.strem.io", "v3-cinemeta.strem.io", "api.github.com")


def rule_harvester_endpoints(facts: PackageFacts) -> List[Finding]:
    urls = facts.apk.strings_matching(r"^https://(stremio-addons\.net|api\.strem\.io|v3-cinemeta\.strem\.io|api\.github\.com)")
    if not urls:
        return [Finding(
            id="APK_NO_HARVESTER", severity=ERROR, file=facts.label, line=0,
            message="no directory endpoint strings in the APK: the harvester cannot run",
            rule="harvester endpoints",
        )]
    hosts = sorted({u.split("/")[2] for u in urls})
    return [Finding(
        id="APK_HARVESTER_ENDPOINTS", severity=INFO, file=facts.label, line=0,
        message=f"harvester asks {len(hosts)} hosts: {', '.join(hosts)}",
        evidence="\n".join(sorted(urls)),
        rule="harvester endpoints",
    )]


# ---------------------------------------------------------------------------
# Release drift: the artifacts do not match the sources at HEAD
# ---------------------------------------------------------------------------

def rule_source_drift(facts: PackageFacts, project_files: Sequence[Tuple[str, str]]) -> List[Finding]:
    """Compare the class lists: sources that the APK does not contain.

    Only the direction APK -> source is claimed, and only for Nova's *own*
    top-level classes whose names R8 is known to keep (serialization, the
    Application and the activities). Everything else is renamed by R8, so a
    missing name there proves nothing and is not reported.
    """
    out: List[Finding] = []
    kept_classes = set()
    for d in facts.apk.dex:
        for c in d.classes:
            internal = c[1:-1] if c.startswith("L") else c
            kept_classes.add(internal.replace("/", "."))
    kept_simple = {c.rsplit(".", 1)[-1].split("$")[0] for c in kept_classes}
    app_name = facts.apk.manifest.get("application", {}).get("name", "")
    activities = set(facts.apk.manifest.get("activities", []))
    for name, source_path in project_files:
        if not name.startswith("dev.nova.tv"):
            continue
        simple = name.rsplit(".", 1)[-1]
        if name in kept_classes or simple in kept_simple or name == app_name or name in activities:
            continue
        out.append(Finding(
            id="APK_CLASS_ABSENT", severity=INFO, file=facts.label, line=0,
            message=(f"{name} ({source_path}) is not in the APK under its own name: "
                     f"either R8 renamed it or this APK predates that source"),
            rule="release drift",
        ))
    return out


# ---------------------------------------------------------------------------
# Strings a crash report will need
# ---------------------------------------------------------------------------

def rule_crash_landmarks(facts: PackageFacts) -> List[Finding]:
    """Record what a stack trace from this APK looks like before it happens."""
    out: List[Finding] = []
    renamed = sorted(c for d in facts.apk.dex for c in d.classes
                     if re.fullmatch(r"Ldev/nova/tv/[a-z0-9/]{1,12};", c))
    if renamed:
        out.append(Finding(
            id="APK_RENAMED_HINTS", severity=INFO, file=facts.label, line=0,
            message=(f"{len(renamed)} minified dev.nova.tv classes: a trace showing these "
                     f"names needs the mapping file to be readable"),
            evidence="\n".join(renamed[:12]),
            rule="crash landmarks",
        ))
    return out


def audit(facts_sequence: Sequence[PackageFacts],
          project_files: Sequence[Tuple[str, str]] = ()) -> List[Finding]:
    findings: List[Finding] = []
    for facts in facts_sequence:
        findings += rule_identity(facts)
        findings += rule_native_libs(facts)
        findings += rule_debuggability(facts)
        findings += rule_pool_presence(facts)
        findings += rule_harvester_endpoints(facts)
        findings += rule_crash_landmarks(facts)
        findings += rule_source_drift(facts, project_files)
    return findings
