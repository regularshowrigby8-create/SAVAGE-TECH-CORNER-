"""Tests for the licence text classifier and the re-scan that widened four records.

The re-scan's job is to let a repository speak for its own licence: GitHub's detector answers
`NOASSERTION` for a perfectly ordinary MIT text that lost its title, and four catalogued factories were
wrongly held as blueprints because of it. The danger in a tool like that is the opposite mistake -
reading terms into a text that does not grant them, or overwriting a copyleft licence because a phrase
happened to match. Every test below is written from that direction: the must-fail cases come first.
"""

import json
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import license_rescan  # noqa: E402
import license_text  # noqa: E402

REGISTRIES = ("tools/factory/imported_factories.json",
              "tools/factory/imported_ai_factories.json",
              "tools/factory/imported_mcp_servers.json")

MIT = """MIT License

Copyright (c) 2026 Example

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
"""

MIT_WITHOUT_TITLE = MIT.split("\n", 3)[-1]  # exactly how the four mis-detected repositories look

APACHE = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/
Licensed under the Apache License, Version 2.0 (the "License");
"""

BSD3 = """Redistribution and use in source and binary forms, with or without modification, are
permitted provided that the following conditions are met:
Neither the name of the copyright holder nor the names of its contributors may be used to endorse.
"""

GPL3 = """GNU GENERAL PUBLIC LICENSE
Version 3, 29 June 2007
Everyone is permitted to copy and distribute verbatim copies of this license document.
"""

AGPL3 = """GNU AFFERO GENERAL PUBLIC LICENSE
Version 3, 19 November 2007
"""

ELASTIC = """Elastic License 2.0
You may not provide the software to third parties as a hosted or managed service.
"""

POLYFORM = """PolyForm Shield License 1.0.0
Any purpose other than to compete with the licensor is permitted.
"""

BSL = """Business Source License 1.1
The Licensor hereby grants you the right to copy, modify, and use the Licensed Work.
"""

APACHE_WITH_CCBY_DOCS = APACHE + """
Documentation is licensed under CC-BY-4.0.
"""

DUAL = """This software is released under the MIT License or the Apache License, at your option.
Permission is hereby granted, free of charge, to any person obtaining a copy.
"""

COPYRIGHT_ONLY = "Copyright (c) 2026 Someone. All rights reserved.\n"


class ClassifyTest(unittest.TestCase):
    """What the classifier may say, and - more importantly - what it may not."""

    def test_an_untitled_mit_text_is_still_mit(self):
        # This is the whole reason the re-scan exists: GitHub calls this NOASSERTION.
        result = license_text.classify(MIT_WITHOUT_TITLE)
        self.assertEqual(result["spdx"], "MIT")
        self.assertIn("Permission is hereby granted", result["evidence"])

    def test_a_canonical_text_with_its_title_is_mit(self):
        self.assertEqual(license_text.classify(MIT)["spdx"], "MIT")

    def test_apache_and_bsd3_are_recognised_by_their_own_phrases(self):
        self.assertEqual(license_text.classify(APACHE)["spdx"], "Apache-2.0")
        self.assertEqual(license_text.classify(BSD3)["spdx"], "BSD-3-Clause")

    def test_gpl_and_agpl_texts_are_blocked_never_classified(self):
        for text, name in ((GPL3, "GPL-3.0"), (AGPL3, "AGPL-3.0")):
            with self.subTest(name):
                result = license_text.classify(text)
                self.assertEqual(result["spdx"], "")
                self.assertEqual(result["blocked_by"], name)

    def test_source_available_licences_are_blocked(self):
        for text, name in ((ELASTIC, "Elastic-2.0"), (POLYFORM, "Polyform"), (BSL, "BSL-1.1")):
            with self.subTest(name):
                result = license_text.classify(text)
                self.assertEqual(result["spdx"], "")
                self.assertEqual(result["blocked_by"], name)

    def test_a_permissive_code_licence_mixed_with_cc_by_docs_is_blocked(self):
        # The MCP project's own repositories do this: Apache-2.0 for code, CC-BY-4.0 for prose. The
        # prose is what a factory would copy, so the record may not be widened on the Apache half.
        result = license_text.classify(APACHE_WITH_CCBY_DOCS)
        self.assertEqual(result["spdx"], "")
        self.assertEqual(result["blocked_by"], "mixed-with-docs-licence")

    def test_a_dual_licence_text_is_withheld_not_guessed(self):
        result = license_text.classify(DUAL)
        self.assertEqual(result["spdx"], "")
        self.assertEqual(result["blocked_by"], "")
        self.assertIn("dual-licence", result["note"])

    def test_a_text_that_grants_nothing_is_withheld(self):
        result = license_text.classify(COPYRIGHT_ONLY)
        self.assertEqual(result["spdx"], "")
        self.assertIn("no licence this component can classify", result["note"])

    def test_an_empty_text_is_reported_as_empty(self):
        self.assertIn("empty", license_text.classify("   ")["note"])

    def test_the_classifier_never_returns_a_licence_it_cannot_quote(self):
        # Every positive verdict has to name the phrase that produced it, so a claim in the registry
        # can always be traced back to words in the repository's own file.
        for text in (MIT, MIT_WITHOUT_TITLE, APACHE, BSD3):
            with self.subTest(text.splitlines()[0]):
                result = license_text.classify(text)
                self.assertTrue(result["spdx"])
                self.assertTrue(result["evidence"])
                self.assertTrue(all(word in text for word in result["evidence"].split(" ... ")[0].split()[:4]))


class DecideTest(unittest.TestCase):
    """The rule that governs the registry: widen from nothing, confirm a match, escalate a conflict."""

    def classify(self, text):
        return license_text.classify(text)

    def test_a_record_with_no_licence_may_be_widened(self):
        for recorded in (None, "", "NOASSERTION"):
            with self.subTest(str(recorded)):
                self.assertEqual(license_rescan.decide(recorded, self.classify(MIT)), "widen")

    def test_a_matching_record_is_confirmed(self):
        self.assertEqual(license_rescan.decide("MIT", self.classify(MIT)), "confirmed")

    def test_a_conflicting_record_is_escalated_not_overwritten(self):
        # The one thing this tool must never do: replace a licence the registry already names.
        for recorded in ("GPL-3.0", "AGPL-3.0", "Elastic-2.0", "Apache-2.0"):
            with self.subTest(recorded):
                self.assertEqual(license_rescan.decide(recorded, self.classify(MIT)), "disagrees")

    def test_apply_verdict_leaves_a_disagreeing_entry_alone(self):
        # All a disagreement may do is record where the conflicting text lives, so a person can look.
        entry = {"id": "x", "license": "GPL-3.0", "import_class": "blueprint-only"}
        verdict = {"id": "x", "action": "disagrees", "spdx": "MIT", "evidence": "", "blocked_by": "",
                   "note": "", "found_path": "LICENSE"}
        license_rescan.apply_verdict(entry, verdict)
        self.assertEqual(entry["license"], "GPL-3.0")
        self.assertEqual(entry["import_class"], "blueprint-only")
        self.assertEqual(entry["license_path"], "LICENSE")
        self.assertNotIn("license_detected", entry)

    def test_a_blocked_text_is_recorded_as_blocked_and_never_widened(self):
        entry = {"id": "y", "license": "NOASSERTION", "import_class": "blueprint-only"}
        verdict = license_rescan.verdict_for("NOASSERTION", self.classify(ELASTIC), "LICENSE")
        license_rescan.apply_verdict(entry, verdict)
        self.assertEqual(entry["license"], "NOASSERTION")
        self.assertEqual(entry["license_note"], "blocked: Elastic-2.0")

    def test_widening_a_blueprint_makes_it_carriable(self):
        # A licence that permits reuse means the record's content may now be carried, so the stale
        # blueprint label is corrected in the same write.
        entry = {"id": "z", "license": "NOASSERTION", "import_class": "blueprint-only",
                 "license_file_needed": True}
        verdict = license_rescan.verdict_for("NOASSERTION", self.classify(MIT), "LICENSE")
        verdict["found_path"] = "LICENSE"
        self.assertTrue(license_rescan.apply_verdict(entry, verdict))
        self.assertEqual(entry["license"], "MIT")
        self.assertEqual(entry["import_class"], "asset-import")
        self.assertFalse(entry["license_file_needed"])


class CandidateDiscoveryTest(unittest.TestCase):
    """Which file counts as a repository's licence, and which must never be read as one."""

    def test_a_real_grant_outranks_a_notice(self):
        tree = [{"path": "NOTICE", "type": "blob"}, {"path": "LICENSE", "type": "blob"}]
        self.assertEqual(license_rescan.licence_candidates(tree), ["LICENSE", "NOTICE"])

    def test_a_named_grant_variant_is_recognised(self):
        tree = [{"path": "LICENSE-MIT", "type": "blob"}]
        self.assertEqual(license_rescan.licence_candidates(tree), ["LICENSE-MIT"])

    def test_a_nested_licence_is_never_read_as_the_project_licence(self):
        # fabro ships a rule pack with its own LICENSE deep inside; that licenses the directory, not
        # the repository, and reading it as the project licence would attribute terms nobody published.
        tree = [{"path": ".fabro/workflows/code-review/rules/builtin/LICENSE", "type": "blob"}]
        self.assertEqual(license_rescan.licence_candidates(tree), [])

    def test_a_directory_named_like_a_licence_is_not_a_candidate(self):
        tree = [{"path": "LICENSES", "type": "tree"}]
        self.assertEqual(license_rescan.licence_candidates(tree), [])


class RegistryConsistencyTest(unittest.TestCase):
    """The registries and the licence files the seal carries have to tell the same story."""

    def entries(self):
        for relative in REGISTRIES:
            payload = json.loads((REPO_ROOT / relative).read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                payload = payload.get("factories") or payload.get("servers") or []
            for entry in payload:
                yield relative, entry

    def carried_content(self) -> dict:
        """The factories the seal says it redistributes content for, by id."""
        seal = json.loads((REPO_ROOT / "sealed-factories" / "SEAL.json").read_text(encoding="utf-8"))
        return {factory["id"]: factory for factory in seal["factories"]
                if factory["content_redistributed"]}

    def test_every_factory_whose_content_is_carried_ships_a_licence_text_that_grants_it(self):
        # The safety property of the whole component, re-derived offline: read the licence bytes the
        # seal carries next to the content and classify them again. A factory that says MIT while
        # shipping a copyleft text is caught here without trusting the earlier scan.
        licences = REPO_ROOT / "sealed-factories" / "licenses"
        carried = self.carried_content()
        checked = 0
        for relative, entry in self.entries():
            if entry["id"] not in carried:
                continue
            matches = sorted(licences.glob(f'{entry["id"]}-*'))
            self.assertTrue(matches, f'{relative}: {entry["id"]} carries content but no licence text')
            result = license_text.classify(matches[0].read_text(encoding="utf-8", errors="replace"))
            checked += 1
            self.assertEqual(result["spdx"], entry["license"],
                             f'{relative}: {entry["id"]} carries content under a text classified '
                             f'{result["spdx"] or result["blocked_by"] or result["note"]} while the '
                             f'registry claims {entry["license"]}')
        self.assertGreater(checked, 100, "the seal carries content; none of it was re-checked")

    def test_a_record_ships_no_content_when_its_licence_text_is_one_the_classifier_refuses(self):
        # autogen publishes CC-BY-4.0 - a documentation licence - for a code repository. The record
        # exists as a blueprint; nothing of it may be carried.
        carried = self.carried_content()
        refused = []
        licences = REPO_ROOT / "sealed-factories" / "licenses"
        for relative, entry in self.entries():
            for path in sorted(licences.glob(f'{entry["id"]}-*')):
                result = license_text.classify(path.read_text(encoding="utf-8", errors="replace"))
                if not result["spdx"]:
                    refused.append(entry["id"])
                    self.assertNotIn(entry["id"], carried,
                                     f'{entry["id"]} carries content under a licence text this '
                                     f'component may not redistribute')
        self.assertTrue(refused, "at least one catalogued repository publishes a licence the seal "
                                 "cannot carry; the guard would otherwise never be exercised")

    def test_no_record_was_widened_against_a_conflicting_licence(self):
        # Every entry whose licence came from the text must still be one the classifier would grant.
        widened = [(relative, entry) for relative, entry in self.entries()
                   if entry.get("license_source") == "text"]
        self.assertTrue(widened, "the re-scan widened four records; none is recorded")
        for relative, entry in widened:
            with self.subTest(entry["id"]):
                self.assertIn(entry["license"], {"MIT", "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause",
                                                 "ISC", "CC0-1.0", "Unlicense"})
                self.assertEqual(entry["import_class"], "asset-import",
                                 f'{relative}: {entry["id"]} was widened but is still a blueprint')


if __name__ == "__main__":
    unittest.main()
