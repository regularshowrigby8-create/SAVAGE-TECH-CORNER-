# SAVAGE-TECH-CORNER-
The ultimate tech hub

## Architecture & System Structure

This repository coordinates the Savage Tech Corner engineering ecosystem organized into 5 primary pillars:

```
SAVAGE-TECH-CORNER-/
├── factories/              # [1] Factory: Multi-factory refinement, 66 AI models & Android tools
│   ├── factory-1/          # Personal code space, deterministic AST scanners, unit test suites
│   ├── factory-2/          # Multi-agent debugging workspaces, non-mutating proposals, Main Agent verifier
│   ├── factory-3/          # Android APK analyzer, LeakCanary, StrictMode, 16KB ABI alignment, Middleman
│   ├── final-product/      # Release artifacts, SoulBrowser-debug.apk, and RELEASE_MANIFEST.json
│   ├── factory-setup.zip   # Canonical multi-factory release archive
│   └── run_factories.py    # Master pipeline runner
│
├── shell/                  # [2] Shell: Soul Browser Android shell & WebView bridge
│   ├── soul_browser/       # WebViewHolder, WebViewBridge, SoulBrowserShell coordinator
│   ├── android_project/    # AndroidManifest.xml and Java/Kotlin entry sources
│   └── tests/              # Shell unit test suites
│
├── frontend/               # [3] Front End: Android Browser Frontend
│   ├── compose_user_interface/      # TopAppBar, WebViewport, BottomNavigationBar composables
│   ├── browser_controller/          # Action dispatcher and intent listener
│   ├── webview_engine/              # Security policy, DOM storage, and cookie configuration
│   ├── tabs_and_navigation/         # Multi-tab state management and session history
│   ├── local_storage/               # Encrypted preferences, bookmarks, and local records
│   ├── downloads_and_permissions/   # Scoped storage, download manager, runtime permission requests
│   ├── search_providers/            # DuckDuckGo, Google, Ecosia, and Savage query templates
│   └── api_client/                  # Client for communicating with the Browser Backend API Gateway
│
├── backend/                # [4] Back End: Browser Backend
│   ├── api_gateway/                 # Central router, rate limiter, and request dispatcher
│   ├── search_orchestration/        # Ranking aggregator and instant answer engine
│   ├── user_and_sync_services/      # Cross-device bookmark and history sync
│   ├── ai_services/                 # Page summarization, smart search, and tab clustering
│   ├── caching/                     # TTL memory and distributed cache layer
│   ├── databases/                   # User and crawl index storage management
│   ├── background_workers/          # Asynchronous job queue and task processor
│   └── monitoring_and_security/     # Prometheus metrics and security event auditing
│
└── .github/workflows/      # [5] Pipeline: Automation & Continuous Integration
    ├── factory-2-debug.yml          # Single-job Factory 2 CI runner and gate validator
    └── model-discovery.yml          # Automated weekly scan for free, no-key coding models
```
