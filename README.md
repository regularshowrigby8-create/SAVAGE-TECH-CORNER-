# Savage Tech Corner & Savage Browser

> **Savage Tech Corner**: High-assurance multi-agent software engineering factory and platform.  
> **Savage Browser**: Security-hardened Android web browser powered by the lightweight **Soul Browser Shell** architecture (`com.savage.soulbrowser`).

- **Application Package**: `com.savage.soulbrowser`
- **Android Target**: API 26 to 35 (Android 8.0 - Android 15)
- **License**: MIT
- **Architecture**: Soul Browser Shell & Hardened Native WebView Container

---

## 1. Naming & Ecosystem Taxonomy

- **Savage Tech Corner**: The parent organization, engineering studio, and multi-factory software platform.
- **Savage Browser**: The primary Android application deliverable (`com.savage.soulbrowser`).
- **Soul Browser Shell**: The architectural UI shell and WebView design pattern optimized for low-memory overhead and instant responsiveness.
- **The Factories**: Reusable autonomous multi-agent verification and refinement pipelines powering this and future applications.

---

## 2. Repository Layout & Maturity Status

| Directory | Maturity Status | Description |
|---|---|---|
| **`shell/android_project/`** | **Android Shell Project (Source Pipeline)** | Android Gradle source project with `MainActivity.java`; automated cloud build and APK artifacts compiled in CI via `.github/workflows/android-build.yml`. |
| **`shell/soul_browser/`** | **Tested Prototype** | Python architectural model of browser shell and bridge with 100% test coverage. |
| **`frontend/`** | **Architectural Prototype** | 8 modular prototype specifications (UI composition, tabs, storage, search providers). |
| **`backend/`** | **Architectural Prototype** | 8 cloud backend prototype services (API gateway, search orchestration, sync, AI engine). |
| **`factories/`** | **Working / Tested** | Multi-agent autonomous verification factory (`factory-1`, `factory-2`, `factory-3`). |
| **`docs/`** | **Authoritative Specification** | Architecture specification, Charter invariants, and security guidelines. |
| **`artifacts/`** | **Generated Deliverables** | CI-generated APKs, release manifests, and audit reports (`artifacts/debug/`, `artifacts/manifests/`). |

---

## 3. Key Documentation

- 🏛️ **[Architecture & API Contracts](docs/ARCHITECTURE.md)**: Component responsibilities, dependency flow, wire payloads, and offline resilience rules.
- 🛡️ **[Security Policy](docs/SECURITY.md)**: WebView TLS fail-closed policy, scheme filtering, and vulnerability reporting.
- 🤝 **[Contributing Guidelines](docs/CONTRIBUTING.md)**: Verification invariants, Universal Change Ledger rules, and PR workflow.
- 📜 **[Global Factory Charter](docs/factory/CHARTER.md)**: Anti-fabrication rules, zero padding, and deterministic gates.
- 🤖 **[Standardized Model Registry](models/free-models.json)**: Model capabilities, latency classes, and fallback priorities.

---

## 4. Verification & Testing

```bash
# 1. Execute system-wide test suite
python3 test_entire_system.py

# 2. Run deterministic Factory 2 quality gates
bash .claude/scripts/gates.sh

# 3. Build and package Savage Browser APK
python3 factories/run_factories.py --source shell
```

---

## 5. Branch Architecture

| Branch | Purpose |
|---|---|
| **`main`** | Production release baseline with strict Charter and gate enforcement. |
| **`production`** | Certified releases and deployment artifacts. |
| **`shell`** | Native Android project and Soul Browser shell architecture. |
| **`frontend`** | Browser frontend modular prototypes. |
| **`backend`** | Cloud backend modular services. |
| **`pipeline`** | Multi-factory runners, packaging tools, and model discovery workflows. |
