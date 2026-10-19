---
description: Gradle memory while the redroid container runs
priority: 20
---
- The redroid container (Docker, Android 15 + Shizuku 13.5, `localhost:5555`) counts as a running emulator: allow one Gradle invocation alongside it. A session cannot stop it, because Docker needs root.
- With redroid up, run a whole-project gate as sequential stages (`testDebugUnitTest`, `validateDebugScreenshotTest`, `lintDebug`, `:app:minifyReleaseWithR8`), each with `--max-workers=4`, never as one invocation.
- Before such a gate, end orphaned `GradleWorkerMain` processes (parent pid 1, left behind by a killed daemon) and idle Kotlin daemons. On 2026-10-19 the one-shot gate plus these leftovers got the Gradle daemon OOM-killed twice (`journalctl -k`).
