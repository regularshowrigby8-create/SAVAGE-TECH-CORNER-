"""Four independent models check the same claim, and must agree before it stands.

Wired to ZeroKey (github.com/downloaddoctor/zerokey), an OpenAI-compatible local
proxy for DeepSeek, Claude, ChatGPT, Qwen and Inception/Mercury. ZeroKey is
Non-Commercial licensed, so this talks to it over HTTP and copies none of its
code.

The design principle: **one model is an opinion, four are evidence.** A single
model asked "is this real?" will answer confidently and wrongly. Four models
asked the same question, with a verdict schema they must fill, either agree or
expose the disagreement. Disagreement is the signal that something is a guess.

Two things this deliberately does not do:
  * It never invents an endpoint. If ZeroKey is not running, that is reported,
    not worked around with a fake response.
  * It never treats a model's confidence as proof. A unanimous "supported" is
    still recorded as a model verdict, not as a fact-check -- the deterministic
    checkers in `appaudit` and `hallucination` remain the source of truth.
"""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# ZeroKey's documented default. Overridable so a different port or a remote
# instance works without editing code.
DEFAULT_BASE = "http://localhost:7250/v1"

# The four models, by the provider names ZeroKey documents. The actual model id
# is discovered from /v1/models at run time -- hard-coding ids here would break
# the moment a provider renames one, which is exactly the kind of invention this
# module exists to prevent.
PROVIDERS = ("deepseek", "claude", "chatgpt", "qwen")

VERDICTS = ("supported", "unsupported", "uncertain")

SYSTEM = (
    "You are a strict verification checker. You are given a CLAIM about a "
    "software repository and the EVIDENCE available. Answer only with JSON: "
    '{"verdict": "supported" | "unsupported" | "uncertain", "reason": "<one sentence>", '
    '"evidence": "<the specific text you relied on, or empty>"}. '
    "If the evidence does not contain what the claim needs, the verdict is "
    "'unsupported'. Do not fill gaps with what you believe to be true. "
    "'uncertain' is for evidence that exists but cannot settle the claim."
)


@dataclass
class Verdict:
    provider: str
    model: str
    verdict: str
    reason: str
    evidence: str
    ok: bool = True
    error: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {
            "provider": self.provider,
            "model": self.model,
            "verdict": self.verdict,
            "reason": self.reason,
            "evidence": self.evidence,
            "ok": self.ok,
            "error": self.error,
        }


@dataclass
class Consensus:
    """What four independent checks add up to."""

    claim: str
    verdicts: List[Verdict] = field(default_factory=list)

    @property
    def answered(self) -> List[Verdict]:
        return [v for v in self.verdicts if v.ok]

    @property
    def tally(self) -> Dict[str, int]:
        counts = {v: 0 for v in VERDICTS}
        for verdict in self.answered:
            counts[verdict.verdict] = counts.get(verdict.verdict, 0) + 1
        return counts

    @property
    def unanimous(self) -> bool:
        answers = {v.verdict for v in self.answered}
        return len(self.answered) >= 2 and len(answers) == 1

    def decision(self) -> Tuple[str, str]:
        """(decision, why) -- the honest summary of what the models agreed on."""
        if not self.answered:
            return "unverified", "no model answered; nothing was checked"
        if len(self.answered) == 1:
            return "unverified", "only one model answered; a single opinion is not evidence"
        counts = self.tally
        top = max(counts, key=lambda k: counts[k])
        if self.unanimous:
            return top, f"all {len(self.answered)} models agreed"
        return "disputed", f"models disagreed: {counts}"

    def as_dict(self) -> Dict[str, Any]:
        decision, why = self.decision()
        return {
            "claim": self.claim,
            "decision": decision,
            "why": why,
            "tally": self.tally,
            "unanimous": self.unanimous,
            "verdicts": [v.as_dict() for v in self.verdicts],
        }


def _post(url: str, payload: Dict[str, Any], api_key: str, timeout: float) -> Dict[str, Any]:
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode())


def list_models(base_url: str = DEFAULT_BASE, api_key: str = "zerokey", timeout: float = 10.0) -> List[str]:
    """The models ZeroKey actually reports. Empty list means it is not running."""
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/models",
        headers={"Authorization": f"Bearer {api_key}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode())
    except (urllib.error.URLError, OSError, ValueError):
        return []
    items = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(items, list):
        return []
    ids = []
    for item in items:
        if isinstance(item, dict) and item.get("id"):
            ids.append(str(item["id"]))
    return ids


def pick_models(available: Sequence[str], providers: Sequence[str] = PROVIDERS) -> List[Tuple[str, str]]:
    """Map each provider to a real model id.

    Matching is by substring because providers expose ids as
    `deepseek-chat`, `claude-sonnet-4`, `gpt-4o`, `qwen-max` and so on. A provider
    with no matching model is omitted rather than guessed at.
    """
    aliases = {
        "deepseek": ("deepseek",),
        "claude": ("claude", "sonnet", "opus", "haiku"),
        "chatgpt": ("gpt", "o1", "o3", "o4", "chatgpt"),
        "qwen": ("qwen",),
        "mercury": ("mercury", "inception"),
    }
    picked: List[Tuple[str, str]] = []
    used = set()
    for provider in providers:
        needles = aliases.get(provider, (provider,))
        for model in available:
            if model in used:
                continue
            low = model.lower()
            if any(n in low for n in needles):
                picked.append((provider, model))
                used.add(model)
                break
    return picked


def parse_verdict(text: str) -> Tuple[str, str, str]:
    """Pull the JSON out of a model reply, tolerating prose around it."""
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        return "uncertain", "reply was not JSON", ""
    try:
        data = json.loads(match.group(0))
    except ValueError:
        return "uncertain", "reply JSON did not parse", ""
    verdict = str(data.get("verdict", "uncertain")).lower().strip()
    if verdict not in VERDICTS:
        return "uncertain", f"unknown verdict '{verdict}'", ""
    return verdict, str(data.get("reason", ""))[:400], str(data.get("evidence", ""))[:400]


class ZeroKey:
    """Client for a running ZeroKey instance."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE,
        api_key: str = "zerokey",
        timeout: float = 60.0,
        providers: Sequence[str] = PROVIDERS,
    ):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self.providers = providers

    def models(self) -> List[str]:
        return list_models(self.base_url, self.api_key, min(self.timeout, 15.0))

    def ask(self, provider: str, model: str, claim: str, evidence: str) -> Verdict:
        prompt = f"CLAIM:\n{claim}\n\nEVIDENCE:\n{evidence[:12000]}\n"
        try:
            payload = _post(
                f"{self.base_url}/chat/completions",
                {
                    "model": model,
                    "messages": [
                        {"role": "system", "content": SYSTEM},
                        {"role": "user", "content": prompt},
                    ],
                    "temperature": 0,
                    "stream": False,
                },
                self.api_key,
                self.timeout,
            )
        except urllib.error.HTTPError as error:
            return Verdict(provider, model, "uncertain", "", "", ok=False, error=f"HTTP {error.code}")
        except (urllib.error.URLError, OSError) as error:
            return Verdict(provider, model, "uncertain", "", "", ok=False, error=str(error))
        except ValueError as error:
            return Verdict(provider, model, "uncertain", "", "", ok=False, error=f"bad JSON: {error}")

        try:
            content = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            return Verdict(provider, model, "uncertain", "", "", ok=False, error="no message in reply")
        verdict, reason, quoted = parse_verdict(content)
        return Verdict(provider, model, verdict, reason, quoted)

    def complete(self, model: str, prompt: str) -> str:
        """One raw completion. Used for open-ended review, not verdicts.

        `verify` runs a strict JSON verdict schema because a claim is a
        supported/unsupported question. A design review has no such schema, so it
        needs the model's actual prose, unparsed.
        """
        payload = _post(
            f"{self.base_url}/chat/completions",
            {
                "model": model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.2,
                "stream": False,
            },
            self.api_key,
            self.timeout,
        )
        try:
            return payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            raise ValueError(f"{model}: no message in reply")

    def verify(self, claim: str, evidence: str) -> Consensus:
        """Ask every available provider the same question."""
        available = self.models()
        if not available:
            return Consensus(
                claim=claim,
                verdicts=[
                    Verdict(p, "", "uncertain", "", "", ok=False, error="ZeroKey not reachable")
                    for p in self.providers[:1]
                ],
            )
        consensus = Consensus(claim=claim)
        for provider, model in pick_models(available, self.providers):
            consensus.verdicts.append(self.ask(provider, model, claim, evidence))
        return consensus


def status(base_url: str = DEFAULT_BASE, api_key: str = "zerokey", timeout: float = 10.0) -> Dict[str, Any]:
    """Whether ZeroKey is running, and which of the four providers it exposes."""
    models = list_models(base_url, api_key, timeout)
    picked = pick_models(models, PROVIDERS)
    return {
        "base_url": base_url,
        "reachable": bool(models),
        "models": models,
        "providers_found": [p for p, _ in picked],
        "providers_missing": [p for p in PROVIDERS if p not in {x for x, _ in picked}],
        "ok": bool(models),
    }
