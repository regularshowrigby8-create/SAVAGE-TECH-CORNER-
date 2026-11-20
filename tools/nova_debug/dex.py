"""A small, dependency-free reader for the Android containers Nova ships.

Why this exists: the two committed artefacts are R8-minified release builds with
no ``mapping.txt`` beside them, so a crash report from a phone shows
``dev.nova.tv.finder.pool.a.b(...)`` and the viewer has no way back to a source
file. Everything here is read straight from the APK with the standard library:

  * ``AndroidManifest.xml`` (compiled, binary XML) -> package, activities, sdk
  * ``classes*.dex`` -> classes, method signatures, string constants
  * the zip entries -> whether native libs, mapping files, signatures exist

Only what is needed is parsed. No third-party packages, no Android SDK.

Reference for the formats: Android source ``frameworks/base/libs/androidfw``
(``ResourceTypes.h`` for the binary XML chunk layout) and the Dalvik
``dex-format`` document.
"""

from __future__ import annotations

import hashlib
import re
import struct
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Binary XML (AXML)
# ---------------------------------------------------------------------------

_RES_STRING_POOL = 0x0001
_RES_XML = 0x0003
_RES_XML_START_ELEMENT = 0x0102
_RES_XML_ATTRIBUTE = 0x0102
_TYPE_STRING = 0x03
_TYPE_INT_DEC = 0x10
_TYPE_INT_HEX = 0x11
_TYPE_INT_BOOLEAN = 0x12


def _pool_strings(data: bytes, start: int, header_size: int) -> List[str]:
    """Decode a ``ResStringPool`` chunk that begins at ``start``."""
    chunk_type, chunk_header, chunk_size = struct.unpack_from("<HHI", data, start)
    string_count, style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", data, start + 8
    )
    out: List[str] = []
    utf8 = bool(flags & 0x100)
    offsets_at = start + chunk_header
    for i in range(string_count):
        off, = struct.unpack_from("<I", data, offsets_at + 4 * i)
        p = start + strings_start + off
        if utf8:
            # u8 length (|0x80 => two bytes), then a second u8 byte-length.
            n = data[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p]
                p += 1
            n2 = data[p]
            p += 1
            if n2 & 0x80:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n, = struct.unpack_from("<H", data, p)
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def parse_axml(data: bytes) -> dict:
    """Decode the parts of a compiled AndroidManifest.xml Nova is checked against."""
    if len(data) < 8:
        raise ValueError("manifest too short")
    ftype, fheader, fsize = struct.unpack_from("<HHI", data, 0)
    if ftype != _RES_XML:
        raise ValueError(f"not a binary XML document (type 0x{ftype:04x})")
    strings: List[str] = []
    elements: List[dict] = []
    offset = fheader
    while offset + 8 <= min(fsize, len(data)):
        ctype, cheader, csize = struct.unpack_from("<HHI", data, offset)
        if csize == 0:
            break
        if ctype == _RES_STRING_POOL:
            strings = _pool_strings(data, offset, cheader)
        elif ctype == _RES_XML_START_ELEMENT:
            # ResXMLTree_node.header is followed by lineNumber/comment (8 bytes),
            # then ResXMLTree_attrExt: ns, name, attributeStart, attributeSize,
            # attributeCount... `attributeStart` counts from the attrExt origin.
            _ns, name_idx, attr_start, attr_size, attr_count = struct.unpack_from(
                "<IIHHH", data, offset + 16
            )
            name = strings[name_idx] if name_idx < len(strings) else ""
            attrs: Dict[str, str] = {}
            base = offset + 16 + attr_start
            for i in range(attr_count):
                at = base + i * attr_size
                _a_ns, a_name, a_raw, _vsize, _res0, vtype, vdata = struct.unpack_from(
                    "<IIIHBBI", data, at
                )
                label = strings[a_name] if a_name < len(strings) else f"attr{a_name}"
                if a_raw and a_raw < len(strings):
                    value = strings[a_raw]
                elif vtype == _TYPE_STRING and vdata < len(strings):
                    value = strings[vdata]
                elif vtype == _TYPE_INT_BOOLEAN:
                    value = "true" if vdata else "false"
                elif vtype in (_TYPE_INT_DEC, _TYPE_INT_HEX):
                    value = str(vdata)
                else:
                    value = f"0x{vdata:08x}"
                attrs[label] = value
            elements.append({"name": name, "attributes": attrs})
        offset += csize

    app = next((e for e in elements if e["name"] == "application"), {"attributes": {}})
    uses_sdk = next((e for e in elements if e["name"] == "uses-sdk"), {"attributes": {}})
    activity_names = [
        e["attributes"].get("name", "")
        for e in elements
        if e["name"] in ("activity", "activity-alias")
    ]
    permissions = [
        e["attributes"].get("name", "")
        for e in elements
        if e["name"] in ("uses-permission", "uses-permission-sdk-23")
    ]
    features = [
        {"name": e["attributes"].get("name", ""), "required": e["attributes"].get("required", "")}
        for e in elements
        if e["name"] == "uses-feature"
    ]
    return {
        "package": "",
        "application": app["attributes"],
        "min_sdk": uses_sdk["attributes"].get("minSdkVersion", ""),
        "target_sdk": uses_sdk["attributes"].get("targetSdkVersion", ""),
        "activities": activity_names,
        "permissions": permissions,
        "features": features,
        "elements": len(elements),
    }


# ---------------------------------------------------------------------------
# DEX
# ---------------------------------------------------------------------------

def _uleb128(data: bytes, offset: int) -> Tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


@dataclass
class Dex:
    """The subset of a dex file needed to name classes and their members."""

    path: str
    strings: List[str]
    types: List[str]
    methods: List[Tuple[str, str, str]]   # (declaring type, name, proto)
    classes: List[str]
    class_access: Dict[str, int]
    class_methods: Dict[str, List[str]]
    raw_bytes: int
    sha256: str

    def has_class(self, descriptor: str) -> bool:
        return descriptor in self.class_access or descriptor in set(self.classes)


def parse_dex(data: bytes, path: str = "") -> Dex:
    if data[:4] != b"dex\n":
        raise ValueError(f"{path}: not a dex file")
    h = struct.unpack_from("<30I", data, 0)
    string_ids_size, string_ids_off = h[14], h[15]
    type_ids_size, type_ids_off = h[16], h[17]
    _proto_ids_size, _proto_ids_off = h[18], h[19]
    _field_ids_size, _field_ids_off = h[20], h[21]
    method_ids_size, method_ids_off = h[22], h[23]
    class_defs_size, class_defs_off = h[24], h[25]

    strings: List[str] = []
    for i in range(string_ids_size):
        off, = struct.unpack_from("<I", data, string_ids_off + 4 * i)
        n, p = _uleb128(data, off)
        strings.append(data[p:p + n].decode("utf-8", "replace"))

    types: List[str] = []
    for i in range(type_ids_size):
        idx, = struct.unpack_from("<I", data, type_ids_off + 4 * i)
        types.append(strings[idx] if idx < len(strings) else "")

    methods: List[Tuple[str, str, str]] = []
    for i in range(method_ids_size):
        cls, proto, name = struct.unpack_from("<HHI", data, method_ids_off + 8 * i)
        methods.append((
            types[cls] if cls < len(types) else "",
            strings[name] if name < len(strings) else "",
            types[proto] if proto < len(types) else "",
        ))

    classes: List[str] = []
    access: Dict[str, int] = {}
    for i in range(class_defs_size):
        base = class_defs_off + 32 * i
        cls_idx, cls_access, _super, _ifaces, _src, _ann, data_off, _static = struct.unpack_from(
            "<8I", data, base
        )
        descriptor = types[cls_idx] if cls_idx < len(types) else ""
        classes.append(descriptor)
        access[descriptor] = cls_access

    # Method names declared per class: needed to tell "class survived R8" from
    # "the method the finder calls survived R8".
    class_owned: Dict[str, List[str]] = {c: [] for c in classes}
    for declaring, name, _proto in methods:
        if declaring in class_owned:
            class_owned[declaring].append(name)

    return Dex(
        path=path,
        strings=strings,
        types=types,
        methods=methods,
        classes=classes,
        class_access=access,
        class_methods=class_owned,
        raw_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


# ---------------------------------------------------------------------------
# APK
# ---------------------------------------------------------------------------

@dataclass
class Apk:
    path: Path
    sha256: str
    size: int
    manifest: dict
    dex: List[Dex]
    entries: List[str]
    native_libs: List[str] = field(default_factory=list)
    has_mapping: bool = False

    @property
    def package(self) -> str:
        return self.manifest.get("package", "")

    @property
    def all_classes(self) -> Dict[str, int]:
        out: Dict[str, int] = {}
        for d in self.dex:
            out.update(d.class_access)
        return out

    def strings_matching(self, pattern: str) -> List[str]:
        rx = re.compile(pattern)
        seen: List[str] = []
        for d in self.dex:
            for s in d.strings:
                if rx.search(s):
                    seen.append(s)
        return sorted(set(seen))


def read_apk(path: Path) -> Apk:
    raw = path.read_bytes()
    with zipfile.ZipFile(path) as z:
        entries = z.namelist()
        manifest_bytes = z.read("AndroidManifest.xml") if "AndroidManifest.xml" in entries else b""
        manifest = parse_axml(manifest_bytes) if manifest_bytes else {}
        dex_names = sorted(n for n in entries if re.fullmatch(r"classes\d*\.dex", n))
        dex_files = [parse_dex(z.read(n), n) for n in dex_names]
        native = sorted(n for n in entries if n.startswith("lib/") and n.endswith(".so"))
        has_mapping = any("mapping" in n.lower() for n in entries)
    # The zip does not carry the package name in the binary manifest header:
    # the <manifest package=""> attribute is the authority, so it is read above.
    manifest["package"] = _package_name(manifest_bytes)
    return Apk(
        path=path,
        sha256=hashlib.sha256(raw).hexdigest(),
        size=len(raw),
        manifest=manifest,
        dex=dex_files,
        entries=entries,
        native_libs=native,
        has_mapping=has_mapping,
    )


def _package_name(manifest_bytes: bytes) -> str:
    """Pull `<manifest package="...">` out of the binary manifest."""
    try:
        elements = parse_axml(manifest_bytes).get("elements")  # not returned; fall back
    except Exception:
        elements = None
    strings: List[str] = []
    offset = 8
    while offset + 8 <= len(manifest_bytes):
        ctype, cheader, csize = struct.unpack_from("<HHI", manifest_bytes, offset)
        if csize == 0:
            break
        if ctype == _RES_STRING_POOL:
            strings = _pool_strings(manifest_bytes, offset, cheader)
            break
        offset += csize
    if not strings:
        return ""
    # Conservative: the first string that looks like a reverse-domain package.
    rx = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$")
    for s in strings:
        if rx.match(s) and not s.startswith("android") and not s.startswith("http"):
            return s
    return ""


def descriptor(internal_name: str) -> str:
    """``dev.nova.tv.Foo`` -> ``Ldev/nova/tv/Foo;``"""
    return "L" + internal_name.replace(".", "/") + ";"


def internal_name(descriptor_text: str) -> str:
    text = descriptor_text
    if text.startswith("L") and text.endswith(";"):
        text = text[1:-1]
    return text.replace("/", ".")
