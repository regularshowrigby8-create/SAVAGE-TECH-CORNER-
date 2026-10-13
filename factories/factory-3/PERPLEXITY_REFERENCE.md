# Android Pipeline, Debugging, and Multi-Language Architecture Reference

_Extracted from Perplexity Technical Reference (Session: 225644eb-d9ba-4f38-b36e-9617c9ae4727)_

---

## 1. Core Architecture Recommendation

**Recommendation:** Keep one Android host app, then integrate other codebases through isolated modules and explicit bridges—not by mixing their source files together.

- Every module must log its name.
- Every cross-language call must carry a request ID to trace failures across the Android host and imported components.

---

## 2. GitHub APK Pipeline Tools

| Tool                                   | Purpose                                                           | Priority                                            |
| -------------------------------------- | ----------------------------------------------------------------- | --------------------------------------------------- |
| GitHub Actions                         | Orchestrates your build, test, and release jobs                   | Core                                                |
| Git + actions/checkout                 | Retrieves the repository and its version history                  | Core                                                |
| actions/setup-java                     | Installs the JDK required by your selected Android Gradle Plugin  | Core                                                |
| Gradle Wrapper: gradlew                | Builds using your project's declared Gradle version               | Core                                                |
| Android Gradle Plugin — AGP            | Compiles and packages the Android application                     | Core                                                |
| gradle/actions/setup-gradle            | Configures Gradle caching and validates the Gradle Wrapper        | Core                                                |
| Android SDK                            | Platform SDK, Build Tools, command-line tools, and Platform Tools | Core                                                |
| android-actions/setup-android          | Configures Android SDK tooling on the runner when needed          | Conditional                                         |
| actions/upload-artifact                | Saves APKs, test results, logs, and reports from workflow runs    | Core                                                |
| reactivecircus/android-emulator-runner | Starts a hardware-accelerated emulator and runs device tests      | Core for device tests                               |
| Gradle Managed Devices                 | Alternative Gradle-controlled device-test setup                   | Choose instead of emulator runner where appropriate |
| GitHub CLI — gh                        | Lets your agent inspect workflow results and retrieve artifacts   | Recommended                                         |
| GitHub encrypted secrets               | Stores signing credentials outside your source code               | Core for releases                                   |
| apksigner + zipalign                   | APK signature verification and package alignment                  | Core for releases                                   |
| Dependabot or Renovate                 | Dependency-update automation                                      | Choose one                                          |

**Policy Note:** Do not copy random JDK, AGP, Gradle, Kotlin, or NDK versions into the workflow. Select a compatible set, pin it in the repository, and update it through tested pull requests.

---

## 3. Code Quality and Testing Matrix

| Area                                | Tools to use                     | Recommended role                                       |
| ----------------------------------- | -------------------------------- | ------------------------------------------------------ |
| Android-specific analysis           | Android Lint                     | Required check                                         |
| Kotlin analysis                     | detekt                           | Required when using Kotlin                             |
| Kotlin formatting                   | ktlint or Spotless with ktlint   | Choose one formatting entry point                      |
| JVM unit tests                      | JUnit, MockK                     | Business logic and mocked collaborators                |
| Coroutine/Flow tests                | kotlinx-coroutines-test, Turbine | Asynchronous state and event tests                     |
| Android tests without a device      | Robolectric                      | Selected Android-dependent unit tests                  |
| Compose interface tests             | Compose UI testing               | Component and interaction assertions                   |
| Traditional Android interface tests | Espresso                         | View-based screens                                     |
| Cross-app/system interactions       | UI Automator                     | Permissions, system dialogs, external apps             |
| End-to-end user journeys            | Maestro                          | Agent-friendly smoke-test flows                        |
| HTTP integration tests              | MockWebServer                    | Controlled API responses and failures                  |
| Kotlin coverage                     | Kover                            | Coverage reporting                                     |
| Secret detection                    | Gitleaks                         | Block accidentally committed credentials               |
| Code security analysis              | CodeQL                           | Add for supported languages and repository eligibility |
| Licence auditing                    | ORT (OSS Review Toolkit)         | Review stitched dependencies and licences              |

**Interaction Rule:** Every important interaction needs an expected outcome and an automated assertion. A successful compilation alone never qualifies a feature as finished.

---

## 4. Android Debugging Toolkit

| Tool                        | Investigation Domain                                                      |
| --------------------------- | ------------------------------------------------------------------------- |
| Android Studio debugger     | Kotlin/Java state, breakpoints, exceptions, execution flow                |
| ADB                         | Device communication, shell commands, instrumentation, profiling          |
| Logcat                      | Runtime logs, crashes, and stack traces                                   |
| Android Emulator            | Device/API compatibility testing                                          |
| Android Studio Profiler     | CPU and memory investigations                                             |
| Layout Inspector            | Layout hierarchy and Compose interface inspection                         |
| App Inspection              | Database, network, and background-work inspection                         |
| LeakCanary                  | Debug-build memory leak detection                                         |
| StrictMode                  | Debug-build detection of problematic main-thread work and resource misuse |
| Perfetto                    | System traces, scheduling, rendering, and jank investigations             |
| Macrobenchmark              | Startup and larger UI-flow measurements; traces                           |
| APK Analyzer                | Packaged files, libraries, and APK size                                   |
| R8 Retrace                  | Readable stack traces from obfuscated release builds                      |
| Chrome DevTools for WebView | HTML, CSS, JavaScript, and WebView console inspection                     |
| LLDB                        | Native C/C++ breakpoints and debugging                                    |

---

## 5. Mixed-Language Integration Tools

| Language/codebase            | Integration route                                | Additional tools                                                  |
| ---------------------------- | ------------------------------------------------ | ----------------------------------------------------------------- |
| Kotlin + Java                | Android library modules and JVM interoperability | Gradle, Kotlin compiler, JDK                                      |
| C/C++                        | Native library exposed through JNI               | Android NDK, CMake, Ninja, LLDB, clang-format, clang-tidy         |
| Rust                         | Android native library with bindings             | Rust/Cargo, Android NDK, cargo-ndk, UniFFI, Clippy, rustfmt       |
| Go                           | Bind a compatible library for Android            | Go toolchain, gomobile, go test, go vet                           |
| Python                       | Embed selected Python functionality              | Chaquopy, Python, Ruff, pytest                                    |
| JavaScript/TypeScript web UI | WebView-based component or Capacitor             | Node.js, npm/pnpm, TypeScript, ESLint, Capacitor, Chrome DevTools |
| React Native                 | Integrate a React Native component into the host | React Native integration tools, Node.js, Metro, Jest              |
| Dart/Flutter                 | Flutter add-to-app module                        | Flutter add-to-app, Flutter SDK, Dart analyzer, Flutter tests     |

**ABI & 16 KB Page-Size Compatibility:**
For modules containing native libraries—including native libraries supplied by dependencies—add ABI and 16 KB page-size compatibility checks (alignment checks, SDK compatibility review, testing on a 16 KB environment).

---

## 6. Single Pipeline Workflow Architecture

```
Pull request / push / manual run
                 │
                 ▼
        Repository validation
        Versions, secrets, licences
                 │
                 ▼
        Parallel language checks
        Kotlin / Rust / Go / Python / JS
        Only for languages actually present
                 │
                 ▼
        Android lint + unit tests
                 │
                 ▼
        Build integrated debug APK
                 │
                 ▼
        Emulator integration tests
        Compose / Espresso / UI Automator
        Maestro smoke flows
                 │
                 ▼
        Upload debugging evidence
        APK, reports, logs, screenshots
                 │
                 ▼
        Required quality gate
                 │
        Trusted release trigger only
                 ▼
        Build signed release APK
                 │
                 ▼
        Release-build smoke test
        Signature + native-library checks
                 │
                 ▼
        Publish APK and release evidence
```

---

## 7. Rules for Stitching Codebases

1. **Licence:** Record its licence, attribution requirements, and redistribution conditions.
2. **Boundary:** Put it behind a module API or adapter rather than exposing its internals everywhere.
3. **Build ownership:** Define how GitHub builds it and which versions it requires.
4. **Contract:** Specify inputs, outputs, errors, threading, cancellation, and data ownership.
5. **Integration test:** Exercise the real bridge, not just mocked functions.
6. **Android compatibility:** Check permissions, minimum SDK, manifest changes, dependencies, and native ABIs.
7. **Release test:** Exercise the integrated component in the minified release build.
8. **Evidence:** Require the agent to provide changed files, passed checks, failed checks, and runtime proof.
