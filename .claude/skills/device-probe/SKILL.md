---
name: device-probe
description: Run Inspiration's debug ProbeReceiver probes (PROBE, PROBE_CAPTURE, PROBE_DIFF, PROBE_INSIGHTS, PROBE_REQUEST_PERMISSION) on an emulator and save the InspirationProbe JSON lines as device evidence under build/device-evidence/. Use this whenever a phase or ticket needs a device run, emulator evidence, "probe on API 35/36/37", a check that Shizuku/UserService reads work on a real system, or after a Room schema change that needs pm clear before re-probing — even if the request just says "run it on the emulator".
---

# Device probe

Device evidence in this project means the JSON lines the debug `ProbeReceiver` logs under the `InspirationProbe` tag. Before this skill, every device run re-typed the same boot / install / Shizuku / broadcast / logcat sequence by hand, and the evidence lived only in scrollback. The script makes the run repeatable and leaves a file you can cite in PROGRESS.md.

## Run

```bash
bash .claude/skills/device-probe/scripts/device-probe.sh --avd batstats-api36 --probe PROBE_CAPTURE [--clear] [--no-install] [--timeout 120] [--extras "--ei key 1"]
```

- `--avd`: the probe AVDs are `pixel-api35`, `batstats-api36`, `screenoff-api37`. If a device is already attached (or `ANDROID_SERIAL` is set), it is reused and `--avd` is ignored.
- `--probe`: the action suffix after `com.akane.inspiration.debug.`. Check `app/src/debug/kotlin/com/akane/inspiration/debug/ProbeReceiver.kt` for the actions that exist right now; new phases add probes there.
- `--clear`: `pm clear` after install. Use it when the Room schema changed in place (identityHash mismatch would otherwise crash the app on open).
- `--extras`: passed verbatim to `am broadcast` (e.g. PROBE_DIFF takes snapshot ids).

Output: `build/device-evidence/api<SDK>-<PROBE>.jsonl`, one JSON object per line, ending with `{"probe":"done"}` (or `{"probe":"timeout"}` when the probe's own deadline hit). `build/` is gitignored, so quote the relevant lines into PROGRESS.md or the story log instead of committing the file.

Exit codes: `0` finished, `2` setup failed (boot, installDebug, Shizuku missing/not started), `3` no terminal line before `--timeout` (or probe-side timeout). Treat the exit code as the oracle, not a grep of the output.

## Process rules that still apply

- Only the orchestrator boots emulators, and only one emulator/adb run happens at a time. Gradle (`installDebug`) counts toward the two-concurrent-Gradle budget.
- Run API levels sequentially: stop the emulator (`adb emu kill`) before switching `--avd`, otherwise the script reuses whichever device is attached.

## Shizuku permission

The script starts the Shizuku server via its adb starter if it is not running, but it cannot grant permission. If it reports a state other than `Ready`, run `--probe PROBE_REQUEST_PERMISSION` and have someone tap "Allow all the time" on the AVD (screen access needed — this is a real blocker in a headless run; say so rather than retrying). The grant survives `pm clear` because it is keyed by UID.

## Limits

Emulator batteries are synthetic: charge counters, discharge and per-UID mAh are not representative, and screen-off sleep is not real deep sleep. Evidence from this skill proves the read paths, parsing and wiring work on that API level; it does not validate battery numbers or thresholds. Say so when citing it.
