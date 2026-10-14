#!/usr/bin/env python3
"""Classify a published licence text, so a repository can be carried on its *text* rather than only
on GitHub's detector.

The sealed component may redistribute a factory's files only when its licence permits it. Until now
that decision came from GitHub's `license.spdx_id`, which answers `NOASSERTION` for a licence it cannot
classify - including plain MIT texts with a replaced copyright line, or without a title - and that cost
the component real content: `gratefuljinx77-speckit-claude` publishes 15 files of agent and skill
definitions and carries none of them because the detector shrugged.

This module reads the licence text itself. It is deliberately conservative in three ways:

* a permissive licence is recognised only by the canonical phrases of that licence, and the phrase that
  matched is returned as evidence, so a claim can always be checked against the text;
* anything that limits redistribution is a *blocker* and wins over a permissive phrase: copyleft
  (GNU GPL/AGPL/LGPL), source-available licences (Elastic 2.0, Polyform, BSL, SSPL), non-commercial
  terms, Commons Clause, enterprise-edition carve-outs, and repositories whose licence file announces a
  *transition* or mixes in a documentation licence this component does not carry (CC-BY);
* two permissive licences named in one text is ambiguous, and ambiguity withholds content rather than
  guessing, because the safe direction is always "carry less".

`classify` is pure: it takes text and returns a verdict. Nothing here touches the network or the disk.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

#: The licences whose canonical grant is distinctive enough to recognise from the text alone. Each
#: entry is (spdx, [phrases that must all appear], the phrase quoted as evidence).
PERMISSIVE_MARKERS: tuple[tuple[str, tuple[str, ...], str], ...] = (
    ("MIT", (r"permission is hereby granted, free of charge",),
     "Permission is hereby granted, free of charge"),
    ("Apache-2.0", (r"apache license", r"version 2\.0"),
     "Apache License, Version 2.0"),
    ("BSD-3-Clause", (r"redistribution and use in source and binary forms", r"neither the name"),
     "Redistribution and use in source and binary forms ... neither the name"),
    ("BSD-2-Clause", (r"redistribution and use in source and binary forms",),
     "Redistribution and use in source and binary forms"),
    ("ISC", (r"permission to use, copy, modify, and/or distribute this software",),
     "Permission to use, copy, modify, and/or distribute this software"),
    ("Unlicense", (r"free and unencumbered software released into the public domain",),
     "free and unencumbered software released into the public domain"),
    ("CC0-1.0", (r"cc0 1\.0 universal",), "CC0 1.0 Universal"),
)

#: Terms that mean this component must not redistribute the repository's content, whatever else the
#: text says. Order matters only for the message; the first match is reported.
BLOCKERS: tuple[tuple[str, str], ...] = (
    ("AGPL-3.0", r"gnu affero general public license"),
    ("GPL-3.0", r"gnu general public license"),
    ("LGPL", r"gnu lesser general public license"),
    ("Elastic-2.0", r"elastic license"),
    ("Polyform", r"polyform"),
    ("BSL-1.1", r"business source license"),
    ("SSPL", r"server side public license"),
    ("non-commercial", r"non-?commercial"),
    ("Commons-Clause", r"commons clause"),
    ("enterprise-edition", r"enterprise edition license"),
    ("mixed-with-docs-licence", r"cc-by(-sa)?[- ]?4\.0|attribution 4\.0 international|"
                                r"creative commons attribution 4\.0"),
    ("licence-transition", r"licensing transition"),
)

#: `MIT` in a dual-licence sentence ("under the MIT License or the Apache License") still leaves one
#: obligation, so a text that names two permissive licences with the word "or"/"and" is ambiguous.
DUAL_LICENCE = re.compile(r"\b(mi?t|apache|bsd|isc)\b[^\n]{0,40}\b(or|and|dual)\b", re.I)


def classify(text: str) -> dict:
    """Return {"spdx", "evidence", "blocked_by", "note"} for one licence text.

    `spdx` is empty when the text is not recognised, blocked, or ambiguous; `blocked_by` names the
    blocker when one matched; `note` explains an unclassifiable but not blocked text.
    """
    if not text or not text.strip():
        return {"spdx": "", "evidence": "", "blocked_by": "", "note": "the licence text is empty"}
    low = text.lower()
    for name, pattern in BLOCKERS:
        if re.search(pattern, low):
            return {"spdx": "", "evidence": "", "blocked_by": name,
                    "note": f"the text carries a {name} term, which limits redistribution"}
    matched = [(spdx, evidence) for spdx, phrases, evidence in PERMISSIVE_MARKERS
               if all(re.search(phrase, low) for phrase in phrases)]
    # BSD-3-Clause is BSD-2-Clause plus the no-endorsement clause, so a BSD-3 text always matches both
    # markers. Keeping the more specific grant is not a guess - the narrower marker is a strict subset
    # of the text the wider one already matched.
    names = {spdx for spdx, _ in matched}
    if {"BSD-3-Clause", "BSD-2-Clause"} <= names:
        matched = [pair for pair in matched if pair[0] != "BSD-2-Clause"]
    if len(matched) > 1:
        names = ", ".join(spdx for spdx, _ in matched)
        return {"spdx": "", "evidence": "", "blocked_by": "",
                "note": f"the text matches more than one permissive licence ({names}); withheld rather "
                        f"than guessed"}
    if not matched:
        return {"spdx": "", "evidence": "", "blocked_by": "",
                "note": "the text matches no licence this component can classify"}
    if DUAL_LICENCE.search(text) and len(re.findall(r"permission is hereby granted", low)) < 2:
        return {"spdx": "", "evidence": "", "blocked_by": "",
                "note": "the text reads as a dual-licence grant; withheld rather than guessed"}
    spdx, evidence = matched[0]
    return {"spdx": spdx, "evidence": evidence, "blocked_by": "", "note": ""}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Classify a licence text")
    parser.add_argument("path", help="a licence file to classify")
    args = parser.parse_args(argv[1:])
    text = Path(args.path).read_text(encoding="utf-8", errors="replace")
    print(json.dumps(classify(text), indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
