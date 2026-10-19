---
description: redroid device memory and leftover Gradle processes
priority: 20
---
- The redroid container (Docker, Android 15 + Shizuku 13.5, `localhost:5555`) is always on and uses about 1.9 GB. A session can't stop it, because Docker needs root. It counts like an emulator for the Gradle limits in android-build.
- Before a whole-project gate, end orphaned `GradleWorkerMain` processes (parent pid 1, left behind by a killed daemon) and Kotlin daemons whose Gradle daemon is gone. On 2026-10-19, before the host had zram, a one-shot gate plus these leftovers got the Gradle daemon OOM-killed twice (`journalctl -k`).
- Match these processes with a bracketed pattern (`pgrep -f '[G]radleWorkerMain'`). A plain `pkill -f GradleWorkerMain` also matches the shell running the command and kills it.
