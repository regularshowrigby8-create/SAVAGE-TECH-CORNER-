# Comprehensive System Architecture: Savage Browser & Savage Tech Corner

## 1. Naming & Ecosystem Taxonomy

| Term | Role & Definition |
|---|---|
| **Savage Tech Corner** | The parent organization, developer studio, and multi-factory software engineering platform. |
| **Savage Browser** | The flagship Android application product (`com.savage.soulbrowser`). |
| **Soul Browser Shell** | The ultra-lightweight, high-performance architectural shell design pattern powering the browser UI and WebView container. |
| **The Factories** | Reusable autonomous multi-agent verification and refinement pipelines powering this and future applications. |

---

## 2. Directory Layout & Maturity Model

Every module across the repository is categorized by a strict maturity classification:

| Directory | Role | Maturity Status |
|---|---|---|
| `shell/android_project/` | Production native Android application (Gradle, Java, ResXML, Manifest) | **Working / Release-Ready** |
| `shell/soul_browser/` | Python architectural model of browser shell and bridge | **Tested Prototype** |
| `frontend/` | Browser frontend modules (UI, tabs, storage, engine) | **Architectural Prototype** |
| `backend/` | Cloud backend services (Gateway, search orchestration, AI sync) | **Architectural Prototype** |
| `factories/` | Canonical autonomous agent and verification pipeline | **Working / Tested** |
| `docs/` | Formal specifications, security policies, and contracts | **Authoritative Specification** |
| `artifacts/` | Build deliverables, APKs, test reports, and manifests | **Generated Artifacts (CI-managed)** |

---

## 3. Component Responsibilities & Dependency Direction

```
+-------------------------------------------------------------------------+
|                              USER INTERFACE                             |
|       (MainActivity.java / Jetpack Compose UI / Soul Browser Shell)     |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                            WEBVIEW CONTAINER                            |
|       (Hardened WebSettings / Fail-Closed SSL / Scheme Whitelist)       |
+-------------------------------------------------------------------------+
                                     |  (Privileged JavaScript Bridge)
                                     v
+-------------------------------------------------------------------------+
|                        SECURE SOUL BRIDGE LAYER                         |
|   (Origin Whitelist: savage-tech-corner.io / Sanitized Input Buffers)   |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                    LOCAL DATA & CLIENT ORCHESTRATION                    |
|          (Encrypted Storage / Tab Session Manager / Download Mgr)       |
+-------------------------------------------------------------------------+
                                     |  (HTTPS REST / JSON-RPC)
                                     v
+-------------------------------------------------------------------------+
|                           SAVAGE API GATEWAY                            |
|          (Reverse Proxy / Auth Validator / Rate Limiting)               |
+-------------------------------------------------------------------------+
                 /                           |                          \
                v                            v                           v
+---------------------------+  +--------------------------+  +--------------------------+
|    SEARCH ORCHESTRATION   |  |   USER & SYNC SERVICE    |  |    AUTONOMOUS AI ENGINE  |
| (Ranking / Instant Answer)|  | (Encrypted History/Sync) |  | (DeepSeek Swarm / Summar)|
+---------------------------+  +--------------------------+  +--------------------------+
```

### Strict Dependency Direction:
- **UI** depends on **Local Client Orchestrator**.
- **Local Client Orchestrator** depends on **API Gateway**.
- **API Gateway** dispatches to **Isolated Domain Microservices**.
- **Backend services never import, access, or depend on Android UI or WebView packages.**

---

## 4. API Contracts & Wire Formats

### 4.1 Search Orchestration API (`/api/v1/search`)

- **Method**: `POST`
- **Request Payload**:
```json
{
  "query": "android 16kb page size alignment",
  "provider": "auto",
  "safe_search": "strict",
  "page": 1
}
```

- **Response Payload**:
```json
{
  "status": "success",
  "query": "android 16kb page size alignment",
  "instant_answer": "Android 15+ devices support 16 KB page sizes requiring native libraries to be aligned to 16,384 bytes.",
  "results": [
    {
      "title": "Support 16 KB page sizes | Android Developers",
      "url": "https://developer.android.com/guide/practices/page-sizes",
      "snippet": "Learn how to optimize native C/C++ code for 16KB page alignment.",
      "score": 0.98
    }
  ],
  "latency_ms": 42
}
```

### 4.2 Cross-Device Bookmark & Session Sync API (`/api/v1/sync`)

- **Method**: `PUT`
- **Headers**: `Authorization: Bearer <token>`, `X-Request-ID: <uuid>`
- **Request Payload**:
```json
{
  "device_id": "dev_android_pix9_01",
  "timestamp": "2026-10-10T17:45:00Z",
  "bookmarks": [
    {
      "id": "bm_001",
      "title": "Savage Tech Corner",
      "url": "https://savage-tech-corner.io",
      "created_at": 1728582300
    }
  ]
}
```

---

## 5. Security Boundaries & Invariants

1. **WebView Policy**:
   - `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`.
   - File URL schemes blocked: `setAllowFileAccess(false)`, `setAllowFileAccessFromFileURLs(false)`.
   - Scheme Whitelist: Only `https://` is permitted. Cleartext `http://` navigation is blocked fail-closed.
   - SSL Validation: `onReceivedSslError` executes `handler.cancel()` unconditionally.

2. **Privileged JavascriptInterface Bridge**:
   - Origin Restriction: Invocations only permitted when `webView.getUrl()` matches whitelisted domains (`savage-tech-corner.io`, `app.savage-tech-corner.io`).
   - Input Sanitization: Payload lengths capped (<= 500 characters) and stripped of control characters.

3. **Offline & Fallback Behavior**:
   - If the backend is unreachable, the client operates in **Offline Resilient Mode**:
     - Local encrypted cache serves cached pages and offline history.
     - Bookmarks are persisted to local SQLite/Room storage and queued for background synchronization.
     - Network errors display a clean offline UI rather than raw WebView crash states.
