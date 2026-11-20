"""Catch claims that are not backed by evidence.

The failure this prevents is specific and I have made it repeatedly: asserting
that a repository, licence, version or capability exists without having looked.
The fix is not "be more careful" -- it is to make every claim carry the thing
that proves it, and to fail when it does not.

Two layers, in this order:

1. **Deterministic checks** -- text against the filesystem, or a claim against
   the live GitHub API. These are facts and they decide the outcome.

2. **Model cross-checks** (`nova.zerokey`) -- four independent models asked the
   same question. Used only for claims facts cannot settle, and recorded as
   model opinion, never as proof.

A claim is only "ok" when the deterministic layer proves it. Model agreement
cannot turn an unproven claim into a proven one; it can only add suspicion to a
claim that otherwise passed.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# --- claim kinds -------------------------------------------------------------

# "in `owner/repo`" style references: a name that looks like a GitHub repo.
REPO_REF = re.compile(r"\b([A-Za-z0-9][\w.-]*)/([A-Za-z0-9][\w.-]*)\b")
# A version pin claim: `1.2.3`, `v1.2.3`, `1.0.0-alpha11`
VERSION = re.compile(r"\bv?\d+\.\d+(?:\.\d+)?(?:-[A-Za-z0-9.]+)?\b")
# File references the text asserts exist.
FILE_REF = re.compile(r"`([\w./-]+\.(?:kt|py|js|ts|md|yml|yaml|json|toml|xml|gradle|kts))`")


@dataclass
class Claim:
    text: str
    source: str
    line: int


@dataclass
class Result:
    claim: Claim
    verdict: str          # "proven" | "contradicted" | "unverified"
    why: str
    evidence: str = ""
    model_consensus: Optional[Dict[str, Any]] = None

    @property
    def ok(self) -> bool:
        return self.verdict == "proven"

    def as_dict(self) -> Dict[str, Any]:
        return {
            "claim": self.claim.text,
            "source": f"{self.claim.source}:{self.claim.line}",
            "verdict": self.verdict,
            "why": self.why,
            "evidence": self.evidence,
            "model_consensus": self.model_consensus,
        }


# --- layer 1: deterministic ------------------------------------------------

# A backticked token that is a bare filename with no directory part is a weak
# claim (`AddonClient.kt` is shorthand, not a path), so only paths are checked.
PATHLIKE = re.compile(r"^[\w.-]+(?:/[\w.-]+)+$")

# Words that appear as owner/repo-shaped tokens but are prose, not repositories.
NOT_OWNERS = {
    "http", "https", "www", "github", "docs", "api", "app", "src", "lib",
    "merge", "runs", "and", "or", "the", "a", "one", "two", "see", "e.g",
}


def extract_claims(text: str, source: str = "<text>") -> List[Claim]:
    """Only claims that can actually be disproved.

    An earlier version treated every `owner/repo`-shaped token as a repository,
    which flagged `github/workflows`, `docs/api` and `merge/dedupe` as
    nonexistent repos. Fifty-one false "contradicted" results is worse than none:
    a checker that cries wolf is ignored exactly when it matters. So:

    * repositories are matched only when written as a full GitHub URL, or when
      the token is backticked and does not look like a filesystem path;
    * files are checked only when the backticked token has a directory part;
    * anything inside a fenced code block is skipped entirely.
    """
    claims: List[Claim] = []
    in_fence = False
    for i, line in enumerate(text.splitlines(), start=1):
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        # Explicit URLs are unambiguous.
        for match in re.finditer(r"github\.com/([\w.-]+)/([\w.-]+)", line):
            claims.append(Claim(f"repo `{match.group(1)}/{match.group(2)}` exists", source, i))

        # A sentence that names an upstream repository is talking about that
        # repository's files, not ours ("Stremio's SDK ships `docs/protocol.md`").
        upstream = bool(re.search(r"`[A-Za-z0-9][\w.-]*/[\w.-]+`", line))

        for match in FILE_REF.finditer(line):
            token = match.group(1)
            if not PATHLIKE.match(token):
                continue  # bare filename, too ambiguous to call a claim
            if token.startswith(("http", "www")):
                continue
            if upstream:
                continue
            claims.append(Claim(f"file `{token}` exists", source, i))

        # A backticked owner/repo token, e.g. `chrisbanes/tivi`.
        # A line that already says something does not exist is telling the
        # truth about its absence. `apksig/apksig` appears in the plan precisely
        # because it 404s; flagging it would punish the doc for being honest.
        # Look back far enough to catch "checked and nonexistent:" followed by a
        # list, without letting one negation silence a whole document.
        window = "\n".join(text.splitlines()[max(0, i - 6) : i])
        negated = re.search(
            r"\b(do not exist|does not exist|nonexistent|not found|404|absent|"
            r"rejected|unusable|cannot be imported|no licence)\b",
            window,
            re.IGNORECASE,
        )

        for match in re.finditer(r"`([A-Za-z0-9][\w.-]*)/([A-Za-z0-9][\w.-]*)`", line):
            if negated:
                continue
            owner, repo = match.group(1), match.group(2)
            if owner.lower() in NOT_OWNERS:
                continue
            if "." in owner and owner not in ("androidx",):
                continue  # a hostname like v3-cinemeta.strem.io is not an owner
            if "." in repo and not repo.endswith((".kt", ".py", ".md", ".js", ".json")):
                continue
            if repo.endswith((".kt", ".py", ".md", ".js", ".json")):
                continue
            claims.append(Claim(f"repo `{owner}/{repo}` exists", source, i))
    return claims


def _is_local_path(token: str, doc_dir: Optional[Path], repo_root: Optional[Path]) -> bool:
    """`nova-tv/android` and `tv/android-tv` are directories in this repo.

    Treating a local path as a repository name asks GitHub about a repo that was
    never meant to exist there, and reports the resulting 404 as a defect in the
    document.
    """
    bases = [b for b in (doc_dir, repo_root, repo_root and repo_root / "nova-tv") if b]
    for base in bases:
        if (base / token).exists():
            return True
    return False


def check_file_claim(claim: Claim, root: Path) -> Result:
    """Resolve a documented path against every root it could legitimately mean.

    Documentation under nova-tv/docs/ refers to paths in three different frames:
    repository-relative (`shell/android_project`), nova-tv-relative
    (`ops/kotlin_lint.py`, `docs/...`), and upstream (`docs/protocol.md` in
    Stremio's repo). Checking only one frame produced a wall of false
    "contradicted" results, so each candidate root is tried and the claim passes
    if any resolves.
    """
    match = re.search(r"file `(.+?)` exists", claim.text)
    if not match:
        return Result(claim, "unverified", "not a file claim")
    relative = match.group(1).lstrip("/")

    searched = []
    # root is the repository root; nova-tv sits under it.
    for base in (
        root,
        root / "nova-tv",
        root / "nova-tv" / "python",
        root / "nova-tv" / "docs",
        claim_root(claim),
    ):
        if base is None:
            continue
        candidate = base / relative
        searched.append(str(candidate))
        if candidate.exists():
            return Result(claim, "proven", "found on disk", str(candidate))
    return Result(claim, "contradicted", "no such file from any root", "; ".join(searched[:3]))


def claim_root(claim: Claim) -> Optional[Path]:
    """The directory the claim's document lives in, so relative paths resolve."""
    return getattr(claim, "_doc_dir", None)


def check_repo_claim(claim: Claim, github: Any) -> Result:
    match = re.search(r"repo `(.+?)` exists", claim.text)
    if not match or github is None:
        return Result(claim, "unverified", "no GitHub client")
    full_name = match.group(1)
    try:
        repo = github.repo(full_name)
    except Exception as error:  # GitHubError, and anything the transport raises
        return Result(claim, "contradicted", f"API says no: {error}")
    return Result(claim, "proven", "repo exists", f"{repo.full_name}")


def verify_document(
    path: Path,
    *,
    root: Optional[Path] = None,
    github: Optional[Any] = None,
) -> List[Result]:
    """Check every checkable claim in one document."""
    text = path.read_text(errors="ignore")
    base = root or path.parent
    results: List[Result] = []
    for claim in extract_claims(text, source=path.name):
        claim._doc_dir = path.parent  # type: ignore[attr-defined]
        if "exists" in claim.text and claim.text.startswith("file"):
            results.append(check_file_claim(claim, base))
        elif claim.text.startswith("repo"):
            token = claim.text.split("`")[1]
            if _is_local_path(token, path.parent, base):
                # A directory in this repository, not a GitHub repository.
                results.append(
                    Result(claim, "proven", "resolves as a local path in this repo", token)
                )
                continue
            if github is None:
                results.append(Result(claim, "unverified", "no GitHub client to ask"))
            else:
                results.append(check_repo_claim(claim, github))
    return results


# --- layer 2: model cross-check -----------------------------------------------

def crosscheck_unverified(
    results: Sequence[Result],
    *,
    client: Optional[Any] = None,
    limit: int = 5,
) -> List[Result]:
    """Ask the models about claims the deterministic layer could not settle.

    Model agreement never upgrades a verdict. It is attached as context so a
    human can see whether four models independently found the same problem.
    """
    if client is None:
        return list(results)
    examined: List[Result] = []
    asked = 0
    for result in results:
        if result.verdict == "unverified" and asked < limit:
            claimed = result.claim.text
            evidence = result.evidence or result.why
            try:
                consensus = client.verify(claimed, evidence)
                result.model_consensus = consensus.as_dict()
            except Exception as error:  # never let a checker crash the audit
                result.model_consensus = {"decision": "unverified", "why": f"model layer failed: {error}"}
            asked += 1
        examined.append(result)
    return examined


def summarise(results: Sequence[Result]) -> Dict[str, Any]:
    counts = {"proven": 0, "contradicted": 0, "unverified": 0}
    for result in results:
        counts[result.verdict] = counts.get(result.verdict, 0) + 1
    return {
        "total": len(results),
        "counts": counts,
        # The only number that matters: claims asserted with no evidence.
        "unsupported": counts["contradicted"] + counts["unverified"],
        "ok": counts["contradicted"] == 0 and counts["unverified"] == 0,
    }
