# Nova TV

A TV streaming client for Android, plus the Python tooling that builds, checks
and continuously improves it.

Independent of any other project in this repository: its own tree, its own
package (`dev.nova.tv`), its own CI.

```
nova-tv/
├── android/          Kotlin app (Compose for TV, Media3)
├── python/           56 scripts over a tested core
├── ops/              release and check scripts
└── .github/          CI: test, verify every script, scheduled collection
```

## Status, stated plainly

| Part | State |
|---|---|
| Python tooling — 8 modules, 34 unit tests | **Built and tested** |
| 56 scripts | **46 verified producing real output**; 3 need an AI key, 6 need open network, 1 loops by design |
| CI | Written; runs on push and every 6 hours |
| Kotlin app | **Source written, not compiled** — no JDK/Android SDK in the build environment |

That last row matters. There is no JDK, Kotlin compiler, Gradle or Android SDK
available where this was assembled, so the Kotlin code has **not** been compiled
or run. Treat it as a reviewed starting point that needs a real Android Studio
build before anyone calls it working.

## The Python side

See [`python/README.md`](python/README.md). Quick version:

```bash
cp nova.toml.example nova.toml
export GITHUB_TOKEN=ghp_...   AI_API_KEY=sk-...
make test && make scripts
```

## What it deliberately does not do

- **No auto-commit, no auto-push.** The improvement loop writes proposals only
  when they pass tests, the tree is clean, and `--apply` was passed. Everything
  it touches is snapshotted first and can be rolled back.
- **No bundled credentials.** Every token is read from the environment.
- **No model that pretends to exist.** `darkgpt` and `worm-gpt` are aliases in
  `models.json` — they point at whatever endpoint you configure. No public
  endpoint by those names exists; the tool says so rather than guessing.
