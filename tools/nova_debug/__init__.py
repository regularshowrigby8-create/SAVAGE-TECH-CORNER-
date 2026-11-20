"""Debug tooling for the Nova TV / Nova Mobile Android app.

Two entry points use this package:

  ``debug_nova.py``             -- finds defects in the Nova sources and the two
                                   committed APKs and writes a report.
  ``verify_no_false_green.py``  -- checks that the debugger itself is honest:
                                   every rule detects a planted defect (no
                                   theatre) and stays silent when it should.

Design rules, because a checker that lies is worse than no checker:

* every finding carries a file, a line and the exact text, so it can be
  confirmed by reading one place;
* a rule that cannot decide says WARN, never ERROR;
* nothing here needs a JDK, an emulator or the internet. The committed APKs and
  the Kotlin sources are the evidence.
"""

__all__ = ["findings", "dex", "ktscan", "source_audit", "apk_audit", "cli"]
