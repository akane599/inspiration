---
description: redroid device memory and leftover Gradle processes
priority: 20
---
- The redroid container (Docker, Android 15 + Shizuku 13.6.0, `localhost:5555`) is always on and uses about 1.9 GB. A session can't stop it, because Docker needs root. It counts like an emulator for the Gradle limits in android-build.
- Run Gradle through `bash .claude/scripts/gradle-slot.sh <tasks>` from the checkout or worktree root. It holds one of the host-wide slots in `/tmp/claude-<uid>/gradle-slots`, shared by every session and worktree, then runs `gradle-check.sh`. Don't write a per-session slot wrapper. `GRADLE_SLOTS=1` narrows it to one slot after an OOM.
- Before a whole-project gate, end orphaned `GradleWorkerMain` processes (parent pid 1, left behind by a killed daemon) and Kotlin daemons whose Gradle daemon is gone. On 2026-10-19, before the host had zram, a one-shot gate plus these leftovers got the Gradle daemon OOM-killed twice (`journalctl -k`).
- Match these processes with a bracketed pattern (`pgrep -f '[G]radleWorkerMain'`). A plain `pkill -f GradleWorkerMain` also matches the shell running the command and kills it.
