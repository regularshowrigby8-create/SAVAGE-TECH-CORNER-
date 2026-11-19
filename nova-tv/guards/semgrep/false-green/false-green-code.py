# Fixture for `semgrep --test` (Python false-green rule).
def bad(findings):
    # ruleid: hardcoded-ok-true
    return {"findings": findings, "ok": True}


def good(findings):
    # ok: hardcoded-ok-true
    return {"findings": findings, "ok": not findings}
