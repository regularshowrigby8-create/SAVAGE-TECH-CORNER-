#!/usr/bin/env python3
"""The unified model and key gateway: one endpoint, every provider, one token, zero setup.

Agents in the sealed environment never hold a credential and never talk to a vendor. They call this
server, which owns every key it can find and routes the request to a model that is genuinely
reachable - a local server on its default port, or a provider whose key is already in the
environment. Nothing is installed, nothing is written to disk, and no key is ever printed, logged or
returned: requests name a provider, never a secret.

    python3 sealed-factories/runtime/gateway.py --port 8787

    GET  /healthz                  what is reachable right now
    GET  /v1/models                the models that can actually be served
    GET  /v1/keys                  which providers hold a key (counts, never values)
    POST /v1/chat/completions      OpenAI-compatible; "model": "auto" picks the best available

Model routing: `provider/model` (for example `anthropic/claude-3-5-sonnet-latest`) targets one
provider; a bare model name searches every provider that offers it; `auto` takes the first reachable
provider in preference order. When a provider refuses, the next one is tried and the fallback is
reported in the response headers `x-sealed-provider` and `x-sealed-fallbacks`.

Truthfulness: with nothing reachable the gateway answers HTTP 503 and says exactly what it looked
for. It never fabricates a completion.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY_VARIABLES = {
    "openai": ("OPENAI_API_KEY", "OPENAI_API_KEYS"),
    "anthropic": ("ANTHROPIC_API_KEY", "ANTHROPIC_API_KEYS"),
    "google": ("GEMINI_API_KEY", "GOOGLE_API_KEY", "GEMINI_API_KEYS"),
    "groq": ("GROQ_API_KEY", "GROQ_API_KEYS"),
    "mistral": ("MISTRAL_API_KEY", "MISTRAL_API_KEYS"),
    "openrouter": ("OPENROUTER_API_KEY", "OPENROUTER_API_KEYS"),
    "deepseek": ("DEEPSEEK_API_KEY", "DEEPSEEK_API_KEYS"),
    "together": ("TOGETHER_API_KEY", "TOGETHER_API_KEYS"),
    "xai": ("XAI_API_KEY", "XAI_API_KEYS"),
}

#: provider -> (endpoint, default model, needs key, wire format)
PROVIDERS = {
    "openai": ("https://api.openai.com/v1/chat/completions", "gpt-4o-mini", True, "openai"),
    "anthropic": ("https://api.anthropic.com/v1/messages", "claude-3-5-sonnet-latest", True,
                  "anthropic"),
    "google": ("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
               "gemini-2.0-flash", True, "openai"),
    "groq": ("https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", True,
             "openai"),
    "mistral": ("https://api.mistral.ai/v1/chat/completions", "mistral-large-latest", True, "openai"),
    "openrouter": ("https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini", True,
                   "openai"),
    "deepseek": ("https://api.deepseek.com/v1/chat/completions", "deepseek-chat", True, "openai"),
    "together": ("https://api.together.xyz/v1/chat/completions",
                 "meta-llama/Llama-3.3-70B-Instruct-Turbo", True, "openai"),
    "xai": ("https://api.x.ai/v1/chat/completions", "grok-2-latest", True, "openai"),
    "ollama": ("http://127.0.0.1:11434/v1/chat/completions",
               os.environ.get("OLLAMA_MODEL", "llama3.1"), False, "openai"),
    "lmstudio": ("http://127.0.0.1:1234/v1/chat/completions",
                 os.environ.get("LOCAL_MODEL", "local-model"), False, "openai"),
    "vllm": ("http://127.0.0.1:8000/v1/chat/completions",
             os.environ.get("LOCAL_MODEL", "local-model"), False, "openai"),
    "llamacpp": ("http://127.0.0.1:8080/v1/chat/completions",
                 os.environ.get("LOCAL_MODEL", "local-model"), False, "openai"),
}

#: Preference order for `auto`: local first (free, fast, private), then hosted providers.
PREFERENCE = ("ollama", "lmstudio", "vllm", "llamacpp", "openai", "anthropic", "google", "groq",
              "mistral", "deepseek", "together", "xai", "openrouter")

LOCAL_PORTS = {"ollama": 11434, "lmstudio": 1234, "vllm": 8000, "llamacpp": 8080}

SECRET_SHAPES = re.compile(r"sk-[A-Za-z0-9\-_]{12,}|sk-ant-[A-Za-z0-9\-_]{12,}|"
                           r"AIza[0-9A-Za-z\-_]{20,}|gsk_[A-Za-z0-9]{20,}|[A-Za-z0-9]{32,}")
_LOCK = threading.Lock()
_KEYS: dict[str, list[str]] = {}
_USAGE: dict[str, int] = {}
_ROTATION: dict[str, int] = {}


def collect_keys() -> dict[str, list[str]]:
    """Every key the environment already holds. Values stay in this process."""
    found: dict[str, list[str]] = {}
    for provider, variables in KEY_VARIABLES.items():
        values: list[str] = []
        for variable in variables:
            raw = os.environ.get(variable, "")
            if not raw:
                continue
            values.extend(part.strip() for part in raw.split(",") if part.strip())
        for index in range(2, 10):
            extra = os.environ.get(f"{variables[0].rsplit('_KEY', 1)[0]}_KEY_{index}", "")
            if extra.strip():
                values.append(extra.strip())
        if values:
            found[provider] = sorted(set(values))
    return found


def redact(text: str) -> str:
    return SECRET_SHAPES.sub("[redacted]", text or "")


def port_open(host: str, port: int, timeout: float = 0.25) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def available(provider: str) -> bool:
    if provider in LOCAL_PORTS:
        return port_open("127.0.0.1", LOCAL_PORTS[provider])
    return bool(_KEYS.get(provider))


def reachable() -> list[str]:
    return [provider for provider in PREFERENCE if available(provider)]


def health() -> dict:
    return {"service": "sealed-factory-gateway", "reachable": reachable(),
            "local": [p for p in reachable() if p in LOCAL_PORTS],
            "keyed": [p for p in reachable() if p not in LOCAL_PORTS],
            "key_counts": {provider: len(keys) for provider, keys in sorted(_KEYS.items())},
            "usage": dict(_USAGE), "models_served": len(describe_models()),
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}


def describe_models() -> list[dict]:
    models = []
    for provider in reachable():
        _url, default, _keyed, _wire = PROVIDERS[provider]
        override = os.environ.get(f"{provider.upper()}_MODEL", "")
        models.append({"id": f"{provider}/{override or default}", "provider": provider,
                       "object": "model", "owned_by": provider})
        if override:
            models.append({"id": f"{provider}/{default}", "provider": provider,
                           "object": "model", "owned_by": provider})
    return models


def pick_key(provider: str) -> str:
    """Round-robin across a provider's keys so one rate limit does not stop the pipeline."""
    keys = _KEYS.get(provider) or []
    if not keys:
        return ""
    with _LOCK:
        index = _ROTATION.get(provider, 0) % len(keys)
        _ROTATION[provider] = index + 1
        return keys[index]


def build_request(provider: str, body: dict) -> tuple[str, dict, dict]:
    url, default, _keyed, wire = PROVIDERS[provider]
    model = body.get("model") or default
    if model.startswith(f"{provider}/"):
        model = model.split("/", 1)[1]
    messages = body.get("messages") or []
    temperature = body.get("temperature")
    headers = {"Content-Type": "application/json"}
    key = pick_key(provider)

    if wire == "anthropic":
        system = "\n".join(m.get("content", "") for m in messages if m.get("role") == "system")
        conversation = [m for m in messages if m.get("role") != "system"]
        payload: dict = {"model": model, "max_tokens": int(body.get("max_tokens") or 2048),
                         "messages": conversation}
        if system:
            payload["system"] = system
        if temperature is not None:
            payload["temperature"] = temperature
        headers.update({"x-api-key": key, "anthropic-version": "2023-06-01"})
        return url, payload, headers

    payload = {"model": model, "messages": messages, "stream": False}
    if temperature is not None:
        payload["temperature"] = temperature
    if body.get("max_tokens"):
        payload["max_tokens"] = body["max_tokens"]
    if key:
        headers["Authorization"] = f"Bearer {key}"
    if provider == "openrouter":
        headers["HTTP-Referer"] = "https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-"
        headers["X-Title"] = "sealed-factory-gateway"
    return url, payload, headers


def extract(payload: dict, provider: str) -> str:
    if isinstance(payload.get("content"), list):
        parts = [part.get("text", "") for part in payload["content"] if isinstance(part, dict)]
        if any(parts):
            return "\n".join(parts)
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0]
        if isinstance(first, dict):
            message = first.get("message") or {}
            if isinstance(message.get("content"), str):
                return message["content"]
            if isinstance(first.get("text"), str):
                return first["text"]
    if isinstance(payload.get("response"), str):
        return payload["response"]
    return ""


def request_timeout() -> int:
    try:
        return max(5, int(os.environ.get("SEALED_GATEWAY_TIMEOUT", "60")))
    except ValueError:
        return 60


def forward(provider: str, body: dict, timeout: int = 0) -> dict:
    timeout = timeout or request_timeout()
    url, payload, headers = build_request(provider, body)
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers,
                                     method="POST")
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = json.loads(response.read().decode("utf-8", "replace"))
    except (TimeoutError, socket.timeout) as problem:
        # A server that accepted the connection and then went quiet must not hold the pipeline: the
        # router moves on, and the reason is recorded.
        raise GatewayError(504, f"{provider} did not answer within {timeout}s") from problem
    except urllib.error.HTTPError as problem:
        detail = redact(problem.read().decode("utf-8", "replace")[:600])
        raise GatewayError(problem.code, f"{provider} answered HTTP {problem.code}: {detail}") from problem
    except (urllib.error.URLError, OSError, ValueError) as problem:
        raise GatewayError(502, f"{provider} could not be reached: {redact(str(problem))}") from problem
    text = extract(raw, provider)
    if not text:
        raise GatewayError(502, f"{provider} answered without any content")
    with _LOCK:
        _USAGE[provider] = _USAGE.get(provider, 0) + 1
    return {"id": f"chatcmpl-{uuid.uuid4().hex[:24]}", "object": "chat.completion",
            "created": int(time.time()), "model": payload.get("model"), "provider": provider,
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": text}}],
            "usage": raw.get("usage") or {},
            "x_sealed": {"provider": provider, "seconds": round(time.time() - started, 2),
                         "raw_keys": sorted(raw)[:12]}}


class GatewayError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def route(body: dict) -> dict:
    requested = (body.get("model") or "auto").strip()
    order = list(reachable())
    if not order:
        raise GatewayError(503, "no model is reachable: no local server on "
                                + ", ".join(f"{name}:{port}" for name, port in sorted(LOCAL_PORTS.items()))
                                + " and no provider key in the environment ("
                                + ", ".join(sorted(KEY_VARIABLES)) + ")")
    if requested != "auto":
        provider = requested.split("/", 1)[0] if "/" in requested else ""
        if provider in PROVIDERS:
            if provider not in order:
                raise GatewayError(503, f"{provider} is configured but not reachable right now")
            order = [provider] + [p for p in order if p != provider]
        else:
            order = [p for p in order if p != "llamacpp"]

    attempts = []
    for provider in order:
        attempt = dict(body)
        if requested != "auto" and "/" not in requested:
            attempt["model"] = f"{provider}/{requested}"
        try:
            result = forward(provider, attempt)
        except GatewayError as problem:
            attempts.append({"provider": provider, "status": problem.status, "error": str(problem)})
            continue
        result["x_sealed"]["attempts"] = attempts
        return result
    raise GatewayError(502, "every reachable provider refused: "
                            + json.dumps(attempts)[:800])


class Handler(BaseHTTPRequestHandler):
    server_version = "sealed-factory-gateway/1.0"
    token = ""

    def log_message(self, fmt, *args):  # keep stdout clean; we log deliberately below
        return

    def _authorised(self) -> bool:
        if not self.token:
            return True
        header = self.headers.get("Authorization", "")
        return header.removeprefix("Bearer ").strip() == self.token

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - the interface of BaseHTTPRequestHandler
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            return self._send(200, health())
        if path == "/v1/models":
            return self._send(200, {"object": "list", "data": describe_models()})
        if path == "/v1/keys":
            return self._send(200, {"providers": {provider: {"keys": len(keys)}
                                                  for provider, keys in sorted(_KEYS.items())},
                                    "values_exposed": False})
        return self._send(404, {"error": {"message": f"no such path: {path}"}})

    def do_POST(self):  # noqa: N802 - the interface of BaseHTTPRequestHandler
        path = self.path.split("?", 1)[0]
        if not self._authorised():
            return self._send(401, {"error": {"message": "send the gateway token as a bearer token"}})
        if path not in ("/v1/chat/completions", "/v1/completions"):
            return self._send(404, {"error": {"message": f"no such path: {path}"}})
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw or b"{}")
        except ValueError:
            return self._send(400, {"error": {"message": "the request body is not valid JSON"}})
        started = time.time()
        try:
            result = route(body)
        except GatewayError as problem:
            print(f"[GATEWAY] {path} -> {problem.status} ({round(time.time() - started, 2)}s) "
                  f"{redact(str(problem))[:200]}", flush=True)
            return self._send(problem.status, {"error": {"message": str(problem), "type":
                                                         "sealed_gateway_error"}})
        provider = result["x_sealed"]["provider"]
        print(f"[GATEWAY] {path} -> 200 {provider} {result.get('model')} "
              f"({round(time.time() - started, 2)}s)", flush=True)
        return self._send(200, result)


def main() -> int:
    parser = argparse.ArgumentParser(description="Unified model and key gateway")
    parser.add_argument("--port", type=int, default=int(os.environ.get("SEALED_GATEWAY_PORT", 8787)))
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--token", default=os.environ.get("SEALED_GATEWAY_TOKEN", ""))
    parser.add_argument("--once", action="store_true",
                        help="print the health report and exit (used by tests and status checks)")
    args = parser.parse_args()

    global _KEYS
    _KEYS = collect_keys()
    Handler.token = args.token or ("" if args.host.startswith("127.") else uuid.uuid4().hex)

    if args.once:
        print(json.dumps(health(), indent=1))
        return 0

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[GATEWAY] listening on http://{args.host}:{args.port}", flush=True)
    print(f"[GATEWAY] reachable models: {reachable() or 'none - requests will be refused with 503'}",
          flush=True)
    if Handler.token:
        print(f"[GATEWAY] token required: {Handler.token}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[GATEWAY] stopped", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
