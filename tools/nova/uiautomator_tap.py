#!/usr/bin/env python3
"""Find a UIAutomator node by text and print its centre coordinates."""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml")
    parser.add_argument("--text", required=True)
    args = parser.parse_args()
    root = ET.parse(Path(args.xml)).getroot()
    wanted = args.text.casefold()
    candidates = []
    for node in root.iter("node"):
        text = node.attrib.get("text", "")
        desc = node.attrib.get("content-desc", "")
        haystack = f"{text} {desc}".casefold()
        if wanted not in haystack:
            continue
        match = BOUNDS.match(node.attrib.get("bounds", ""))
        if not match:
            continue
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top:
            continue
        exact = wanted == text.casefold() or wanted == desc.casefold()
        clickable = node.attrib.get("clickable") == "true"
        area = (right - left) * (bottom - top)
        candidates.append((not exact, not clickable, area, (left + right) // 2, (top + bottom) // 2, text, desc))
    if not candidates:
        print(f"UI node not found: {args.text!r}", file=sys.stderr)
        return 1
    _, _, _, x, y, text, desc = sorted(candidates)[0]
    print(f"{x} {y}")
    print(f"matched text={text!r} desc={desc!r}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
